package com.turbodm.app.download

import com.turbodm.app.data.local.TorrentDao
import com.turbodm.app.data.local.TorrentEntity
import com.turbodm.app.data.local.TorrentFileDao
import com.turbodm.app.data.local.TorrentFileEntity
import com.turbodm.app.data.repo.TorrentRepository
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
import com.turbodm.app.download.torrent.FakeTorrentClient
import com.turbodm.app.download.torrent.TorrentEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration check for the magnet flow: build the real [TorrentRepository]
 * and [TorrentEngine] against an in-memory [FakeTorrentClient] and verify
 * the end-to-end happy path.
 *
 * The full [DownloadController] is intentionally not used here because it
 * requires the Hilt graph (DB, OkHttp, etc.) which we can't stand up in pure
 * JVM tests. The controller's `addMagnet` is a thin shim over
 * `engine.fetchMetadata` + `repo.create`, which is what this test exercises
 * directly.
 */
class DownloadControllerMagnetTest {

    @Test fun `addMagnet persists a torrent row with file list`() = runBlocking {
        val repo = FakeTorrentRepository()
        val engine = TorrentEngine(FakeTorrentClient(), repo, SpeedTracker())
        val info = engine.fetchMetadata("magnet:?xt=urn:btih:abc123")!!
        assertEquals(3, info.files.size)

        val now = System.currentTimeMillis()
        val row = TorrentEntity(
            magnet = "magnet:?xt=urn:btih:abc123",
            name = info.name,
            totalBytes = info.totalBytes,
            downloadedBytes = 0L,
            status = DownloadStatus.ANALYZING,
            errorMessage = null,
            saveDir = "/tmp/fake",
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            pauseReason = PauseReason.NONE
        )
        val files = info.files.map {
            TorrentFileEntity(torrentId = 0L, index = it.index, path = it.path, size = it.size)
        }
        val id = repo.create(row, files)
        val stored = repo.get(id)
        assertNotNull(stored)
        assertEquals(DownloadStatus.ANALYZING, stored!!.status)
        assertEquals(row.magnet, stored.magnet)
        val storedFiles = repo.filesFor(id)
        assertEquals(3, storedFiles.size)
        // Default selection is true (qBittorrent convention) — the entity default
        // `selected = true` is applied at insertion.
        assertTrue(storedFiles.all { it.selected })
    }

    @Test fun `TorrentInfo derivation is stable across two calls with same magnet`() = runBlocking {
        val repo = FakeTorrentRepository()
        val engine = TorrentEngine(FakeTorrentClient(), repo, SpeedTracker())
        val a = engine.fetchMetadata("magnet:?xt=urn:btih:stable")!!
        val b = engine.fetchMetadata("magnet:?xt=urn:btih:stable")!!
        assertEquals(a.name, b.name)
        assertEquals(a.infoHash, b.infoHash)
        assertEquals(a.totalBytes, b.totalBytes)
    }
}

/** In-memory implementations of the torrent DAOs so [TorrentRepository] can run on plain JVM. */
class FakeTorrentRepository : TorrentRepository(FakeTorrentDao(), FakeTorrentFileDao())

class FakeTorrentDao : TorrentDao {
    val fileDao = FakeTorrentFileDao()
    private val rows = mutableListOf<TorrentEntity>()
    private var nextId = 1L
    override suspend fun insert(entity: TorrentEntity): Long {
        val id = nextId++
        rows.add(entity.copy(id = id))
        return id
    }
    override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
    override fun observeAll(): Flow<List<TorrentEntity>> = MutableStateFlow(rows.toList()).asStateFlow()
    override suspend fun getById(id: Long): TorrentEntity? = rows.firstOrNull { it.id == id }
    override fun observeById(id: Long): Flow<TorrentEntity?> = MutableStateFlow(rows.firstOrNull { it.id == id }).asStateFlow()
    override suspend fun setStatus(id: Long, status: DownloadStatus, now: Long) {
        rows.replaceAll { if (it.id == id) it.copy(status = status, updatedAt = now) else it }
    }
    override suspend fun setStatusWithReason(id: Long, status: DownloadStatus, reason: PauseReason, now: Long) {
        rows.replaceAll { if (it.id == id) it.copy(status = status, pauseReason = reason, updatedAt = now) else it }
    }
    override suspend fun setDownloadedBytes(id: Long, bytes: Long, now: Long) {
        rows.replaceAll { if (it.id == id) it.copy(downloadedBytes = bytes, updatedAt = now) else it }
    }
    override suspend fun setMetadata(id: Long, hash: String, name: String, total: Long, now: Long) {
        rows.replaceAll { if (it.id == id) it.copy(infoHash = hash, name = name, totalBytes = total, updatedAt = now) else it }
    }
    override suspend fun setError(id: Long, msg: String?, status: DownloadStatus, now: Long) {
        rows.replaceAll { if (it.id == id) it.copy(errorMessage = msg, status = status, updatedAt = now) else it }
    }
    override suspend fun markCompleted(id: Long, now: Long) {
        rows.replaceAll { if (it.id == id) it.copy(status = DownloadStatus.COMPLETED, completedAt = now, updatedAt = now) else it }
    }
}

class FakeTorrentFileDao : TorrentFileDao {
    private val rows = mutableListOf<TorrentFileEntity>()
    private var nextId = 1L
    override suspend fun insertAll(files: List<TorrentFileEntity>) {
        files.forEach { f ->
            rows.add(f.copy(id = nextId++, torrentId = f.torrentId))
        }
    }
    override suspend fun forTorrent(torrentId: Long): List<TorrentFileEntity> = rows.filter { it.torrentId == torrentId }
    override fun observeForTorrent(torrentId: Long): Flow<List<TorrentFileEntity>> = MutableStateFlow(rows.filter { it.torrentId == torrentId }).asStateFlow()
    override suspend fun setSelected(torrentId: Long, index: Int, selected: Boolean) {
        rows.replaceAll { if (it.torrentId == torrentId && it.index == index) it.copy(selected = selected) else it }
    }
    override suspend fun setDownloaded(torrentId: Long, index: Int, bytes: Long) {
        rows.replaceAll { if (it.torrentId == torrentId && it.index == index) it.copy(downloadedBytes = bytes) else it }
    }
    override suspend fun deleteForTorrent(id: Long) { rows.removeAll { it.torrentId == id } }
    override suspend fun selectedForTorrent(torrentId: Long): List<TorrentFileEntity> = rows.filter { it.torrentId == torrentId && it.selected }
}

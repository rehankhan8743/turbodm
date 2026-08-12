package com.turbodm.app.data.repo

import com.turbodm.app.data.local.TorrentDao
import com.turbodm.app.data.local.TorrentEntity
import com.turbodm.app.data.local.TorrentFileDao
import com.turbodm.app.data.local.TorrentFileEntity
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surface for the torrent subsystem. Mirrors [DownloadRepository] in shape
 * even though the underlying rows are different — `torrents` and `torrent_files`
 * instead of `downloads` and `chunks`.
 *
 * The torrent entity stays close to [com.turbodm.app.data.local.TorrentEntity]
 * without a pure-domain wrapper for now; the parent row is a thin persistence
 * record and the engine maps it to/from [com.turbodm.app.download.torrent.TorrentInfo]
 * at the boundary. If a domain layer is added later, these can be swapped.
 */
@Singleton
open class TorrentRepository @Inject constructor(
    private val dao: TorrentDao,
    private val fileDao: TorrentFileDao
) {
    fun observeAll(): Flow<List<TorrentEntity>> = dao.observeAll()
    fun observeById(id: Long): Flow<TorrentEntity?> = dao.observeById(id)
    fun observeFiles(torrentId: Long): Flow<List<TorrentFileEntity>> =
        fileDao.observeForTorrent(torrentId)

    suspend fun get(id: Long): TorrentEntity? = dao.getById(id)
    suspend fun filesFor(torrentId: Long): List<TorrentFileEntity> = fileDao.forTorrent(torrentId)
    suspend fun selectedFiles(torrentId: Long): List<TorrentFileEntity> = fileDao.selectedForTorrent(torrentId)

    /** Creates a torrent row and inserts the file list in one call. The caller
     *  supplies a fully-populated [TorrentEntity] (id = 0 because Room auto-generates). */
    suspend fun create(torrent: TorrentEntity, files: List<TorrentFileEntity>): Long {
        val id = dao.insert(torrent.copy(id = 0L))
        // Bind the children to the new parent id and persist.
        fileDao.insertAll(files.map { it.copy(id = 0L, torrentId = id) })
        return id
    }

    suspend fun setStatus(id: Long, status: DownloadStatus) = dao.setStatus(id, status)
    suspend fun setStatus(id: Long, status: DownloadStatus, reason: PauseReason) =
        dao.setStatusWithReason(id, status, reason)
    suspend fun setDownloaded(id: Long, bytes: Long) = dao.setDownloadedBytes(id, bytes)
    suspend fun setMetadata(id: Long, hash: String, name: String, total: Long) =
        dao.setMetadata(id, hash, name, total)
    suspend fun setError(id: Long, msg: String?, status: DownloadStatus) = dao.setError(id, msg, status)
    suspend fun markCompleted(id: Long) = dao.markCompleted(id)
    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun setFileSelected(torrentId: Long, index: Int, selected: Boolean) =
        fileDao.setSelected(torrentId, index, selected)
    suspend fun setFileDownloaded(torrentId: Long, index: Int, bytes: Long) =
        fileDao.setDownloaded(torrentId, index, bytes)
}

/** Domain-side helper the engine can pass around without hauling the Room entity. */
data class TorrentFileSnapshot(
    val index: Int,
    val path: String,
    val size: Long,
    val downloadedBytes: Long,
    val selected: Boolean
)

data class TorrentSnapshot(
    val id: Long,
    val magnet: String,
    val infoHash: String?,
    val name: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val errorMessage: String?,
    val saveDir: String,
    val files: List<TorrentFileSnapshot>
)

fun TorrentEntity.toSnapshot(files: List<TorrentFileEntity>): TorrentSnapshot = TorrentSnapshot(
    id = id,
    magnet = magnet,
    infoHash = infoHash,
    name = name,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    status = status,
    errorMessage = errorMessage,
    saveDir = saveDir,
    files = files.map {
        TorrentFileSnapshot(
            index = it.index,
            path = it.path,
            size = it.size,
            downloadedBytes = it.downloadedBytes,
            selected = it.selected
        )
    }
)

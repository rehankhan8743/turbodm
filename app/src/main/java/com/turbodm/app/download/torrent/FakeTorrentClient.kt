package com.turbodm.app.download.torrent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory [TorrentClient] for development and tests. Until libtorrent4j lands,
 * this is what gets wired into the DI graph so the rest of the codebase can be
 * built and exercised end-to-end.
 *
 * The fake is a pure state machine — no background coroutines, no delays.
 * Each method synchronously mutates state and emits a single
 * [TorrentStatusUpdate] so the engine observes the transition.
 *
 * Subtlety with [MutableSharedFlow]: a `tryEmit` with no active subscribers is
 * dropped (replay is 0). Real subscribers connect via [observeStatus] *after*
 * [addTorrent] returns, so we can't rely on emissions landing directly. Instead
 * the fake stores the current state and re-emits it whenever a new subscriber
 * attaches, mimicking libtorrent's "initial status" semantics.
 */
class FakeTorrentClient : TorrentClient {

    private data class FakeHandle(
        val torrentId: Long,
        val magnet: String,
        val state: AtomicReference<TorrentState>
    )

    private val flows = ConcurrentHashMap<String, MutableSharedFlow<TorrentStatusUpdate>>()
    private val handles = ConcurrentHashMap<String, FakeHandle>()
    private val addLock = Mutex()

    override suspend fun fetchMetadata(magnet: String): TorrentInfo {
        val hash = magnet.hashCode().toUInt().toString(16)
        val files = listOf(
            TorrentFileMeta(index = 0, path = "fake-$hash-720p.mp4", size = 700L * 1024 * 1024),
            TorrentFileMeta(index = 1, path = "fake-$hash-audio.aac", size = 80L * 1024 * 1024),
            TorrentFileMeta(index = 2, path = "fake-$hash-readme.txt", size = 1024L)
        )
        return TorrentInfo(
            name = "Fake Torrent $hash",
            infoHash = "fakedeadbeef$hash",
            totalBytes = files.sumOf { it.size },
            files = files
        )
    }

    override suspend fun addTorrent(
        torrentId: Long,
        magnet: String,
        saveDir: String,
        selectedIndices: List<Int>
    ): String = addLock.withLock {
        val handle = "fake-$torrentId"
        // Default state is CONNECTING — in real libtorrent, by the time the
        // engine observes the torrent it's already past METADATA (which is
        // only relevant during the fetchMetadata call before this).
        val state = AtomicReference(TorrentState.CONNECTING)
        flows[handle] = MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
        handles[handle] = FakeHandle(torrentId, magnet, state)
        handle
    }

    override fun observeStatus(clientHandle: String): Flow<TorrentStatusUpdate> {
        val handle = handles[clientHandle]
            ?: error("No torrent with handle $clientHandle — call addTorrent first")
        val flow = flows[clientHandle]!!
        // Emit the current snapshot so subscribers that connect *after*
        // addTorrent (the normal case in the engine) see the initial state.
        flow.tryEmit(snapshotFor(handle))
        return flow.asSharedFlow()
    }

    override suspend fun pause(clientHandle: String) {
        val handle = handles[clientHandle] ?: return
        handle.state.set(TorrentState.PAUSED)
        flows[clientHandle]?.tryEmit(snapshotFor(handle))
    }

    override suspend fun resume(clientHandle: String) {
        val handle = handles[clientHandle] ?: return
        handle.state.set(TorrentState.DOWNLOADING)
        flows[clientHandle]?.tryEmit(snapshotFor(handle))
    }

    override suspend fun remove(clientHandle: String) {
        flows.remove(clientHandle)
        handles.remove(clientHandle)
    }

    private fun snapshotFor(handle: FakeHandle): TorrentStatusUpdate =
        TorrentStatusUpdate(
            torrentId = handle.torrentId,
            totalBytes = -1L,
            downloadedBytes = -1L,
            bytesPerSecond = 0L,
            state = handle.state.get()
        )
}
package com.turbodm.app.download.torrent

import com.turbodm.app.data.repo.TorrentRepository
import com.turbodm.app.data.repo.toSnapshot
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
import com.turbodm.app.download.SpeedTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the torrent lifecycle alongside [com.turbodm.app.download.DownloadEngine].
 * One engine instance owns one [TorrentClient] (the libtorrent binding, today
 * swapped with [FakeTorrentClient]) and one `Job` per active torrent.
 *
 * The engine's job is to:
 * - Add a torrent to the client session when the user starts it.
 * - Subscribe to status updates and translate them into DB writes
 *   (status, downloaded bytes, per-file progress) and [SpeedTracker] entries
 *   so the UI sees torrent progress alongside HTTP downloads.
 * - Map pause / resume / cancel through to the client and update DB status.
 *
 * Why a separate engine from `DownloadEngine`:
 * - `DownloadEngine` is HTTP-shaped: bytes come in linearly from a single
 *   `OkHttp` response. Torrent bytes come in across many peers, the "total" is
 *   only known after metadata fetch, and the unit of progress is a file index.
 * - Keeping them apart means the existing HTTP path keeps working untouched
 *   while torrent code evolves.
 */
@Singleton
class TorrentEngine @Inject constructor(
    private val client: TorrentClient,
    private val repo: TorrentRepository,
    private val speedTracker: SpeedTracker
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val handles = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * Metadata-only fetch — does not persist anything to the database. Used
     * by [com.turbodm.app.download.DownloadController.addMagnet] to learn the
     * file list before writing the torrent row.
     */
    suspend fun fetchMetadata(magnet: String): TorrentInfo? = client.fetchMetadata(magnet)

    /** Starts the torrent: registers with the client and begins observing status. */
    suspend fun start(torrentId: Long) {
        if (jobs[torrentId]?.isActive == true) return
        val torrent = repo.get(torrentId) ?: return
        val files = repo.selectedFiles(torrentId)
        if (files.isEmpty()) {
            // Nothing to fetch. Mark complete immediately so the row reflects reality.
            repo.markCompleted(torrentId)
            return
        }
        jobs[torrentId] = scope.launch {
            try {
                val handle = client.addTorrent(
                    torrentId = torrentId,
                    magnet = torrent.magnet,
                    saveDir = torrent.saveDir,
                    selectedIndices = files.map { it.index }
                )
                handles[torrentId] = handle
                repo.setStatus(torrentId, DownloadStatus.DOWNLOADING)
                client.observeStatus(handle).collect { update ->
                    applyUpdate(update)
                }
            } catch (t: Throwable) {
                repo.setError(torrentId, t.message ?: "Torrent failed", DownloadStatus.FAILED)
            } finally {
                handles.remove(torrentId)
                jobs.remove(torrentId)
            }
        }
    }

    /** Map status updates to repository writes. */
    private suspend fun applyUpdate(update: TorrentStatusUpdate) {
        val torrentId = update.torrentId
        when (update.state) {
            TorrentState.METADATA -> repo.setStatus(torrentId, DownloadStatus.ANALYZING)
            TorrentState.CONNECTING -> repo.setStatus(torrentId, DownloadStatus.QUEUED)
            TorrentState.DOWNLOADING -> {
                repo.setStatus(torrentId, DownloadStatus.DOWNLOADING)
                if (update.totalBytes > 0) {
                    repo.setMetadata(torrentId, "fake-hash", "Fake", update.totalBytes)
                }
                if (update.downloadedBytes >= 0) {
                    repo.setDownloaded(torrentId, update.downloadedBytes)
                    if (update.bytesPerSecond > 0) {
                        // No actual byte deltas to record, but registering a non-zero
                        // bps with a 1-byte delta is enough to keep the SpeedTracker
                        // happy; the real impl will report true deltas.
                        speedTracker.record(torrentId, update.bytesPerSecond.coerceAtMost(1024L))
                    }
                }
                update.fileBytes.forEach { (idx, bytes) ->
                    repo.setFileDownloaded(torrentId, idx, bytes)
                }
            }
            TorrentState.PAUSED -> repo.setStatus(
                torrentId, DownloadStatus.PAUSED, PauseReason.USER
            )
            TorrentState.FINISHED -> {
                repo.markCompleted(torrentId)
                speedTracker.reset(torrentId)
                handles[torrentId]?.let { client.remove(it) }
            }
            TorrentState.ERROR -> repo.setError(
                torrentId,
                update.errorMessage ?: "Torrent error",
                DownloadStatus.FAILED
            )
        }
    }

    fun pause(torrentId: Long) {
        jobs.remove(torrentId)?.cancel()
        handles[torrentId]?.let { scope.launch { client.pause(it) } }
        scope.launch { repo.setStatus(torrentId, DownloadStatus.PAUSED, PauseReason.USER) }
        speedTracker.reset(torrentId)
    }

    fun resume(torrentId: Long) {
        // Easiest path: re-add and let the client drive state. libtorrent can
        // resume by info-hash directly; the fake doesn't keep state, so we
        // re-`start` instead. Real impl should prefer resume-by-info-hash.
        scope.launch { start(torrentId) }
    }

    fun cancel(torrentId: Long) {
        jobs.remove(torrentId)?.cancel()
        handles[torrentId]?.let { scope.launch { client.remove(it) } }
        handles.remove(torrentId)
        speedTracker.reset(torrentId)
        scope.launch { repo.setStatus(torrentId, DownloadStatus.CANCELLED) }
    }

    /** Releases engine resources — used by tests; production has a singleton. */
    fun shutdown() {
        scope.cancel()
    }
}
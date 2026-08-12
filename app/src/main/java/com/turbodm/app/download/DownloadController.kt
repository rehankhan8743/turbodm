package com.turbodm.app.download

import android.content.Context
import com.turbodm.app.data.local.ChunkDao
import com.turbodm.app.data.local.TorrentEntity
import com.turbodm.app.data.local.TorrentFileEntity
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.data.repo.TorrentRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
import com.turbodm.app.download.scheduler.SchedulerWorker
import com.turbodm.app.download.torrent.TorrentEngine
import com.turbodm.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public entry point for the download subsystem. UI and the foreground service
 * both go through this so we can keep the engine concerns in one place.
 *
 * v2: segment count comes from settings (Phase 1 wire-up kept `segments = 1`;
 *     this is the place where it now flows from settings into the entity).
 * v2: pause/resume actually round-trip status; resume puts the row back in
 *     QUEUED so the QueueManager picks it up and respects maxParallel.
 * v3: pause/resume carry a [PauseReason] so the [ConnectivityWatcher] knows
 *     which pauses it can auto-resume.
 * v6 (Phase 6): adds magnet-link entry points. These use a separate set of
 *     methods ([addMagnet] / [startTorrent] / [pauseTorrent] / [cancelTorrent]
 *     / [deleteTorrent]) so the existing single-file download methods stay
 *     exactly as they were. UI passes the row type explicitly.
 */
@Singleton
open class DownloadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val torrentRepo: TorrentRepository,
    private val schemeRegistry: SchemeRegistry,
    private val engine: DownloadEngine,
    private val torrentEngine: TorrentEngine,
    private val queue: QueueManager,
    private val connectivityWatcher: ConnectivityWatcher,
    private val settings: SettingsRepository,
    private val chunkDao: ChunkDao,
    private val rulesEngine: RulesEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        queue.start()
        connectivityWatcher.start()
    }

    suspend fun addAndStart(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
        cookies: String? = null,
        expectedSha256: String? = null,
        /** Epoch ms to start the download. 0 or past = start now. */
        scheduleAtEpochMs: Long = 0L,
        /** For streaming-site URLs, pick the best audio stream instead of video. */
        preferAudioOnly: Boolean = false
    ): Long {
        require(url.isNotBlank()) { "URL is required" }
        // Magnet links have their own multi-step flow; reject them here with a
        // clear error so callers know to use [addMagnet].
        val scheme = SchemeRegistry.extractScheme(url)
        if (scheme == "magnet") error("Use addMagnet for magnet: URLs")
        val handler = schemeRegistry.handlerFor(url)
            ?: error("Unsupported URL scheme: $url")
        // Handlers do their own network I/O; funnel through IO so a handler
        // that forgot to switch dispatchers can never block the main thread.
        val info = withContext(Dispatchers.IO) { handler.probe(url) }
        val snap = settings.flow.first()
        // Rules engine: auto-route into a per-type subfolder. For streaming URLs
        // the filename is a placeholder ("video title" from NewPipe), so force
        // a temporary extension matching the user's intent (audio → music/, video → videos/).
        // The streaming handler will re-target the row once it knows the real filename.
        val isStreaming = handler is com.turbodm.app.download.handlers.StreamingSchemeHandler
        val effectiveFileName = when {
            // Streaming URLs have a fake title; hint the extension so the rules
            // engine puts it in the right bucket immediately.
            isStreaming && preferAudioOnly -> info.fileName.substringBeforeLast('.') + ".opus"
            isStreaming -> info.fileName.substringBeforeLast('.') + ".mp4"
            else -> info.fileName
        }
        val targetPath = rulesEngine.resolveTargetPath(
            baseDir = snap.downloadDir,
            fileName = effectiveFileName,
            mimeType = info.mimeType,
            enabled = snap.rulesEngineEnabled
        )
        // Adaptive segment count: pick based on file size so small files don't
        // pay segment overhead and huge files saturate the pipe. User's
        // defaultSegments setting acts as the cap.
        val segments = if (info.supportsRange && info.totalBytes > 0) {
            ChunkPlanner.adaptiveSegments(info.totalBytes, snap.defaultSegments)
        } else 1
        val scheduledNow = scheduleAtEpochMs > System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val download = Download(
            url = url,
            fileName = info.fileName,
            mimeType = info.mimeType,
            totalBytes = info.totalBytes,
            downloadedBytes = 0L,
            status = if (scheduledNow) DownloadStatus.SCHEDULED else DownloadStatus.QUEUED,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            targetPath = targetPath,
            supportsRange = info.supportsRange,
            segments = segments,
            priority = 0,
            referer = referer,
            userAgent = userAgent,
            cookies = cookies,
            expectedSha256 = expectedSha256,
            preferAudioOnly = preferAudioOnly,
            scheduledForEpochMs = if (scheduledNow) scheduleAtEpochMs else 0L
        )
        val id = repo.create(download)
        if (scheduledNow) {
            // Park the row and let WorkManager re-queue it when its time arrives.
            SchedulerWorker.schedule(
                context,
                downloadId = id,
                atEpochMs = scheduleAtEpochMs,
                wifiOnly = snap.wifiOnly
            )
            return id
        }
        // QueueManager observes QUEUED rows and starts the engine. No direct engine.start here.
        return id
    }

    /**
     * Probe a magnet link's metadata and persist a torrent row in ANALYZING
     * status. Returns the torrent id; the caller navigates to the file picker.
     *
     * Errors fall through as exceptions; the caller is expected to surface
     * them in the UI.
     */
    suspend fun addMagnet(magnet: String): Long {
        require(magnet.isNotBlank()) { "Magnet is required" }
        val snap = settings.flow.first()
        if (!snap.magnetEnabled) error("Magnet links are disabled in Settings")
        // Reuse the client's metadata fetch — the engine keeps the client private.
        // We do that through TorrentEngine by way of an explicit fetch call.
        // Funnel through IO: real libtorrent4j hits the network here; FakeTorrentClient
        // is in-memory and pays no cost. Belt-and-suspenders so a future client swap
        // can't trigger NetworkOnMainThreadException from the ViewModel caller.
        val info = withContext(Dispatchers.IO) { torrentEngine.fetchMetadata(magnet) }
            ?: error("Could not fetch torrent metadata")
        val now = System.currentTimeMillis()
        val torrent = TorrentEntity(
            magnet = magnet,
            name = info.name,
            totalBytes = info.totalBytes,
            downloadedBytes = 0L,
            status = DownloadStatus.ANALYZING,
            errorMessage = null,
            saveDir = snap.downloadDir,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            pauseReason = PauseReason.NONE
        )
        val fileRows = info.files.map {
            TorrentFileEntity(
                torrentId = 0L, // bound by repo.create
                index = it.index,
                path = it.path,
                size = it.size
            )
        }
        val id = torrentRepo.create(torrent, fileRows)
        // Persist infoHash + name on the row for completeness; the fake uses
        // a derived hash but real libtorrent will return the canonical one.
        torrentRepo.setMetadata(id, info.infoHash, info.name, info.totalBytes)
        return id
    }

    /**
     * Start downloading the torrent with the given selection. `selectedIndices`
     * are 0-based file indices within the torrent. Files not in the set are
     * marked unselected in the DB.
     */
    suspend fun startTorrent(torrentId: Long, selectedIndices: Set<Int>) {
        val files = torrentRepo.filesFor(torrentId)
        files.forEach { f ->
            val shouldSelect = f.index in selectedIndices
            if (f.selected != shouldSelect) {
                torrentRepo.setFileSelected(torrentId, f.index, shouldSelect)
            }
        }
        torrentEngine.start(torrentId)
    }

    fun pauseTorrent(id: Long) = torrentEngine.pause(id)
    fun resumeTorrent(id: Long) = torrentEngine.resume(id)
    fun cancelTorrent(id: Long) = torrentEngine.cancel(id)

    suspend fun deleteTorrent(id: Long) {
        torrentEngine.cancel(id)
        torrentRepo.delete(id)
    }

    /** User-initiated pause. The watcher will not auto-resume. */
    fun pause(id: Long) = pause(id, PauseReason.USER)

    /** Tagged pause. NETWORK-paused rows can be auto-resumed; USER-paused cannot. */
    fun pause(id: Long, reason: PauseReason) {
        // Don't override a USER pause with a NETWORK pause — the user explicitly
        // wanted this stopped.
        if (reason == PauseReason.NETWORK) {
            scope.launch {
                val d = repo.get(id) ?: return@launch
                if (d.pauseReason == PauseReason.USER) return@launch
                engine.pause(id)
                repo.setStatus(id, DownloadStatus.PAUSED, reason)
            }
        } else {
            engine.pause(id)
            scope.launch { repo.setStatus(id, DownloadStatus.PAUSED, reason) }
        }
    }

    /** User-initiated resume. */
    fun resume(id: Long) = resume(id, PauseReason.NONE)

    /** Tagged resume. Resets the pause reason so subsequent network drops re-pause. */
    fun resume(id: Long, reason: PauseReason) {
        scope.launch {
            val d = repo.get(id) ?: return@launch
            // Only auto-resume rows the system paused (NETWORK / SCHEDULED).
            // User-paused rows are off-limits unless the user explicitly resumes.
            if (reason == PauseReason.NONE && d.pauseReason == PauseReason.USER) return@launch
            repo.setStatus(id, DownloadStatus.QUEUED, reason)
        }
    }

    fun cancel(id: Long) = engine.cancel(id)

    suspend fun delete(id: Long) {
        engine.cancel(id)
        // Remove any on-disk remnants before dropping the row — otherwise the
        // DB row disappears but a stray .part file keeps eating storage.
        repo.get(id)?.let { d ->
            listOf(File(d.targetPath), File(d.targetPath + ".part")).forEach { f ->
                if (f.exists()) runCatching { f.delete() }
            }
        }
        chunkDao.deleteForDownload(id)
        repo.delete(id)
    }
}
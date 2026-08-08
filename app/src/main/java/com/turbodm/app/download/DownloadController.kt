package com.turbodm.app.download

import android.content.Context
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
import com.turbodm.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 */
@Singleton
class DownloadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val analyzer: LinkAnalyzer,
    private val engine: DownloadEngine,
    private val queue: QueueManager,
    private val connectivityWatcher: ConnectivityWatcher,
    private val settings: SettingsRepository
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
        expectedSha256: String? = null
    ): Long {
        require(url.isNotBlank()) { "URL is required" }
        val snap = settings.flow.first()
        val info = analyzer.analyze(url)
        val targetPath = "${snap.downloadDir}/${info.fileName}"
        val segments = if (info.supportsRange && info.totalBytes > 0) snap.defaultSegments else 1
        val now = System.currentTimeMillis()
        val download = Download(
            url = url,
            fileName = info.fileName,
            mimeType = info.mimeType,
            totalBytes = info.totalBytes,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED,
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
            expectedSha256 = expectedSha256
        )
        val id = repo.create(download)
        // QueueManager observes QUEUED rows and starts the engine. No direct engine.start here.
        return id
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
        repo.delete(id)
    }
}

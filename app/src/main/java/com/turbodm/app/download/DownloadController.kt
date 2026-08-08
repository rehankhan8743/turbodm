package com.turbodm.app.download

import android.content.Context
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
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
 */
@Singleton
class DownloadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val analyzer: LinkAnalyzer,
    private val engine: DownloadEngine,
    private val queue: QueueManager,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { queue.start() }

    suspend fun addAndStart(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
        cookies: String? = null
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
            cookies = cookies
        )
        val id = repo.create(download)
        // QueueManager observes QUEUED rows and starts the engine. No direct engine.start here.
        return id
    }

    fun pause(id: Long) = engine.pause(id)

    fun resume(id: Long) {
        scope.launch { repo.setStatus(id, DownloadStatus.QUEUED) }
        // QueueManager will pick it up via the same flow that enqueues new ones.
    }

    fun cancel(id: Long) = engine.cancel(id)

    suspend fun delete(id: Long) {
        engine.cancel(id)
        repo.delete(id)
    }
}

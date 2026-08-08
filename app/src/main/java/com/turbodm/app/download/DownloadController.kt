package com.turbodm.app.download

import android.content.Context
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public entry point for the download subsystem. UI and the foreground service
 * both go through this so we can keep the engine concerns in one place.
 */
@Singleton
class DownloadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val analyzer: LinkAnalyzer,
    private val engine: DownloadEngine,
    private val settings: SettingsRepository
) {
    suspend fun addAndStart(url: String, referer: String? = null, userAgent: String? = null, cookies: String? = null): Long {
        require(url.isNotBlank()) { "URL is required" }
        val snap = settings.flow.first()
        val info = analyzer.analyze(url)
        val targetPath = "${snap.downloadDir}/${info.fileName}"
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
            segments = 1,
            priority = 0,
            referer = referer,
            userAgent = userAgent,
            cookies = cookies
        )
        val id = repo.create(download)
        engine.start(id)
        return id
    }

    fun pause(id: Long) = engine.pause(id)
    fun resume(id: Long) = engine.start(id)
    fun cancel(id: Long) = engine.cancel(id)
    suspend fun delete(id: Long) {
        engine.cancel(id)
        repo.delete(id)
    }
}

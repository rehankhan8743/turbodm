package com.turbodm.app.domain.model

/**
 * Pure-domain download record. Mirrors the Room entity but is free of framework concerns.
 */
data class Download(
    val id: Long = 0L,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val targetPath: String,
    val supportsRange: Boolean,
    val segments: Int,
    val priority: Int,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val sha256: String? = null
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f
        else (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

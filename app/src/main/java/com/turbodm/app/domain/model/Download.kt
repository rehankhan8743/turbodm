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
    val expectedSha256: String? = null,
    val computedSha256: String? = null,
    val pauseReason: PauseReason = PauseReason.NONE,
    /**
     * When true and the URL is a streaming site (YouTube, SoundCloud, TikTok…),
     * pick the best *audio* stream instead of video. Falls back to video if
     * no audio stream is found.
     */
    val preferAudioOnly: Boolean = false,
    /**
     * Epoch ms when this download should auto-start. `0` means "no schedule —
     * start as soon as a slot is free" (current behavior).
     *
     * Set by the Add-download screen when the user picks a future time; the
     * SchedulerWorker re-queues the row once the wall clock passes it AND any
     * network constraint is satisfied.
     */
    val scheduledForEpochMs: Long = 0L
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f
        else (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

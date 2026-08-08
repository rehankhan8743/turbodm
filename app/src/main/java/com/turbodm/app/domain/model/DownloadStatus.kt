package com.turbodm.app.domain.model

/**
 * Lifecycle state of a download. Persisted in Room as a string for forward compatibility.
 */
enum class DownloadStatus {
    QUEUED,
    ANALYZING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isActive: Boolean
        get() = this == ANALYZING || this == DOWNLOADING
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

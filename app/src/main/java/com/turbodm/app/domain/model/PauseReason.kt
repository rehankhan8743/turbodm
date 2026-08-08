package com.turbodm.app.domain.model

/**
 * Why a download is in [DownloadStatus.PAUSED] or [DownloadStatus.QUEUED].
 *
 * The watcher needs to know which paused rows it can auto-resume (network drops
 * are temporary) and which the user controls (manual pauses should not auto-resume).
 */
enum class PauseReason {
    /** Default state. Not paused for any reason. */
    NONE,

    /** User tapped Pause. The watcher must not auto-resume. */
    USER,

    /** Wi-Fi dropped or device left the allowed network. The watcher will auto-resume. */
    NETWORK,

    /** App or user is enforcing a time-based rule. Future: scheduler. */
    SCHEDULED,
}

package com.turbodm.app.domain.model

/**
 * One byte-range slot in a multi-part download. Persisted so we can resume after a process kill.
 */
data class ChunkState(
    val id: Long = 0L,
    val downloadId: Long,
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long
) {
    val isComplete: Boolean get() = downloadedBytes >= (endByte - startByte + 1)
}

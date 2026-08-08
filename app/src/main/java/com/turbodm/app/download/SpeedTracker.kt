package com.turbodm.app.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks bytes-per-second using a sliding 1-second window.
 *
 * Designed to be called from many chunk coroutines concurrently — all math is
 * per-key, no global locks. The default window length is short enough that the
 * reported bps reflects "right now" rather than "lifetime average".
 */
class SpeedTracker(private val windowMs: Long = 1000L) {

    private data class Sample(val timestamp: Long, val bytes: Long)

    private val samples = HashMap<Long, ArrayDeque<Sample>>()
    private val _bps = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val bps: StateFlow<Map<Long, Long>> = _bps.asStateFlow()

    /** Records [bytes] additional bytes transferred for [downloadId]. */
    @Synchronized
    fun record(downloadId: Long, bytes: Long) {
        if (bytes <= 0) return
        val now = System.currentTimeMillis()
        val q = samples.getOrPut(downloadId) { ArrayDeque() }
        q.addLast(Sample(now, bytes))
        trim(q, now)
        _bps.value = snapshot(samples, now)
    }

    /** Resets state for a download, e.g. on completion or cancel. */
    @Synchronized
    fun reset(downloadId: Long) {
        samples.remove(downloadId)
        _bps.value = _bps.value - downloadId
    }

    private fun trim(q: ArrayDeque<Sample>, now: Long) {
        val cutoff = now - windowMs
        while (q.isNotEmpty() && q.first().timestamp < cutoff) q.removeFirst()
    }

    private fun snapshot(samples: Map<Long, ArrayDeque<Sample>>, now: Long): Map<Long, Long> {
        if (samples.isEmpty()) return emptyMap()
        val out = HashMap<Long, Long>(samples.size)
        for ((id, q) in samples) {
            trim(q, now)
            if (q.isEmpty()) continue
            val total = q.sumOf { it.bytes }
            // Scale to bytes/sec so the UI sees a real bps regardless of window length.
            val span = (now - q.first().timestamp).coerceAtLeast(1L)
            out[id] = total * 1000L / span
        }
        return out
    }
}

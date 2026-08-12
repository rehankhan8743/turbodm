package com.turbodm.app.download

import kotlin.math.max
import kotlin.math.min

/**
 * Computes per-byte ranges for a segmented download.
 *
 * We avoid more than ~64 MB per chunk because:
 *   - RandomAccessFile.seek cost is negligible up to that scale
 *   - we want a chunk to complete in seconds, not minutes, so retries don't pay
 *     the full transfer cost when something flakes
 */
object ChunkPlanner {

    data class Spec(val index: Int, val startByte: Long, val endByte: Long) {
        val length: Long get() = endByte - startByte + 1
    }

    /** Returns [count] non-empty, contiguous, non-overlapping ranges that fully cover [0, totalBytes). */
    fun plan(totalBytes: Long, count: Int): List<Spec> {
        require(totalBytes > 0) { "Cannot plan chunks for empty file" }
        val n = count.coerceAtLeast(1)
        if (n == 1) return listOf(Spec(0, 0, totalBytes - 1))

        // Clamp by MAX_CHUNK_SIZE so we don't end up with a 500 MB chunk if user asks for 2.
        val effective = ((totalBytes + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE).toInt().coerceAtLeast(n)
        val segments = max(n, effective)

        val base = totalBytes / segments
        val extra = (totalBytes % segments).toInt()
        val out = ArrayList<Spec>(segments)
        var cursor = 0L
        for (i in 0 until segments) {
            val size = base + if (i < extra) 1 else 0
            val end = cursor + size - 1
            out += Spec(i, cursor, end)
            cursor += size
        }
        return out
    }

    /**
     * Reconciles planned specs against already-persisted chunk progress. Chunks that
     * are already complete are dropped from the work list; partially-downloaded chunks
     * are returned with their start byte shifted forward.
     */
    fun reconcile(specs: List<Spec>, completed: Set<Int>, partial: Map<Int, Long>): List<Spec> {
        if (completed.isEmpty() && partial.isEmpty()) return specs
        return specs.mapNotNull { spec ->
            when {
                completed.contains(spec.index) -> null
                partial.containsKey(spec.index) -> {
                    val skipped = partial.getValue(spec.index)
                    if (skipped >= spec.length) null
                    else spec.copy(startByte = spec.startByte + skipped)
                }
                else -> spec
            }
        }
    }

    const val MAX_CHUNK_SIZE: Long = 64L * 1024L * 1024L

    /**
     * Adaptive segment count. Small files get fewer segments (chunk overhead
     * dominates), large files get more (parallel TCP streams dominate), and
     * the user setting acts as a per-download cap.
     *
     * Heuristic (roughly):
     *   <8 MB    → 1 segment    (segment overhead > pipelining win)
     *   8-64 MB  → 2-4 segments
     *   64-256 MB → 4-8 segments
     *   >256 MB  → 8-16 segments
     *
     * The returned value may exceed [userSegments] but never
     * [maxChunksFor(totalBytes)] — for a 1 GB file, both are 16.
     */
    fun adaptiveSegments(totalBytes: Long, userSegments: Int): Int {
        if (totalBytes <= 0) return 1
        val max = maxChunksFor(totalBytes)
        val heuristic = when {
            totalBytes < 8L * 1024 * 1024 -> 1
            totalBytes < 64L * 1024 * 1024 -> 4
            totalBytes < 256L * 1024 * 1024 -> 8
            else -> 16
        }
        // Never exceed the user's requested cap, never exceed the planner cap,
        // and never go below 1.
        return heuristic.coerceIn(1, maxOf(userSegments, max).coerceAtMost(max))
    }

    fun maxChunksFor(totalBytes: Long): Int =
        min(16, max(1, ((totalBytes + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE).toInt()))
}

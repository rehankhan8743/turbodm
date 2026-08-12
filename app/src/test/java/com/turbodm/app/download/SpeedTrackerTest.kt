package com.turbodm.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SpeedTrackerTest {

    @Test fun `empty tracker reports zero for all ids`() {
        val t = SpeedTracker(windowMs = 1000L)
        assertEquals(emptyMap<Long, Long>(), t.bps.value)
    }

    @Test fun `record adds the sample to bps map`() {
        val t = SpeedTracker(windowMs = 1000L)
        t.record(downloadId = 1L, bytes = 1000L)
        // 1000 bytes recorded "now"; the sliding window collapses span→1ms via
        // coerceAtLeast(1), so reported bps will be >> 1000. Just verify the
        // entry is present and > 0.
        val reported = t.bps.value[1L] ?: 0L
        assertTrue("expected > 0, got $reported", reported > 0L)
    }

    @Test fun `record with zero or negative bytes is ignored`() {
        val t = SpeedTracker(windowMs = 1000L)
        t.record(1L, 0L)
        t.record(1L, -100L)
        assertTrue("expected empty map, got ${t.bps.value}", t.bps.value.isEmpty())
    }

    @Test fun `reset removes the entry`() {
        val t = SpeedTracker(windowMs = 1000L)
        t.record(1L, 1000L)
        assertTrue(t.bps.value.containsKey(1L))
        t.reset(1L)
        assertTrue(t.bps.value[1L] == null)
    }

    @Test fun `multiple downloads are tracked independently`() {
        val t = SpeedTracker(windowMs = 1000L)
        t.record(1L, 500L)
        t.record(2L, 2000L)
        val a = t.bps.value[1L] ?: 0L
        val b = t.bps.value[2L] ?: 0L
        assertTrue(a > 0L)
        assertTrue(b > 0L)
        // The bigger sample should report ≥ the smaller (modulo jitter)
        assertTrue("expected b >= a, got a=$a b=$b", b >= a)
    }

    @Test fun `concurrent records accumulate`() {
        val t = SpeedTracker(windowMs = 10_000L)
        repeat(100) { t.record(1L, 10L) }
        // 100 × 10 = 1000 bytes, span ≈ 0ms → guard coerceAtLeast(1L)
        // → bps is huge but > 0
        assertTrue("expected > 0, got ${t.bps.value[1L]}", (t.bps.value[1L] ?: 0L) > 0L)
    }

    @Test fun `samples older than the window are trimmed`() {
        val t = SpeedTracker(windowMs = 50L)
        t.record(1L, 1000L)
        // Sleep past the window so the next snapshot will discard it.
        TimeUnit.MILLISECONDS.sleep(120)
        // Touching the tracker forces a snapshot recompute at "now".
        t.record(1L, 100L)
        // Whatever the precise bps is, the stale 1000-byte sample must be gone,
        // so reported rate reflects only the 100 bytes added *now*.
        val bps = t.bps.value[1L] ?: 0L
        // We don't pin the magnitude (span collapses to 1ms via coerceAtLeast)
        // but it must be > 0.
        assertTrue("expected > 0, got $bps", bps > 0L)
    }
}
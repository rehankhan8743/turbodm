package com.turbodm.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {

    @Test fun `plan with one segment returns a single chunk covering the whole file`() {
        val specs = ChunkPlanner.plan(totalBytes = 1024, count = 1)
        assertEquals(1, specs.size)
        assertEquals(ChunkPlanner.Spec(0, 0, 1023), specs[0])
        assertEquals(1024L, specs[0].length)
    }

    @Test fun `plan with three equal segments divides without remainder`() {
        val specs = ChunkPlanner.plan(totalBytes = 300, count = 3)
        assertEquals(3, specs.size)
        // 100 / 100 / 100
        assertEquals(ChunkPlanner.Spec(0, 0, 99), specs[0])
        assertEquals(ChunkPlanner.Spec(1, 100, 199), specs[1])
        assertEquals(ChunkPlanner.Spec(2, 200, 299), specs[2])
        // contiguous
        for (i in 1 until specs.size) {
            assertEquals(specs[i - 1].endByte + 1, specs[i].startByte)
        }
    }

    @Test fun `plan with remainder distributes extra bytes across the first chunks`() {
        // 10 bytes into 3 chunks → 4,3,3 (base=3, extra=1)
        val specs = ChunkPlanner.plan(totalBytes = 10, count = 3)
        assertEquals(3, specs.size)
        assertEquals(ChunkPlanner.Spec(0, 0, 3), specs[0])
        assertEquals(ChunkPlanner.Spec(1, 4, 6), specs[1])
        assertEquals(ChunkPlanner.Spec(2, 7, 9), specs[2])
        val total = specs.sumOf { it.length }
        assertEquals(10L, total)
    }

    @Test fun `plan clamps count by MAX_CHUNK_SIZE for very small chunk requests`() {
        // 256 MB with requested 2 segments → 64 MB × 4 (since 64 MB cap)
        val total = 256L * 1024L * 1024L
        val specs = ChunkPlanner.plan(totalBytes = total, count = 2)
        assertTrue("expected at least 4 segments, got ${specs.size}", specs.size >= 4)
        // each chunk ≤ 64 MB
        for (s in specs) {
            assertTrue("chunk too big: ${s.length}", s.length <= ChunkPlanner.MAX_CHUNK_SIZE)
        }
    }

    @Test fun `plan with count=0 falls back to one segment`() {
        val specs = ChunkPlanner.plan(totalBytes = 100, count = 0)
        assertEquals(1, specs.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plan with zero bytes throws`() {
        ChunkPlanner.plan(totalBytes = 0, count = 2)
    }

    @Test fun `maxChunksFor caps at 16`() {
        // 16 GB would normally need 256 chunks but we cap at 16
        val max = ChunkPlanner.maxChunksFor(16L * 1024L * 1024L * 1024L)
        assertEquals(16, max)
    }

    @Test fun `maxChunksFor at least one even for tiny files`() {
        assertEquals(1, ChunkPlanner.maxChunksFor(1))
    }

    // ----- reconcile -----

    @Test fun `reconcile with no completion returns specs unchanged`() {
        val specs = ChunkPlanner.plan(100, 2)
        val out = ChunkPlanner.reconcile(specs, completed = emptySet(), partial = emptyMap())
        assertEquals(specs, out)
    }

    @Test fun `reconcile drops completed chunks`() {
        val specs = ChunkPlanner.plan(100, 3) // indices 0,1,2
        val out = ChunkPlanner.reconcile(specs, completed = setOf(1), partial = emptyMap())
        assertEquals(listOf(specs[0], specs[2]), out)
    }

    @Test fun `reconcile shifts partial chunks forward`() {
        val specs = ChunkPlanner.plan(100, 2) // [0..49], [50..99]
        val out = ChunkPlanner.reconcile(specs, completed = emptySet(), partial = mapOf(0 to 30L))
        // chunk 0: now starts at 30, still ends at 49
        assertEquals(ChunkPlanner.Spec(0, 30, 49), out[0])
        assertEquals(specs[1], out[1])
    }

    @Test fun `reconcile drops a partial whose progress already covered the chunk`() {
        val specs = ChunkPlanner.plan(100, 2)
        // chunk 0 is 50 bytes; pretending 50 already downloaded means it's done.
        val out = ChunkPlanner.reconcile(specs, completed = emptySet(), partial = mapOf(0 to 50L))
        assertEquals(listOf(specs[1]), out)
    }

    @Test fun `reconcile handles mix of completed and partial`() {
        val specs = ChunkPlanner.plan(300, 3)
        val out = ChunkPlanner.reconcile(
            specs,
            completed = setOf(2),
            partial = mapOf(0 to 10L) // skip 10 bytes into chunk 0
        )
        assertEquals(2, out.size)
        assertEquals(ChunkPlanner.Spec(0, 10, 99), out[0])
        assertEquals(specs[1], out[1])
    }

    @Test fun `reconcile shifted chunk covers exactly the unpersisted tail`() {
        // Regression for the DownloadEngine double-skip bug:
        // reconcile() already shifts startByte forward by persisted bytes.
        // Adding persisted bytes a second time would skip past endByte and
        // produce a truncated file. The following asserts the shifted chunk
        // covers exactly the bytes NOT yet written to disk.
        val specs = ChunkPlanner.plan(1000, 4) // 250 bytes each
        val persisted = 120L // bytes already on disk
        val out = ChunkPlanner.reconcile(specs, completed = emptySet(), partial = mapOf(0 to persisted))
        val shifted = out[0]
        // startByte has been advanced by the persisted bytes.
        assertEquals(0L + persisted, shifted.startByte)
        // Length of the shifted chunk must be exactly the remaining bytes.
        val expected = specs[0].length - persisted
        assertEquals(expected, shifted.length)
        // And the end must still be the chunk's original end — reconcile does
        // not extend the chunk, it trims the start.
        assertEquals(specs[0].endByte, shifted.endByte)
    }
}
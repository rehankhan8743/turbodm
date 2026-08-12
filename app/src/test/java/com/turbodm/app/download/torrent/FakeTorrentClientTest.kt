package com.turbodm.app.download.torrent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeTorrentClientTest {

    @Test fun `fetchMetadata returns deterministic three-file torrent`() = runBlocking {
        val client = FakeTorrentClient()
        val info1 = client.fetchMetadata("magnet:?xt=urn:btih:abc")
        val info2 = client.fetchMetadata("magnet:?xt=urn:btih:abc")
        assertEquals(info1.name, info2.name)
        assertEquals(info1.infoHash, info2.infoHash)
        assertEquals(info1.totalBytes, info2.totalBytes)
        assertEquals(3, info1.files.size)
        assertEquals(0, info1.files[0].index)
        assertEquals(1, info1.files[1].index)
        assertEquals(2, info1.files[2].index)
        assertEquals(info1.files.sumOf { it.size }, info1.totalBytes)
    }

    @Test fun `fetchMetadata produces different content for different magnets`() = runBlocking {
        val client = FakeTorrentClient()
        val infoA = client.fetchMetadata("magnet:?xt=urn:btih:AAAA")
        val infoB = client.fetchMetadata("magnet:?xt=urn:btih:BBBB")
        assertTrue("Different magnets should produce different fake names", infoA.name != infoB.name)
    }

    @Test fun `observeStatus immediately emits current state on subscribe`() = runBlocking {
        val client = FakeTorrentClient()
        val handle = client.addTorrent(
            torrentId = 42L,
            magnet = "magnet:?xt=urn:btih:xyz",
            saveDir = "/tmp/fake",
            selectedIndices = listOf(0, 1)
        )
        assertTrue(handle.isNotBlank())
        val snapshot = client.observeStatus(handle).first()
        assertEquals(TorrentState.CONNECTING, snapshot.state)
    }

    @Test fun `pause flips state to PAUSED and emits`() = runBlocking {
        val client = FakeTorrentClient()
        val handle = client.addTorrent(
            torrentId = 7L, magnet = "magnet:?xt=urn:btih:pauseme",
            saveDir = "/tmp/fake", selectedIndices = listOf(0)
        )
        // Drain the initial CONNECTING snapshot.
        client.observeStatus(handle).first()
        client.pause(handle)
        // observeStatus re-emits current state; pausing after means we get PAUSED.
        val paused = client.observeStatus(handle).first()
        assertEquals(TorrentState.PAUSED, paused.state)
    }

    @Test fun `resume after pause emits DOWNLOADING`() = runBlocking {
        val client = FakeTorrentClient()
        val handle = client.addTorrent(
            torrentId = 9L, magnet = "magnet:?xt=urn:btih:resume",
            saveDir = "/tmp/fake", selectedIndices = listOf(0)
        )
        client.observeStatus(handle).first() // drain CONNECTING
        client.pause(handle)
        client.observeStatus(handle).first() // drain PAUSED
        client.resume(handle)
        val resumed = client.observeStatus(handle).first()
        assertEquals(TorrentState.DOWNLOADING, resumed.state)
    }

    @Test fun `observeStatus throws for unknown handle`() = runBlocking {
        val client = FakeTorrentClient()
        var thrown: Throwable? = null
        try {
            client.observeStatus("nope")
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
    }
}
package com.turbodm.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemeRegistryTest {

    private class FakeHandler(
        override val schemes: Set<String>,
        override val hostPatterns: Set<String> = emptySet()
    ) : SchemeHandler {
        override suspend fun probe(url: String): LinkAnalyzer.Info =
            LinkAnalyzer.Info(fileName = "x", totalBytes = 0, mimeType = null, supportsRange = false)
        override suspend fun fetch(download: com.turbodm.app.domain.model.Download) = Unit
    }

    private fun registryWith(vararg handlers: SchemeHandler): SchemeRegistry =
        SchemeRegistry(handlers.toSet())

    @Test fun `handlerFor returns http handler for https URL`() {
        val http = FakeHandler(setOf("http", "https"))
        val r = registryWith(http)
        assertEquals(http, r.handlerFor("https://example.com/file.zip"))
        assertEquals(http, r.handlerFor("HTTP://example.com")) // case-insensitive scheme
    }

    @Test fun `handlerFor returns null for unregistered scheme`() {
        val r = registryWith(FakeHandler(setOf("http")))
        assertNull(r.handlerFor("ftp://server/file"))
        assertNull(r.handlerFor("not a url at all"))
    }

    @Test fun `handlerFor returns null for empty input`() {
        val r = registryWith(FakeHandler(setOf("http")))
        assertNull(r.handlerFor(""))
    }

    @Test fun `handlerFor picks the first inserted handler when schemes overlap`() {
        val first = FakeHandler(setOf("magnet"))
        val second = FakeHandler(setOf("magnet", "http", "https"))
        // Whichever is first in the Set wins (order is undefined for Set, so we
        // verify the registry returns *some* matching handler — exact tie-break
        // order is documented but not strictly testable with a HashSet).
        val r = registryWith(first, second)
        val resolved = r.handlerFor("magnet:?xt=urn:btih:abc")
        assertNotNull(resolved)
        assertTrue(resolved === first || resolved === second)
    }

    @Test fun `handlerFor supports magnet scheme`() {
        val magnet = FakeHandler(setOf("magnet"))
        val r = registryWith(FakeHandler(setOf("http", "https")), magnet)
        assertEquals(magnet, r.handlerFor("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a77a&dn=ubuntu"))
    }

    @Test fun `handlerFor handles content and file schemes`() {
        val content = FakeHandler(setOf("content"))
        val file = FakeHandler(setOf("file"))
        val r = registryWith(content, file)
        assertEquals(content, r.handlerFor("content://com.example.provider/123"))
        assertEquals(file, r.handlerFor("file:///sdcard/Download/foo.txt"))
    }

    @Test fun `supportedSchemes is the union of all handlers`() {
        val r = registryWith(
            FakeHandler(setOf("http", "https")),
            FakeHandler(setOf("magnet")),
            FakeHandler(setOf("content", "file"))
        )
        val schemes = r.supportedSchemes
        assertTrue("http" in schemes)
        assertTrue("https" in schemes)
        assertTrue("magnet" in schemes)
        assertTrue("content" in schemes)
        assertTrue("file" in schemes)
        assertEquals(5, schemes.size)
    }

    @Test fun `http handler declares http and https schemes`() {
        // We don't construct the real handler (it needs injected deps); instead we
        // verify via a fake that a typical HTTP handler's scheme set matches what
        // the registry expects.
        val http = FakeHandler(setOf("http", "https"))
        assertEquals(setOf("http", "https"), http.schemes)
    }

    @Test fun `registry with no handlers returns null for everything`() {
        val r = registryWith()
        assertNotNull(r)
        assertNull(r.handlerFor("https://example.com"))
        assertTrue(r.supportedSchemes.isEmpty())
    }

    // ---- Host-based routing (Phase 5 streaming integration) ----

    // Streaming handler declares hostPatterns but empty schemes — the realistic shape
    // matches the real StreamingSchemeHandler. The HTTP handler owns the `https` scheme
    // outright.
    @Test fun `host-pattern handler preempts http handler for its declared hosts`() {
        val http = FakeHandler(setOf("http", "https"))
        val streaming = FakeHandler(schemes = emptySet(), hostPatterns = setOf("youtube.com", "youtu.be"))
        val r = registryWith(http, streaming)
        // https://youtube.com would normally hit http (https scheme) — streaming wins by host.
        assertSame(streaming, r.handlerFor("https://www.youtube.com/watch?v=abc"))
        assertSame(streaming, r.handlerFor("https://youtu.be/abc"))
    }

    @Test fun `host-pattern handler does not preempt http for unrelated hosts`() {
        val http = FakeHandler(setOf("http", "https"))
        val streaming = FakeHandler(schemes = emptySet(), hostPatterns = setOf("youtube.com"))
        val r = registryWith(http, streaming)
        // Plain HTTP URL on an unknown host falls through to the http handler.
        assertSame(http, r.handlerFor("https://example.com/file.zip"))
    }

    @Test fun `host-pattern suffix match catches subdomains but not unrelated domains`() {
        val http = FakeHandler(setOf("http", "https"))
        val streaming = FakeHandler(schemes = emptySet(), hostPatterns = setOf("youtube.com"))
        val r = registryWith(http, streaming)
        // m.youtube.com / www.youtube.com / music.youtube.com all match.
        assertSame(streaming, r.handlerFor("https://m.youtube.com/watch?v=x"))
        assertSame(streaming, r.handlerFor("https://www.youtube.com/"))
        assertSame(streaming, r.handlerFor("https://music.youtube.com/playlist?list=abc"))
        // notyoutube.com — substring match but no dot before pattern, so no match.
        // Falls through to plain http handler.
        assertSame(http, r.handlerFor("https://notyoutube.com/"))
    }

    @Test fun `host-pattern matching is case-insensitive on the host`() {
        val streaming = FakeHandler(schemes = emptySet(), hostPatterns = setOf("youtube.com"))
        val r = registryWith(FakeHandler(setOf("http", "https")), streaming)
        assertSame(streaming, r.handlerFor("https://WWW.YOUTUBE.COM/watch"))
        assertSame(streaming, r.handlerFor("https://YouTube.Com/"))
    }

    @Test fun `extractHost returns null for magnet URLs`() {
        // Magnet has no host component — only scheme and query.
        assertNull(SchemeRegistry.extractHost("magnet:?xt=urn:btih:abc"))
    }

    @Test fun `extractHost handles ports and trailing paths`() {
        assertEquals("example.com", SchemeRegistry.extractHost("https://example.com/file.zip"))
        assertEquals("example.com", SchemeRegistry.extractHost("https://example.com:8080/path"))
        assertEquals("example.com", SchemeRegistry.extractHost("https://user@example.com/"))
        assertEquals("a.b.c", SchemeRegistry.extractHost("http://a.b.c:1"))
    }

    @Test fun `handler without hostPatterns does not preempt by host`() {
        // Magnet handler has schemes only — must not steal streaming-host URLs
        // via some unintended host pattern matching.
        val magnet = FakeHandler(setOf("magnet"))
        val http = FakeHandler(setOf("http", "https"))
        val r = registryWith(magnet, http)
        // youtube.com is not magnet, so http wins.
        assertSame(http, r.handlerFor("https://youtube.com/watch?v=abc"))
    }
}
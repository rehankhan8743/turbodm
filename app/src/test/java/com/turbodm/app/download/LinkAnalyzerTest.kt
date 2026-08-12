package com.turbodm.app.download

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkAnalyzerTest {

    private lateinit var server: MockWebServer
    private lateinit var analyzer: LinkAnalyzer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        analyzer = LinkAnalyzer(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    private fun urlOf(path: String) = server.url(path).toString()

    @Test fun `successful HEAD reports size and range support`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "12345")
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Type", "application/zip")
                .setHeader("Content-Disposition", "filename=\"hello.zip\"")
        )
        val info = analyzer.analyze(urlOf("/download.zip"))
        assertEquals(12345L, info.totalBytes)
        assertTrue(info.supportsRange)
        assertEquals("application/zip", info.mimeType)
        assertEquals("hello.zip", info.fileName)
    }

    @Test fun `HEAD returning 405 falls back to range GET`() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/9876")
                .setHeader("Content-Type", "application/octet-stream")
        )
        val info = analyzer.analyze(urlOf("/thing"))
        assertEquals(9876L, info.totalBytes)
        assertTrue(info.supportsRange)
    }

    @Test fun `filename falls back to URL path when no Content-Disposition`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "10")
        )
        val info = analyzer.analyze(urlOf("/path/some-file.bin"))
        assertEquals("some-file.bin", info.fileName)
    }

    @Test fun `filename sanitizes illegal path characters`() {
        // Stripped query string, sanitized name.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "10"))
        val info = analyzer.analyze(urlOf("/path/evil|name?.bin"))
        // '?' is dropped because of substringBefore('?'); '|' replaced with '_'.
        assertFalse("filename should not contain '|': ${info.fileName}", info.fileName.contains('|'))
    }

    @Test fun `defaultName is used when URL yields nothing`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "10"))
        val info = analyzer.analyze(urlOf("/"), defaultName = "myfile.dat")
        assertEquals("myfile.dat", info.fileName)
    }

    @Test(expected = IllegalStateException::class)
    fun `non-recoverable HTTP error throws`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        analyzer.analyze(urlOf("/x"))
    }
}
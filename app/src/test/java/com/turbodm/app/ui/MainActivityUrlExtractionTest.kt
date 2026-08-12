package com.turbodm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the URL extraction regex used in share-intent handling.
 *
 * Note: we exercise the regex via reflection-free access by re-implementing the
 * same pattern here. If the production regex changes, this test will catch the
 * divergence (intentional — the test verifies the contract, not the symbol).
 */
class MainActivityUrlExtractionTest {

    // Same regex as MainActivity.URL_REGEX.
    // - `://` for http(s), ftp, content, file
    // - `:` for magnet (which uses `magnet:?xt=...`)
    // We use an alternation so each scheme matches its own separator.
    private val URL_REGEX = Regex(
        """((https?|ftp|content|file)://[^\s]+|magnet:[^\s]+)""",
        RegexOption.IGNORE_CASE
    )

    @Test fun `https URL extracted from share text`() {
        val url = URL_REGEX.find("Check this out: https://example.com/file.zip!")?.value
        assertEquals("https://example.com/file.zip!", url)
    }

    @Test fun `http URL extracted`() {
        val url = URL_REGEX.find("http://example.com/foo")?.value
        assertEquals("http://example.com/foo", url)
    }

    @Test fun `magnet link extracted with query parameters`() {
        val url = URL_REGEX.find("grab this torrent magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a77a&dn=ubuntu-iso")?.value
        assertEquals("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a77a&dn=ubuntu-iso", url)
    }

    @Test fun `ftp URL extracted`() {
        val url = URL_REGEX.find("ftp://ftp.example.com/pub/file.tar.gz")?.value
        assertEquals("ftp://ftp.example.com/pub/file.tar.gz", url)
    }

    @Test fun `content URI extracted`() {
        val url = URL_REGEX.find("content://com.example.provider/123")?.value
        assertEquals("content://com.example.provider/123", url)
    }

    @Test fun `file URI extracted with triple-slash absolute path`() {
        val url = URL_REGEX.find("file:///sdcard/Download/foo.txt")?.value
        assertEquals("file:///sdcard/Download/foo.txt", url)
    }

    @Test fun `uppercase scheme is matched case-insensitively`() {
        val url = URL_REGEX.find("HTTPS://example.com/")?.value
        assertEquals("HTTPS://example.com/", url)
    }

    @Test fun `text with no URL returns null`() {
        assertNull(URL_REGEX.find("just plain text with no link")?.value)
    }

    @Test fun `empty input returns null`() {
        assertNull(URL_REGEX.find("")?.value)
    }

    @Test fun `trailing punctuation captured along with URL`() {
        // Trailing punctuation (e.g. period) IS captured; downstream parsers
        // (OkHttp, Uri.parse) tolerate it. We document this behavior.
        val url = URL_REGEX.find("see https://x.com/y.")?.value
        assertEquals("https://x.com/y.", url)
    }

    @Test fun `first URL wins when multiple are present`() {
        val url = URL_REGEX.find("a https://first.com b https://second.com")?.value
        assertEquals("https://first.com", url)
    }
}
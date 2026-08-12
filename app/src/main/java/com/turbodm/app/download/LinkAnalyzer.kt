package com.turbodm.app.download

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes a URL with HEAD (falling back to a 1-byte range GET) to learn its
 * size, content type, and whether the server supports range requests.
 */
@Singleton
class LinkAnalyzer @Inject constructor(
    private val client: OkHttpClient
) {
    data class Info(
        val fileName: String,
        val totalBytes: Long,
        val mimeType: String?,
        val supportsRange: Boolean
    )

    fun analyze(url: String, defaultName: String? = null): Info {
        val req = Request.Builder().url(url).head().build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                val total = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val accepts = resp.header("Accept-Ranges")?.equals("bytes", true) == true ||
                    resp.header("Content-Range") != null
                val mime = resp.body?.contentType()?.toString()
                val name = guessName(url, resp.header("Content-Disposition"), defaultName)
                return Info(name, total, mime, accepts)
            }
            if (resp.code == 405 || resp.code == 501) return analyzeWithRange(url, defaultName)
            error("HTTP ${resp.code}")
        }
    }

    private fun analyzeWithRange(url: String, defaultName: String?): Info {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { resp ->
            val mime = resp.body?.contentType()?.toString()
            val cr = resp.header("Content-Range")
            val total = cr?.substringAfter('/')?.toLongOrNull() ?: -1L
            val supports = resp.code == 206 || cr != null
            val name = guessName(url, resp.header("Content-Disposition"), defaultName)
            return Info(name, total, mime, supports)
        }
    }

    private fun guessName(url: String, disposition: String?, default: String?): String {
        if (!disposition.isNullOrBlank()) {
            val m = Regex("""filename\*?=(?:UTF-8''|")?([^";]+)""").find(disposition)
            if (m != null) {
                // Percentages in Content-Disposition filenames are URL-encoded;
                // decode them so "video%20clip.mp4" saves as "video clip.mp4".
                val decoded = runCatching { URLDecoder.decode(m.groupValues[1].trim(), Charsets.UTF_8) }
                    .getOrElse { m.groupValues[1].trim() }
                return sanitize(decoded)
            }
        }
        if (!default.isNullOrBlank()) return sanitize(default)
        val fromUrl = url.substringBefore('?').substringAfterLast('/')
        // Decode %-encoded URL path so "%20" in a direct link becomes a space
        // in the saved filename rather than staying encoded.
        val decoded = runCatching { URLDecoder.decode(fromUrl, Charsets.UTF_8) }
            .getOrElse { fromUrl }
        return sanitize(if (decoded.isBlank()) "download.bin" else decoded)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(200)
}

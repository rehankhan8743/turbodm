package com.turbodm.app.download

import okhttp3.OkHttpClient
import okhttp3.Request
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
            if (m != null) return m.groupValues[1].trim()
        }
        if (!default.isNullOrBlank()) return sanitize(default)
        val fromUrl = url.substringBefore('?').substringAfterLast('/')
        return sanitize(if (fromUrl.isBlank()) "download.bin" else fromUrl)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(200)
}

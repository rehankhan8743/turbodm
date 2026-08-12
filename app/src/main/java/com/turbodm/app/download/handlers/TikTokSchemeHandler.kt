package com.turbodm.app.download.handlers

import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.download.DownloadEngine
import com.turbodm.app.download.LinkAnalyzer
import com.turbodm.app.download.SchemeHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves TikTok URLs via the public aggregator at `tiksave.io/api/ajaxSearch`.
 * TikTok's own CDN blocks direct datacenter downloads with HTTP 403 (Akamai geo-fence),
 * which makes any in-app scraping of `downloadAddr` unreliable — the resolve step
 * succeeds but the actual fetch fails. The tiksave endpoint does the heavy lifting
 * server-side: we POST the share URL, get back an HTML fragment containing real
 * CDN URLs (no-watermark MP4, HD MP4, MP3), and pick the one that fits the caller.
 *
 * Tradeoffs:
 *   - This is a third-party service; availability/authorizer notice is theirs.
 *   - Returned `filename` carries author and desc if present, both sanitized.
 *   - The CDN token expiry time is short (1 hour at the time of writing).
 */
@Singleton
class TikTokSchemeHandler @Inject constructor(
    private val client: OkHttpClient,
    private val repo: DownloadRepository,
    private val engine: DownloadEngine
) : SchemeHandler {

    override val schemes: Set<String> = emptySet()

    override val hostPatterns: Set<String> = setOf(
        "tiktok.com",
        "vt.tiktok.com",
        "vm.tiktok.com",
        "www.tiktok.com",
        "m.tiktok.com"
    )

    override suspend fun probe(url: String): LinkAnalyzer.Info = withContext(Dispatchers.IO) {
        val resolved = resolveTikTok(url, preferAudio = false)
        LinkAnalyzer.Info(
            fileName = resolved.fileName,
            totalBytes = -1L,
            mimeType = "video/mp4",
            supportsRange = false // tiksave CDN URLs don't honor Range — single chunk
        )
    }

    override suspend fun fetch(download: Download) = withContext(Dispatchers.IO) {
        try {
            val resolved = resolveTikTok(download.url, preferAudio = download.preferAudioOnly)
            repo.updateUrl(download.id, resolved.resolvedUrl)
            repo.updateFileName(download.id, resolved.fileName)
            // Explicitly disable Range — tiksave-hosted URLs redirect through
            // dl.snapcdn.app and don't honor Range reliably.
            repo.updateSupportsRange(download.id, false)
            repo.setStatus(download.id, DownloadStatus.QUEUED)
        } catch (t: Throwable) {
            // Map common failure modes to actionable errors.
            val msg = when {
                t.message?.contains("geoblock", true) == true ||
                t.message?.contains("geo_block", true) == true ->
                    "TikTok says this content isn't available in your region. " +
                    "Try a different network (mobile data vs Wi-Fi), or pick another video."
                t.message?.contains("photo post", true) == true ->
                    "This looks like a TikTok photo post, not a video. " +
                    "We don't support photo downloads yet — try a video instead."
                else ->
                    t.message ?: "TikTok resolution failed — the video may be private, removed, or geo-blocked."
            }
            repo.setError(download.id, msg, DownloadStatus.FAILED)
        }
    }

    private data class Resolved(val fileName: String, val resolvedUrl: String)

    private fun resolveTikTok(url: String, preferAudio: Boolean): Resolved {
        // The aggregator accepts any TikTok URL form — short, sluggable, or canonical.
        val body: okhttp3.RequestBody = FormBody.Builder()
            .add("q", url)
            .add("lang", "en")
            .build()
        val req = Request.Builder()
            .url("https://tiksave.io/api/ajaxSearch")
            .post(body)
            .header("User-Agent", TIKTOK_UA)
            .header("Origin", "https://tiksave.io")
            .header("Referer", "https://tiksave.io/")
            .build()
        val raw = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("tiksave returned HTTP ${resp.code}")
            resp.body?.string() ?: error("Empty tiksave response")
        }
        val json = JSONObject(raw)
        if (json.optString("status") != "ok") {
            error("tiksave rejected the URL: ${json.optString("message", "unknown")}")
        }
        // The HTML blob embeds the title (a <h3>) and several download URLs —
        // pull them out with regexes. The order is deterministic in the
        // current blob layout: thumbnail, title, then 4 <a href> elements
        // Download MP4 [1] / [2] / HD / MP3.
        val html = json.getString("data")
        val title = H3_REGEX.find(html)?.groupValues?.get(1)
            ?.let { htmlDecodeEntities(it) }?.trim() ?: "TikTok"
        // Default: standard-definition no-watermark mp4 first, then HD variant.
        // MP3 link is the actual audio-only path.
        val mp3Url = MP3_REGEX.find(html)?.groupValues?.get(1)
        val mp4Sd = MP4_REGEX.find(html)?.groupValues?.get(1)
        val mp4Hd = MP4_HD_REGEX.find(html)?.groupValues?.get(1)
        val finalUrl = if (preferAudio) {
            mp3Url ?: error("No audio-only stream for this TikTok")
        } else {
            mp4Hd ?: mp4Sd
            ?: error("No downloadable MP4 found — TikTok may have restricted this video.")
        }
        // Pull the numeric ID for a stable fallback filename.
        val id = TIKTOK_ID_REGEX.find(html)?.groupValues?.get(1) ?: "tt"
        // Trim the title to ~80 chars so the final filename has room for
        // the extension and never exceeds Android's 255-byte limit on a
        // filename path component once the extension is appended.
        val baseName = sanitize("$title - $id", maxLen = 80)
        val fileName = "$baseName.${if (preferAudio) "mp3" else "mp4"}"
        return Resolved(fileName = fileName, resolvedUrl = finalUrl.replace("&amp;", "&"))
    }

    /**
     * Convert HTML entities (`&#x1F44C;`, `&amp;`, `&lt;`, …) to Unicode.
     * TikTok titles embedded in HTML come entity-escaped; without this the
     * downloaded file ends up named like `Sona&amp;x1F615;...mp4`.
     */
    private fun htmlDecodeEntities(s: String): String =
        s.replace(Regex("&#x([0-9A-Fa-f]+);")) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            String(Character.toChars(code))
        }.replace(Regex("&#(\\d+);")) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            String(Character.toChars(code))
        }.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#x27;", "'")

    private fun sanitize(name: String, maxLen: Int = 80): String = name
        .replace(Regex("[#]"), "")               // hashtags — filesystem fine
        .replace(Regex("[\\p{Cntrl}]"), " ")     // strip newlines/tabs
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")  // hostile filesystem chars
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxLen)
        .trim()                                   // re-trim after take — can cut mid-space
        .ifBlank { "tiktokvideo" }

    companion object {
        // Modern mobile-web UA — tiksave keys off the UA to decide whether to
        // redirect to a consent page.
        private const val TIKTOK_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

        // Title: <h3>…</h3>
        private val H3_REGEX = Regex("<h3>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL)

        // Numeric id for stable filenames
        private val TIKTOK_ID_REGEX = Regex("id=\"TikTokId\"\\s+value=\"(\\d+)\"")

        // Each "Download MP4" link: <a ... href="https://dl.snapcdn.app/get?token=..." ...>
        //   or straight to v16m.tiktokcdn-us.com/...
        // We look for the two direct-mp4 href variants and the mp3 href.
        private val MP4_REGEX = Regex(
            """href="([^"]+)"[^>]*>\s*<i[^>]*class="icon icon-download"[^>]*></i>\s*Download MP4 \[1\]"""
        )
        private val MP4_HD_REGEX = Regex(
            """href="([^"]+)"[^>]*>\s*<i[^>]*class="icon icon-download"[^>]*></i>\s*Download MP4 HD"""
        )
        private val MP3_REGEX = Regex(
            """href="([^"]+)"[^>]*>\s*<i[^>]*class="icon icon-download"[^>]*></i>\s*Download MP3"""
        )
    }
}

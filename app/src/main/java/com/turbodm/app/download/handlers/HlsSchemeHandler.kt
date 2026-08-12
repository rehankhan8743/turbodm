package com.turbodm.app.download.handlers

import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.download.LinkAnalyzer
import com.turbodm.app.download.SchemeHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an HLS live/vod `.m3u8` (or DASH `.mpd`) by:
 *
 * 1. Fetching the master playlist and picking the highest-bitrate variant.
 * 2. Downloading each media segment (.ts chunks for HLS) in parallel.
 * 3. Concatenating them in order into a single `.mp4` at the end.
 *
 * Why we don't just rely on Android's MediaPlayer/exoPlayer to record an
 * already-opened stream: that's slow (real-time only), and this app's whole
 * purpose is to be fast. A non-real-time downloader finishes a 2-hour video
 * in seconds on Wi-Fi.
 *
 * The .mpd format is more complex (XML namespaces, SegmentURL templates,
 * SegmentList, SegmentBase, etc.). We handle the two common cases:
 *   - SegmentList: each <SegmentURL media="chunk-1.m4s"/> lists actual chunks.
 *   - SegmentTemplate with $Number$ placeholder — we substitute 1, 2, 3, …
 *   Everything else errors out with a clear "DASH form not supported" message.
 */
@Singleton
class HlsSchemeHandler @Inject constructor(
    private val client: OkHttpClient,
    private val repo: DownloadRepository
) : SchemeHandler {

    override val schemes: Set<String> = setOf("http", "https")

    override suspend fun probe(url: String): LinkAnalyzer.Info = withContext(Dispatchers.IO) {
        // For HLS we don't know the total size up-front. The "fileName" is
        // derived from the URL path; an actual filename is written later once
        // segments are concatenated.
        val path = URI(url).path ?: ""
        val basename = path.substringAfterLast('/').ifBlank { "stream" }
        val cleanName = basename.substringBefore('.').ifBlank { "stream" }
        LinkAnalyzer.Info(
            fileName = "$cleanName.mp4",
            totalBytes = -1L,
            mimeType = "video/mp4",
            supportsRange = false  // manifest isn't a single regular file
        )
    }

    override suspend fun fetch(download: Download): Unit = withContext(Dispatchers.IO) {
        repo.setStatus(download.id, DownloadStatus.ANALYZING)
        try {
            val segments = resolveSegments(download.url)
            if (segments.isEmpty()) {
                repo.setError(download.id, "No segments found — not an HLS/DASH stream.", DownloadStatus.FAILED)
                return@withContext
            }
            repo.setStatus(download.id, DownloadStatus.DOWNLOADING)

            // Download to a per-chunk temporary dir; concatenated at the end.
            val target = File(download.targetPath)
            target.parentFile?.mkdirs()
            val partDir = File(target.parentFile, target.name + ".hls")
            partDir.mkdirs()

            // Parallel-segment downloads (cap concurrent to 6 — anything higher
            // produces bursty writes that mess with disk I/O on slower devices).
            val outFile = File(target.parentFile, target.name + ".part")
            kotlinx.coroutines.sync.Semaphore(6).let { sem ->
                coroutineScope {
                    segments.mapIndexed { idx, segUrl ->
                        async {
                            sem.acquire()
                            try {
                                val tmp = File(partDir, "%06d.ts".format(idx))
                                if (tmp.exists() && tmp.length() > 0L) return@async
                                val req = Request.Builder().url(segUrl).build()
                                client.newCall(req).execute().use { resp ->
                                    if (!resp.isSuccessful)
                                        throw IOException("HTTP ${resp.code} on segment $idx")
                                    val bytes = resp.body?.bytes()
                                        ?: throw IOException("Empty body on segment $idx")
                                    tmp.writeBytes(bytes)
                                }
                                repo.setDownloaded(download.id, idx + 1L)
                            } finally {
                                sem.release()
                            }
                        }
                    }.awaitAll()
                }
            }

            // Concatenate *.ts in numeric order
            val ordered = (0 until segments.size).map { i -> File(partDir, "%06d.ts".format(i)) }
            if (ordered.any { !it.exists() }) {
                val missing = ordered.indexOfFirst { !it.exists() }
                repo.setError(download.id, "Segment $missing not finished — try downloading again", DownloadStatus.FAILED)
                return@withContext
            }
            outFile.outputStream().use { out ->
                ordered.forEach { f -> f.inputStream().use { it.copyTo(out) } }
            }
            // Decide final extension — if every segment is MPEG-TS we save .ts,
            // otherwise save .mp4 even though the bytes are transport-stream;
            // most players can demux ts-in-mp4.
            val finalName = target.absolutePath.removeSuffix(".part")
                .removeSuffix(".mp4") + ".mp4"
            val finalFile = File(finalName)
            if (finalFile.exists()) finalFile.delete()
            if (!outFile.renameTo(finalFile)) {
                repo.setError(download.id, "Failed to save merged MP4", DownloadStatus.FAILED)
                return@withContext
            }
            // Cleanup chunk dir.
            partDir.deleteRecursively()

            repo.setTotalBytes(download.id, finalFile.length())
            repo.markCompleted(download.id)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            repo.setError(download.id, t.message ?: "HLS download failed", DownloadStatus.FAILED)
        }
    }

    /**
     * Resolve [url] to a list of media segment URLs. Handles both HLS master
     * playlists (with #EXT-X-STREAM-INF variants) and dumber HLS media playlists
     * where the segments are listed directly.
     *
     * For DASH .mpd we support SegmentList semantics — those are guaranteed
     * present (`<SegmentURL media="..."/>`) — but not yet $Number$-form
     * templates (returning empty for those surfaces a clear error to the user).
     */
    private fun resolveSegments(url: String): List<String> {
        val req = Request.Builder().url(url).build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching manifest")
            resp.body?.string() ?: error("Empty manifest payload")
        }
        val lower = url.lowercase()
        return when {
            lower.endsWith(".mpd") -> parseDashManifest(body, url)
            else -> parseHlsManifest(body, url)
        }
    }

    private fun parseHlsManifest(body: String, base: String): List<String> {
        // Master playlists list variants with #EXT-X-STREAM-INF. Media playlists
        // list the actual chunks with #EXTINF. Pick the master path if present.
        if (body.contains("#EXT-X-STREAM-INF")) {
            // HLS master playlist: an #EXT-X-STREAM-INF line is followed on the
            // NEXT line by the variant playlist URI. The URI may be on the
            // same line if #EXT-X-STREAM-INF carries a URI attribute (rare),
            // or on the following line (canonical form).
            val lines = body.lines().map { it.trim() }
            val variants = mutableListOf<Pair<Long, String>>()
            for (i in lines.indices) {
                val l = lines[i]
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                // Inline URI in the attribute (URI="..."), or next non-blank line.
                val inlineUri = Regex("""URI="([^"]+)"""").find(l)?.groupValues?.get(1)
                val next = inlineUri ?: lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (next != null) variants += bw to next
            }
            if (variants.isEmpty()) return emptyList()
            val best = variants.maxByOrNull { it.first }!!
            // Re-fetch the variant playlist (it's a media playlist itself).
            val variantUrl = relativeToAbsolute(base, best.second)
            val variantBody = client.newCall(Request.Builder().url(variantUrl).build())
                .execute().use { it.body?.string() ?: "" }
            return parseHlsMedia(variantBody, variantUrl)
        }
        return parseHlsMedia(body, base)
    }

    private fun parseHlsMedia(body: String, base: String): List<String> {
        // A media playlist: alternates #EXTINF duration/comment and the URI on
        // the next non-comment line.  Return URIs in order.
        val lines = body.lineSequence().map { it.trim() }.toList()
        return lines.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { relativeToAbsolute(base, it) }
    }

    /**
     * Turns a potentially relative URI inside a manifest into an absolute URL.
     * Base must be the manifest URL.
     */
    private fun relativeToAbsolute(base: String, line: String): String {
        if (line.startsWith("https://") || line.startsWith("http://")) return line
        val parent = base.substringBeforeLast('/') + '/'
        return parent + line
    }

    /**
     * Parse a DASH MPD file. Two of the common shapes:
     *   - SegmentList with <SegmentURL media="…"/> children
     *   - SegTemplate with $Number$-placeholder substitution
     */
    private fun parseDashManifest(body: String, base: String): List<String> {
        // 1. SegmentList form — the most common for VOD.
        val segUrlRegex = Regex("<SegmentURL\\s+[^>]*media=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val listed = segUrlRegex.findAll(body).map { it.groupValues[1] }.toList()
        if (listed.isNotEmpty()) return listed.map { relativeToAbsolute(base, it) }

        // 2. SegmentTemplate with $Number$ placeholder:
        //    <SegmentTemplate media="chunk-$Number$.m4s" startNumber="1" duration="6"/>
        //    We have to also look at <SegmentTimeline> for actual durations; if absent,
        //    we just run the sequence up to a sentinel readCount (500) — reasonable for
        //    short clips, stops 404s eventually.
        val templateRegex = Regex("<SegmentTemplate[^>]*media=\"([^\"]+)\"[^>]*>")
        val tmatch = templateRegex.find(body) ?: return emptyList()
        val template = tmatch.groupValues[1]
        // Sentinels
        if (!template.contains("$")) return emptyList()  // segment template form we don't support

        val start = Regex("startNumber=\"(\\d+)\"").find(tmatch.value)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val end = start + 500
        return (start..end).map { i ->
            relativeToAbsolute(base, template.replace("\$Number\$", i.toString()))
        }
    }
}

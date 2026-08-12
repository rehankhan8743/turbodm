package com.turbodm.app.download.handlers

import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.download.LinkAnalyzer
import com.turbodm.app.download.RulesEngine
import com.turbodm.app.download.SchemeHandler
import com.turbodm.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a streaming site URL (YouTube, SoundCloud, Bandcamp, etc.) to a
 * direct media URL via NewPipeExtractor, then writes a `Download` row with the
 * resolved URL and asks the HTTP handler to fetch it.
 *
 * The actual byte transfer still goes through OkHttp — this handler only
 * re-targets the URL. The "streaming" abstraction is just URL resolution.
 *
 * Routing: declared via [hostPatterns], not [schemes]. [SchemeRegistry] prefers
 * host-matched handlers over scheme-matched handlers, so this handler preempts
 * [HttpSchemeHandler] for known streaming hosts while leaving plain HTTP(S)
 * alone. See [SchemeHandler.hostPatterns] for the matching rule.
 *
 * Gated by the "streaming site support" feature flag in [SettingsRepository]:
 * when the flag is off, this handler refuses to probe and returns an error.
 *
 * Requires [org.schabi.newpipe.extractor.NewPipe.init] to have been called
 * once at app startup; otherwise `getServiceByUrl` throws.
 */
@Singleton
class StreamingSchemeHandler @Inject constructor(
    private val repo: DownloadRepository,
    private val settings: SettingsRepository,
    private val rulesEngine: RulesEngine
) : SchemeHandler {

    /**
     * We don't list the bare `https://` schemes here — those are routed to
     * [HttpSchemeHandler]. We register the URL patterns that should be
     * resolved through NewPipe instead of OkHttp'd directly. Matching is
     * suffix-based: `m.youtube.com` matches `youtube.com`.
     */
    override val schemes: Set<String> = emptySet()

    /**
     * Hosts that NewPipeExtractor knows how to resolve. Kept narrow on purpose —
     * a URL that doesn't match one of these still routes to the plain HTTP
     * handler, which is the right default for unknown hosts. Adding to this
     * list doesn't require touching the registry.
     */
    /**
     * Hosts NewPipe v0.26.4 *actually* knows how to extract streams from
     * (`services/.../Service.class` in the JAR):
     *   - youtube.com / youtu.be (incl. m.youtube.com)
     *   - soundcloud.com
     *   - bandcamp.com
     *   - peertube.tv (and other ActivityPub video instances)
     *   - media.ccc.de (Chaos Computer Club)
     *
     * TikTok / Instagram / Facebook / Twitter / Twitch / Vimeo are NOT in
     * this build of NewPipe — adding them here would just produce a
     * "no known service can handle this URL" error. They stay on the plain
     * HTTP handler path (which grabs whatever the page returns and is
     * usually wrong for social-media pages).
     */
    override val hostPatterns: Set<String> = setOf(
        "youtube.com",
        "youtu.be",
        "soundcloud.com",
        "bandcamp.com",
        "peertube.tv",
        "media.ccc.de"
    )

    /**
     * TikTok lives in its own handler (TikTokSchemeHandler) — NewPipe has no
     * TikTok service in v0.26.4 so we never see these URLs here. If a regex
     * change means they ever do land here, the resolver will throw a clean
     * "no known service" error rather than silently downloading HTML.
     */

    override suspend fun probe(url: String): LinkAnalyzer.Info = withContext(Dispatchers.IO) {
        if (!settings.flow.first().streamingEnabled) {
            error("Streaming site support is disabled in Settings")
        }
        // We don't know the user's audio-only preference at probe time — is
        // chosen IFF the caller chose audio. Default to video for probing
        // so headers show a usable fileName.
        val info = resolve(url, preferAudio = false)
        LinkAnalyzer.Info(
            fileName = info.fileName,
            totalBytes = -1L,
            mimeType = null,
            supportsRange = true
        )
    }

    override suspend fun fetch(download: Download) = withContext(Dispatchers.IO) {
        if (!settings.flow.first().streamingEnabled) {
            repo.setError(download.id, "Streaming site support is disabled", DownloadStatus.FAILED)
            return@withContext
        }
        val resolved = resolve(download.url, preferAudio = download.preferAudioOnly)
        // Swap the URL and re-target the file based on the real chosen stream's
        // extension. The row was inserted with a placeholder extension matching
        // the user's intent (audio → music/, video → videos/); now that we know
        // the real container the file may need to move (e.g. user picked
        // "audio-only" but the only audio stream is .webm — not .opus).
        val snap = settings.flow.first()
        val newPath = rulesEngine.resolveTargetPath(
            baseDir = snap.downloadDir,
            fileName = resolved.fileName,
            mimeType = null,
            enabled = snap.rulesEngineEnabled
        )
        repo.updateUrl(download.id, resolved.resolvedUrl)
        repo.updateFileName(download.id, resolved.fileName)
        repo.updateTargetPath(download.id, newPath)
        // Drop back to QUEUED so QueueManager picks it up via HttpSchemeHandler.
        repo.setStatus(download.id, DownloadStatus.QUEUED)
    }

    private fun resolve(url: String, preferAudio: Boolean): ResolvedStream {
        val service = NewPipe.getServiceByUrl(url)
            ?: error("This site isn't supported by TurboDM's extractor. " +
                "Open Settings → Experimental to see which hosts are handled, " +
                "or try a different URL. (URL: $url)")
        val extractor = service.getStreamExtractor(url)
        // fetchPage() can throw ContentNotAvailableException, GeographicRestrictionException,
        // AgeRestrictedContentException, etc. — the NewPipe exceptions all carry a
        // localized message; we propagate it directly so the user sees why.
        extractor.fetchPage()
        val stream = StreamInfo.getInfo(extractor)
        // Pick logic:
        //   preferAudio=true  → highest-bitrate audio stream (opus > m4a > aac).
        //   preferAudio=false → highest-resolution *muxed* video+audio so the
        //                        user gets a playable file even if it's slightly
        //                        lower res; fall back to pure video, then audio.
        val best = if (preferAudio) {
            // Prefer formats that Android's media stack AND every third-party
            // player can decode without a custom demuxer. Order of preference:
            //   1. M4A/AAC — universal; every stock player, MediaStore works
            //   2. MP3     — universal; slightly lower quality/bitrate
            //   3. OGG-OPUS — pure Opus; plays in Poweramp, stock Android 6+
            //   4. WEBMA_OPUS / WEBMA — last resort. WebM container, doesn't
            //      show up in many audio players' library scans.
            val audioByFormat = stream.audioStreams.groupBy {
                when (it.format) {
                    org.schabi.newpipe.extractor.MediaFormat.M4A -> 1
                    org.schabi.newpipe.extractor.MediaFormat.MP3 -> 2
                    org.schabi.newpipe.extractor.MediaFormat.MP2 -> 2
                    org.schabi.newpipe.extractor.MediaFormat.OPUS -> 3
                    org.schabi.newpipe.extractor.MediaFormat.WEBMA -> 4
                    org.schabi.newpipe.extractor.MediaFormat.WEBMA_OPUS -> 4
                    org.schabi.newpipe.extractor.MediaFormat.OGG -> 3
                    org.schabi.newpipe.extractor.MediaFormat.FLAC -> 3
                    org.schabi.newpipe.extractor.MediaFormat.AIFF,
                    org.schabi.newpipe.extractor.MediaFormat.AIF -> 3
                    org.schabi.newpipe.extractor.MediaFormat.WAV -> 3
                    else -> 5
                }
            }
            // Pick the best-ranked bucket, then the highest bitrate in it.
            val bestPriority = audioByFormat.keys.minOrNull() ?: Int.MAX_VALUE
            audioByFormat[bestPriority]?.maxByOrNull { it.averageBitrate }
                ?: stream.audioStreams.maxByOrNull { it.averageBitrate }
                // Last-ditch fallback to video if no audio stream at all.
                ?: stream.videoStreams.filter { it.isVideoOnly.not() }.maxByOrNull { it.height }
                ?: stream.videoStreams.maxByOrNull { it.height }
        } else {
            stream.videoStreams.filter { it.isVideoOnly.not() }.maxByOrNull { it.height }
                ?: stream.videoStreams.maxByOrNull { it.height }
                ?: stream.audioStreams.maxByOrNull { it.averageBitrate }
        }
            ?: error("No playable streams found at this URL. The site may have changed; " +
                "the extractor can be updated by replacing the NewPipe AAR. (URL: $url)")
        val fmt = best.format
        // NewPipe's MediaFormat has TWO "name"-like accessors:
        //   • the public String field `name`  — the user-facing display name ("WebM Opus")
        //   • the inherited Enum name from `ordinal` — the constant ("WEBMA_OPUS")
        // In Kotlin, `fmt.name` resolves to the public field, not the enum name.
        // Comparing against enum-identifier strings therefore never matches and
        // everything fell through to a munged display-name — the "webm-opus" bug.
        // Use the enum constant identity directly instead.
        val ext = when (fmt) {
            org.schabi.newpipe.extractor.MediaFormat.MPEG_4 -> "mp4"
            // IMPORTANT: WEBMA / WEBMA_OPUS are Matroska-style containers with
            // Opus (or Vorbis) audio inside. Giving them the ".opus" extension
            // makes standard audio players refuse — they expect Ogg-wrapped
            // Opus (magic 4F 67 67 53 "OggS"), not WebM (magic 1A 45 DF A3).
            // Save them as .webm — VLC/MX Player/Poweramp all play that fine,
            // and the files are still audio-only on disk.
            org.schabi.newpipe.extractor.MediaFormat.WEBM -> "webm"
            org.schabi.newpipe.extractor.MediaFormat.WEBMA,
            org.schabi.newpipe.extractor.MediaFormat.WEBMA_OPUS -> "webm"
            // True Ogg-encapsulated Opus — safe as .opus
            org.schabi.newpipe.extractor.MediaFormat.OPUS -> "opus"
            org.schabi.newpipe.extractor.MediaFormat.M4A -> "m4a"
            org.schabi.newpipe.extractor.MediaFormat.MP3 -> "mp3"
            org.schabi.newpipe.extractor.MediaFormat.MP2 -> "mp2"
            org.schabi.newpipe.extractor.MediaFormat.OGG -> "ogg"
            org.schabi.newpipe.extractor.MediaFormat.AIFF,
            org.schabi.newpipe.extractor.MediaFormat.AIF -> "aiff"
            org.schabi.newpipe.extractor.MediaFormat.WAV -> "wav"
            org.schabi.newpipe.extractor.MediaFormat.FLAC -> "flac"
            org.schabi.newpipe.extractor.MediaFormat.ALAC -> "m4a"
            org.schabi.newpipe.extractor.MediaFormat.v3GPP -> "3gp"
            org.schabi.newpipe.extractor.MediaFormat.TTML -> "ttml"
            org.schabi.newpipe.extractor.MediaFormat.VTT -> "vtt"
            null -> "mp4"
            // TRANSCRIPT1..3 and future formats fall through here.
            else -> (fmt.suffix.ifBlank { fmt.name }).lowercase()
                .replace(Regex("[_\\s]+"), "-")
                .trim('-')
                .ifBlank { "bin" }
        }
        val safeName = stream.name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(200)
        val contentUrl = best.content
            ?: error("NewPipe returned a stream but no content URL — likely a manifest " +
                "format TurboDM can't download directly (HLS/DASH). " +
                "(URL: $url)")
        return ResolvedStream(
            fileName = "$safeName.$ext",
            resolvedUrl = contentUrl
        )
    }

    private data class ResolvedStream(val fileName: String, val resolvedUrl: String)
}
package com.turbodm.app.download

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a URL to the [SchemeHandler] that should drive it.
 *
 * Routing is three-tiered, in order of precedence:
 *   1. **Path-based**: a URL whose path ends in `.m3u8` / `.mpd` is claimed by
 *      the HLS handler regardless of the host — any CDN that serves an HLS
 *      manifest gets the segmented transfer path.
 *   2. **Host-based**: a handler whose [SchemeHandler.hostPatterns] list contains
 *      the URL's host wins. This is how TikTok / YouTube / SoundCloud URLs route
 *      to their dedicated handlers even though they're plain HTTPS.
 *   3. **Scheme-based** (fallback): if neither of the previous two match, the
 *      handler that declares the URL's scheme ("http", "magnet", "content", …)
 *      wins. The first one to declare a scheme claims it.
 *
 * Schemes are compared case-insensitively (RFC 3986 §3.1).
 */
@Singleton
class SchemeRegistry @Inject constructor(
    handlers: Set<@JvmSuppressWildcards SchemeHandler>
) {

    private val byScheme: Map<String, SchemeHandler> =
        handlers.flatMap { h -> h.schemes.map { it.lowercase() to h } }
            .toMap()

    private val hostHandlers: List<SchemeHandler> =
        handlers.filter { it.hostPatterns.isNotEmpty() }

    /** All handlers, retained so we can find path-based (manifest) matches. */
    private val all = handlers.toList()

    /**
     * Returns the handler for [url], or null if no handler claims it.
     * The scheme is extracted with a permissive regex so non-Android-URI
     * strings (e.g. `magnet:?xt=urn:btih:...`) are recognized on pure-JVM
     * tests where `android.net.Uri.parse` would return null.
     */
    fun handlerFor(url: String): SchemeHandler? {
        val scheme = extractScheme(url) ?: return null
        // 1. Path-based override — HLS / DASH manifest URLs always route to
        //    the segmented downloader before any host- or scheme-level match.
        firstManifestHandler(url)?.let { return it }
        // 2. Host-based override.
        extractHost(url)?.let { host ->
            hostHandlers.firstOrNull { h -> h.matchesHost(host) }
                ?.let { return it }
        }
        // 3. Scheme fallback.
        return byScheme[scheme.lowercase()]
    }

    /** All schemes currently registered. Useful for UI hints. */
    val supportedSchemes: Set<String> get() = byScheme.keys

    /**
     * Returns the handler claiming this URL because its path ends in a
     * manifest extension. Right now only [HlsSchemeHandler] registers as a
     * manifest handler, but keeping it generic lets a future DASH handler
     * slot in without touching this code.
     */
    private fun firstManifestHandler(url: String): SchemeHandler? {
        val path = url.substringAfter("://", "").lowercase()
        if (!path.endsWith(".m3u8") && !path.endsWith(".mpd") &&
            !path.contains(".m3u8?") && !path.contains(".mpd?")
        ) return null
        return all.firstOrNull { h -> h is com.turbodm.app.download.handlers.HlsSchemeHandler }
    }

    companion object {
        // Matches "scheme:" at the start, allowing scheme to contain letters,
        // digits, '+', '-', '.'. Magnet links use lowercase; HTTP/S use mixed.
        private val SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

        // Marker separating the scheme from the rest of the URL — `://` for
        // hierarchical schemes (http, https, ftp), `:` for opaque schemes
        // (magnet, data). We only try to extract a host when the URL is
        // hierarchical.
        private val AUTHORITY_SEPARATOR = Regex("://")

        /** Pulls the scheme prefix off a URL string. Returns null for blank input. */
        fun extractScheme(url: String): String? {
            if (url.isBlank()) return null
            return SCHEME_REGEX.find(url)?.groupValues?.getOrNull(1)
        }

        /**
         * Pulls the hostname out of a hierarchical URL string (one with `://`),
         * lowercased. Returns null for opaque schemes (e.g. `magnet:?xt=...`),
         * for blank input, or when the authority part is malformed.
         *
         * Host extraction strips userinfo (`user@host`) and port (`host:port`).
         * The end of the authority is the first `/`, `?`, or `#` — anything
         * after that is path/query/fragment and not part of the host.
         */
        fun extractHost(url: String): String? {
            if (url.isBlank()) return null
            val sep = AUTHORITY_SEPARATOR.find(url) ?: return null
            // Authority starts right after `://`.
            val authorityStart = sep.range.last + 1
            if (authorityStart >= url.length) return null
            val rest = url.substring(authorityStart)
            // Stop at first path/query/fragment marker.
            val endIdx = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, endIdx)
            // Strip userinfo (user@host) — keep everything after the last `@`.
            val withoutUserinfo = authority.substringAfter('@', authority)
            // Strip port (host:port) — keep everything before the first `:`.
            val withoutPort = withoutUserinfo.substringBefore(':')
            val host = withoutPort.trim().lowercase()
            return host.ifBlank { null }
        }
    }
}

/**
 * Returns true if [host] matches any of this handler's [SchemeHandler.hostPatterns].
 * Suffix-based: `"www.youtube.com"` matches `"youtube.com"`, but
 * `"notyoutube.com"` does not (the trailing dot is required to avoid
 * false-positives on unrelated domains).
 */
private fun SchemeHandler.matchesHost(host: String): Boolean =
    hostPatterns.any { pattern ->
        host == pattern || host.endsWith(".$pattern")
    }

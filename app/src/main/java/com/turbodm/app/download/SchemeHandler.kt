package com.turbodm.app.download

import com.turbodm.app.domain.model.Download

/**
 * A `SchemeHandler` knows how to handle one URL scheme from probe to byte-fetch.
 *
 * The two phases are kept separate so the controller can:
 *   1. Probe — learn size, fileName, mimeType, range support, before writing a row.
 *   2. Fetch — once a row exists, the engine asks the matching handler to drive it.
 *
 * For HTTP(S) this maps straight to OkHttp. For `content://` / `file://` it's a
 * single-chunk stream-copy. For `magnet:` it's a long-running BitTorrent session.
 *
 * Handlers are stateless w.r.t. specific downloads — they read whatever they need
 * from the [Download] row. Cancellation is cooperative via the engine's existing
 * coroutine cancellation; callers can `break` out of the fetch loop on cancellation.
 */
interface SchemeHandler {

    /**
     * The URL schemes this handler accepts. Lowercase. The first matching scheme
     * in [SchemeRegistry] wins.
     */
    val schemes: Set<String>

    /**
     * Hostname suffixes this handler specializes in (e.g. `"youtube.com"`, `"youtu.be"`).
     * Match is suffix-based — `"www.youtube.com"` matches `"youtube.com"`. Empty by default.
     *
     * Host-based matching is checked before scheme-based matching in
     * [SchemeRegistry.handlerFor], so a handler declaring both a scheme (`http`/`https`)
     * and host patterns still loses to a more specialized host match. This is how
     * [com.turbodm.app.download.handlers.StreamingSchemeHandler] preempts
     * [com.turbodm.app.download.handlers.HttpSchemeHandler] for streaming sites
     * while leaving plain HTTP(S) downloads alone.
     */
    val hostPatterns: Set<String>
        get() = emptySet()

    /**
     * Resolve a URL into the metadata stored on a `Download` row. May suspend
     * for arbitrarily long (e.g. magnet: waits for metadata) — callers should
     * run this on a background dispatcher.
     */
    suspend fun probe(url: String): LinkAnalyzer.Info

    /**
     * Drive the actual byte transfer for a download. Implementations should
     * honor coroutine cancellation and update the [Download] row through
     * the supplied repo.
     */
    suspend fun fetch(download: Download)
}

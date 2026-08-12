package com.turbodm.app.download.handlers

import com.turbodm.app.download.DownloadEngine
import com.turbodm.app.download.LinkAnalyzer
import com.turbodm.app.download.SchemeHandler
import com.turbodm.app.domain.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles `http://` and `https://` URLs through the existing OkHttp-based engine.
 *
 * Probe delegates to [LinkAnalyzer.analyze]; fetch delegates to [DownloadEngine.start].
 * This is the historical behavior pre-scheme-registry, so on-the-wire semantics
 * for HTTP(S) are unchanged.
 *
 * The probe wraps the underlying OkHttp call in [Dispatchers.IO]. `LinkAnalyzer.analyze`
 * does a synchronous HEAD/range GET — running that on the calling thread (typically
 * Main when invoked from a ViewModel) triggers NetworkOnMainThreadException. The
 * `withContext` is idempotent if the caller is already on IO. Matches the pattern
 * used by [FtpSchemeHandler], [ContentSchemeHandler], and [StreamingSchemeHandler].
 */
@Singleton
class HttpSchemeHandler @Inject constructor(
    private val analyzer: LinkAnalyzer,
    private val engine: DownloadEngine
) : SchemeHandler {

    override val schemes: Set<String> = setOf("http", "https")

    override suspend fun probe(url: String): LinkAnalyzer.Info =
        withContext(Dispatchers.IO) { analyzer.analyze(url) }

    override suspend fun fetch(download: Download) {
        engine.start(download.id)
    }
}

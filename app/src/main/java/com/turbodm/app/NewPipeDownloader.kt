package com.turbodm.app

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges NewPipeExtractor's [Downloader] to OkHttp. Required for [org.schabi.newpipe.extractor.NewPipe.init].
 *
 * NewPipe calls this for every page fetch (search, stream metadata, etc.).
 * Streaming sites' pages are fetched twice per resolution — once for the page
 * HTML, once for the player JS bundle. Both happen lazily inside
 * `getStreamExtractor().fetchPage()` so a single download triggers two HTTP
 * round-trips through here.
 */
@Singleton
class NewPipeDownloader @Inject constructor(
    private val client: OkHttpClient
) : Downloader() {

    override fun execute(request: Request): Response {
        // NewPipe's Request.dataToSend() is a Java getter; in Kotlin it conflicts
        // with the private field of the same name on the source side. Calling
        // via reflection-free method works: Kotlin sees the public getter.
        val bytes = request.dataToSend()
        val body = bytes?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
        for ((name, values) in request.headers()) {
            for (v in values) builder.header(name, v)
        }
        // Some sites reject requests without a browser-like User-Agent.
        if (request.headers()["User-Agent"] == null) {
            builder.header("User-Agent", DEFAULT_UA)
        }
        return client.newCall(builder.build()).execute().use { resp ->
            val respHeaders = resp.headers.toMultimap()
            // NewPipe's Response takes the body as a String, not bytes — UTF-8
            // decoding here matches what NewPipe does internally for HTML/JSON.
            val respBody = resp.body?.string() ?: ""
            Response(
                resp.code,
                resp.message,
                respHeaders,
                respBody,
                request.url()
            )
        }
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
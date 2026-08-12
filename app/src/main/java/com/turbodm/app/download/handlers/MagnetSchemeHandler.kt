package com.turbodm.app.download.handlers

import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.download.LinkAnalyzer
import com.turbodm.app.download.SchemeHandler

/**
 * Registers `magnet:` in the [com.turbodm.app.download.SchemeRegistry] so that
 * `handlerFor("magnet:?xt=...")` returns non-null. This is important because:
 *
 *  - [com.turbodm.app.ui.MainActivity.extractUrl] uses the registry to validate
 *    a share/intent URL — if `magnet:` weren't registered, an incoming magnet
 *    would be silently dropped.
 *  - The `[AddDownloadScreen]` ViewModel checks the registry to decide whether
 *    to call `controller.addAndStart(...)` or `controller.addMagnet(...)`.
 *
 * The actual magnet workflow does not use [probe]/[fetch]: magnets need a
 * metadata fetch, then a file picker, then an engine start — that's three
 * distinct user-visible steps, not a single byte transfer. So both
 * implementations here just fail-fast with a clear error pointing at the
 * real entry point.
 */
class MagnetSchemeHandler : SchemeHandler {

    override val schemes: Set<String> = setOf("magnet")

    override suspend fun probe(url: String): LinkAnalyzer.Info {
        error("Magnet links must go through DownloadController.addMagnet, not the scheme probe path. URL: $url")
    }

    override suspend fun fetch(download: Download) {
        // Defensive: if anyone wires `schemeRegistry.handlerFor(magnet).fetch(d)`,
        // surface a clear error rather than mysteriously no-op.
        error("Magnet downloads do not use the SchemeHandler.fetch path; use DownloadController.startTorrent instead. id=${download.id}")
    }
}

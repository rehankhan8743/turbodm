package com.turbodm.app.download.handlers

import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.download.LinkAnalyzer
import com.turbodm.app.download.SchemeHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams a `file://` URL into a `.part` file.
 *
 * `file://` on Android is only useful for files the app can already see (the
 * app's own files, or a world-readable path on shared storage). The handler
 * requires no permission grant and works offline.
 *
 * Note: on Android 7+ this won't see content URIs from other apps — those
 * must come through [ContentSchemeHandler] with a content:// URI.
 */
@Singleton
class FileSchemeHandler @Inject constructor(
    private val repo: DownloadRepository
) : SchemeHandler {

    override val schemes: Set<String> = setOf("file")

    override suspend fun probe(url: String): LinkAnalyzer.Info {
        // file:///sdcard/Download/foo.bin → /sdcard/Download/foo.bin
        val path = url.removePrefix("file://").removePrefix("file:")
        val f = File(path)
        return LinkAnalyzer.Info(
            fileName = f.name.ifBlank { "download.bin" },
            totalBytes = if (f.exists()) f.length() else -1L,
            mimeType = null,
            supportsRange = false
        )
    }

    override suspend fun fetch(download: Download) = withContext(Dispatchers.IO) {
        val path = download.url.removePrefix("file://").removePrefix("file:")
        val src = File(path)
        val tmp = File(download.targetPath + ".part")
        tmp.parentFile?.mkdirs()

        repo.setStatus(download.id, DownloadStatus.DOWNLOADING)
        try {
            if (!src.exists()) {
                repo.setError(download.id, "Source file does not exist: $path", DownloadStatus.FAILED)
                return@withContext
            }
            src.inputStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (tmp.exists()) {
                if (File(download.targetPath).exists()) File(download.targetPath).delete()
                if (!tmp.renameTo(File(download.targetPath))) {
                    repo.setError(download.id, "Rename failed", DownloadStatus.FAILED)
                    return@withContext
                }
            }
            repo.setDownloaded(download.id, tmp.length())
            repo.markCompleted(download.id)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            repo.setError(download.id, t.message ?: t.javaClass.simpleName, DownloadStatus.FAILED)
        }
    }
}
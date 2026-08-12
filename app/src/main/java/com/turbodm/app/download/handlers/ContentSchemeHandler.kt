package com.turbodm.app.download.handlers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.turbodm.app.download.LinkAnalyzer
import com.turbodm.app.download.SchemeHandler
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.data.repo.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams a `content://` URI into a `.part` file via the ContentResolver.
 *
 * The URI is typically transient — if the calling activity's grant expires
 * (e.g. process is killed and the user shares again from a different app)
 * the InputStream throws and the download is marked FAILED.
 *
 * Size and display name come from the [OpenableColumns] cursor; if either is
 * missing we fall back to "download.bin" / -1.
 */
@Singleton
class ContentSchemeHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository
) : SchemeHandler {

    override val schemes: Set<String> = setOf("content")

    override suspend fun probe(url: String): LinkAnalyzer.Info = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        val resolver = context.contentResolver
        var fileName: String? = null
        var size: Long = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) fileName = c.getString(nameIdx)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {
            // Some providers don't expose columns; we'll use fallbacks below.
        }
        val mime = resolver.getType(uri)
        LinkAnalyzer.Info(
            fileName = sanitize(fileName ?: deriveNameFromUri(uri)),
            totalBytes = size,
            mimeType = mime,
            supportsRange = false
        )
    }

    override suspend fun fetch(download: Download) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(download.url)
        val resolver = context.contentResolver
        val tmp = File(download.targetPath + ".part")
        tmp.parentFile?.mkdirs()

        repo.setStatus(download.id, DownloadStatus.DOWNLOADING)
        var written = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        written += n
                    }
                }
            } ?: throw FileNotFoundException("ContentResolver returned null InputStream for $uri")

            if (tmp.exists()) {
                if (File(download.targetPath).exists()) File(download.targetPath).delete()
                if (!tmp.renameTo(File(download.targetPath))) {
                    repo.setError(download.id, "Rename failed", DownloadStatus.FAILED)
                    return@withContext
                }
            }
            repo.setDownloaded(download.id, written)
            repo.markCompleted(download.id)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            repo.setError(download.id, t.message ?: t.javaClass.simpleName, DownloadStatus.FAILED)
        }
    }

    private fun deriveNameFromUri(uri: Uri): String {
        val last = uri.lastPathSegment ?: return "download.bin"
        // Strip query strings if any slipped in.
        return sanitize(last.substringBefore('?'))
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(200).ifBlank { "download.bin" }
}
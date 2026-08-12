package com.turbodm.app.download.handlers

import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.download.LinkAnalyzer
import com.turbodm.app.download.SchemeHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams a `ftp://` URL into a `.part` file via Apache Commons Net.
 *
 * FTPS is not enabled (commons-net supports it but you'd need a settings toggle
 * for self-signed cert acceptance). Anonymous login by default — for accounts
 * we'd extend the Download entity with a username/password pair.
 *
 * Resume is not supported: FTP `REST` is widely broken on modern servers.
 * Single-chunk stream-copy, no Range.
 */
@Singleton
class FtpSchemeHandler @Inject constructor(
    private val repo: DownloadRepository
) : SchemeHandler {

    override val schemes: Set<String> = setOf("ftp")

    override suspend fun probe(url: String): LinkAnalyzer.Info = withContext(Dispatchers.IO) {
        val uri = URI(url)
        val client = FTPClient().apply {
            connect(uri.host, if (uri.port > 0) uri.port else 21)
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                error("FTP connect failed: ${replyString}")
            }
            login("anonymous", "anonymous@")
        }
        try {
            val totalBytes = try {
                val raw = client.sendCommand("SIZE", uri.path)
                if (FTPReply.isPositiveCompletion(raw)) {
                    client.replyString.substringAfter(' ').trim().toLongOrNull() ?: -1L
                } else -1L
            } catch (_: Exception) { -1L }
            LinkAnalyzer.Info(
                fileName = uri.path.substringAfterLast('/').ifBlank { "download.bin" },
                totalBytes = totalBytes,
                mimeType = null,
                supportsRange = false
            )
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    override suspend fun fetch(download: Download) = withContext(Dispatchers.IO) {
        val uri = URI(download.url)
        val tmp = File(download.targetPath + ".part")
        tmp.parentFile?.mkdirs()

        val client = FTPClient()
        repo.setStatus(download.id, DownloadStatus.DOWNLOADING)
        try {
            client.connect(uri.host, if (uri.port > 0) uri.port else 21)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                repo.setError(download.id, "FTP connect failed: ${client.replyString}", DownloadStatus.FAILED)
                return@withContext
            }
            client.login("anonymous", "anonymous@")
            client.enterLocalPassiveMode()
            client.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)

            client.retrieveFile(uri.path, tmp.outputStream()).let { ok ->
                if (!ok) {
                    repo.setError(download.id, "FTP retrieve failed: ${client.replyString}", DownloadStatus.FAILED)
                    return@withContext
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
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }
}
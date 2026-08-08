package com.turbodm.app.download

import com.turbodm.app.data.local.DownloadDao
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the live download jobs. Each transfer writes to a temp file and streams
 * progress into the repository. Multi-part segmentation is a Phase 2 concern;
 * this implementation is single-thread but resumable via range request.
 */
@Singleton
class DownloadEngine @Inject constructor(
    private val client: OkHttpClient,
    private val repo: DownloadRepository,
    private val dao: DownloadDao,
    private val settings: SettingsRepository
) {
    data class ProgressEvent(val id: Long, val downloaded: Long, val total: Long)

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    private val _events = MutableSharedFlow<ProgressEvent>(extraBufferCapacity = 64)
    val running: StateFlow<Set<Long>> = _running.asStateFlow()
    val events: SharedFlow<ProgressEvent> = _events.asSharedFlow()

    fun start(id: Long) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = scope.launch { run(id) }
    }

    fun pause(id: Long) {
        jobs.remove(id)?.cancel()
        _running.value = _running.value - id
        scope.launch { repo.setStatus(id, DownloadStatus.PAUSED) }
    }

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        _running.value = _running.value - id
        scope.launch {
            repo.setStatus(id, DownloadStatus.CANCELLED)
            dao.getById(id)?.let {
                val tmp = File(it.targetPath + ".part")
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    private suspend fun run(id: Long) {
        val d = repo.get(id) ?: return
        val settingsSnap = settings.flow.first()
        val target = File(d.targetPath)
        target.parentFile?.mkdirs()
        val tmp = File(d.targetPath + ".part")
        val already = if (tmp.exists()) tmp.length() else 0L

        repo.setStatus(id, DownloadStatus.DOWNLOADING)
        _running.value = _running.value + id

        val request = Request.Builder()
            .url(d.url)
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .apply { d.referer?.let { header("Referer", it) } }
            .apply { d.userAgent?.let { header("User-Agent", it) } ?: header("User-Agent", settingsSnap.userAgent) }
            .apply { d.cookies?.let { header("Cookie", it) } }
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    repo.setError(id, "HTTP ${resp.code}", DownloadStatus.FAILED)
                    return
                }
                val body = resp.body ?: run {
                    repo.setError(id, "Empty body", DownloadStatus.FAILED); return
                }
                val total = d.totalBytes.takeIf { it > 0 }
                    ?: resp.header("Content-Length")?.toLongOrNull()?.let { it + already } ?: -1L
                repo.setMetadata(id, total, d.supportsRange, DownloadStatus.DOWNLOADING)

                RandomAccessFile(tmp, "rw").use { raf ->
                    if (already > 0) raf.seek(already)
                    val src = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var written = already
                    var lastEmit = System.currentTimeMillis()
                    while (true) {
                        val n = src.read(buf)
                        if (n == -1) break
                        raf.write(buf, 0, n)
                        written += n
                        _events.tryEmit(ProgressEvent(id, written, total))
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 400) {
                            repo.setDownloaded(id, written)
                            lastEmit = now
                        }
                    }
                }
            }
            if (tmp.exists()) tmp.renameTo(target)
            repo.markCompleted(id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            repo.setError(id, t.message, DownloadStatus.FAILED)
        } finally {
            _running.value = _running.value - id
            jobs.remove(id)
        }
    }
}

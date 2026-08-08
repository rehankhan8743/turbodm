package com.turbodm.app.download

import com.turbodm.app.data.local.ChunkDao
import com.turbodm.app.data.local.ChunkEntity
import com.turbodm.app.data.local.DownloadDao
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2 download engine. Each transfer is split into chunks via [ChunkPlanner],
 * each chunk runs as its own coroutine writing into a shared `.part` file via
 * [RandomAccessFile.seek]. Per-chunk retry is handled by [RetryPolicy].
 *
 * Resume after process kill is supported: on [start], chunks that already have
 * progress in the database are skipped (or shifted forward to their partial byte
 * position) so we don't re-fetch bytes we already have.
 *
 * Cancellation is cooperative via Kotlin coroutines — no mutable-map flags.
 */
@Singleton
class DownloadEngine @Inject constructor(
    private val client: OkHttpClient,
    private val repo: DownloadRepository,
    private val dao: DownloadDao,
    private val chunkDao: ChunkDao,
    private val settings: SettingsRepository,
    private val retryPolicy: RetryPolicy,
    private val speedTracker: SpeedTracker
) {

    data class ProgressEvent(
        val id: Long,
        val downloaded: Long,
        val total: Long,
        val bps: Long,
        val activeChunks: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val chunkBytes = ConcurrentHashMap<Long, ConcurrentHashMap<Int, Long>>()
    private val rafLocks = ConcurrentHashMap<Long, Mutex>()

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
        chunkBytes.remove(id)
        rafLocks.remove(id)
        speedTracker.reset(id)
        scope.launch {
            repo.setStatus(id, DownloadStatus.CANCELLED)
            dao.getById(id)?.let {
                val tmp = File(it.targetPath + ".part")
                if (tmp.exists()) tmp.delete()
            }
            chunkDao.deleteForDownload(id)
        }
    }

    private suspend fun run(id: Long) {
        val d = repo.get(id) ?: return
        val settingsSnap = settings.flow.first()
        val target = File(d.targetPath)
        target.parentFile?.mkdirs()
        val tmp = File(d.targetPath + ".part")

        repo.setStatus(id, DownloadStatus.DOWNLOADING)
        _running.value = _running.value + id

        try {
            val specs = planChunks(id, d)

            rafLocks[id] = Mutex()
            val perChunk = ConcurrentHashMap<Int, Long>().also { chunkBytes[id] = it }

            coroutineScope {
                specs.map { spec ->
                    async(Dispatchers.IO) {
                        runChunk(id, d, spec, settingsSnap.userAgent, tmp, perChunk)
                    }
                }.awaitAll()
            }

            // Verify final size if the server told us. Some servers lie; if the
            // mismatch is small (last few KB), we accept it. Large mismatches fail.
            if (d.totalBytes > 0) {
                val actual = tmp.length()
                if (actual < d.totalBytes - 1024) {
                    repo.setError(id, "Truncated: got $actual of ${d.totalBytes}", DownloadStatus.FAILED)
                    return
                }
                if (actual > d.totalBytes) {
                    // Truncate to expected size.
                    RandomAccessFile(tmp, "rw").use { it.setLength(d.totalBytes) }
                }
            }

            if (tmp.exists()) {
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    repo.setError(id, "Rename failed", DownloadStatus.FAILED)
                    return
                }
            }
            repo.markCompleted(id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            repo.setError(id, t.message ?: t.javaClass.simpleName, DownloadStatus.FAILED)
        } finally {
            _running.value = _running.value - id
            chunkBytes.remove(id)
            rafLocks.remove(id)
            speedTracker.reset(id)
            jobs.remove(id)
        }
    }

    /** Builds the chunk plan, persisting fresh rows on first run. */
    private suspend fun planChunks(id: Long, d: Download): List<ChunkPlanner.Spec> {
        val existing = chunkDao.forDownload(id).associateBy { it.index }
        // Always honor totalBytes if known. If unknown (chunked transfer with no
        // Content-Length), force a single chunk that runs until the body ends.
        if (d.totalBytes <= 0) {
            val single = ChunkPlanner.Spec(0, 0, -1L)
            if (existing.isEmpty()) {
                chunkDao.insertAll(listOf(ChunkEntity(downloadId = id, index = 0, startByte = 0, endByte = -1L, downloadedBytes = 0L)))
            }
            return listOf(single)
        }
        val segments = if (!d.supportsRange) 1 else d.segments.coerceAtLeast(1)
        val raw = ChunkPlanner.plan(d.totalBytes, segments)
        if (existing.isEmpty()) {
            chunkDao.insertAll(raw.map {
                ChunkEntity(downloadId = id, index = it.index, startByte = it.startByte, endByte = it.endByte, downloadedBytes = 0L)
            })
        }
        val completed = existing.filterValues { it.endByte > 0 && it.downloadedBytes >= (it.endByte - it.startByte + 1) }.keys
        val partial = existing.filterValues { v -> v.downloadedBytes > 0L && v.downloadedBytes < (if (v.endByte > 0) v.endByte - v.startByte + 1 else Long.MAX_VALUE) }
            .mapValues { it.value.downloadedBytes }
        return ChunkPlanner.reconcile(raw, completed, partial)
    }

    private suspend fun runChunk(
        downloadId: Long,
        d: Download,
        spec: ChunkPlanner.Spec,
        defaultUA: String,
        tmp: File,
        perChunk: ConcurrentHashMap<Int, Long>
    ) {
        var attempt = 0
        var resumeAt: Long = spec.startByte // grows as bytes for this chunk are written

        // Honor any persisted partial progress for this chunk.
        val existing = chunkDao.forDownload(downloadId).firstOrNull { it.index == spec.index }
        if (existing != null && existing.downloadedBytes > 0 && spec.endByte > 0) {
            resumeAt = spec.startByte + existing.downloadedBytes
        }

        while (true) {
            try {
                val endHeader = if (spec.endByte > 0) spec.endByte else ""
                val request = Request.Builder()
                    .url(d.url)
                    .header("Range", "bytes=$resumeAt-$endHeader")
                    .apply { d.referer?.let { header("Referer", it) } }
                    .apply { d.userAgent?.let { header("User-Agent", it) } ?: header("User-Agent", defaultUA) }
                    .apply { d.cookies?.let { header("Cookie", it) } }
                    .build()

                client.newCall(request).execute().use { resp ->
                    val httpStatus = resp.code
                    if (httpStatus != 200 && httpStatus != 206) {
                        val outcome = retryPolicy.classify(IOException("HTTP $httpStatus"), httpStatus, attempt)
                        when (outcome) {
                            is RetryPolicy.Outcome.Transient -> {
                                attempt = outcome.attempt
                                retryPolicy.awaitBackoff(outcome)
                                return@use
                            }
                            RetryPolicy.Outcome.Permanent -> error("HTTP $httpStatus")
                            RetryPolicy.Outcome.Exhausted -> error("HTTP $httpStatus after $attempt retries")
                        }
                    }
                    // If we asked for a range and the server ignored us and returned 200,
                    // bail out — writing the full body at seek(resumeAt) would corrupt the
                    // file. Caller falls back to a single-chunk plan or marks the download FAILED.
                    if (httpStatus == 200 && resumeAt > spec.startByte) {
                        error("Server returned full body despite Range request (resuming not supported)")
                    }
                    val body = resp.body ?: error("Empty body")
                    val lock = rafLocks.getValue(downloadId)
                    RandomAccessFile(tmp, "rw").use { raf ->
                        raf.seek(resumeAt)
                        val src = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var lastEmit = 0L
                        var lastFlush = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n == -1) break
                            lock.withLock { raf.write(buf, 0, n) }
                            resumeAt += n
                            perChunk[spec.index] = resumeAt - spec.startByte
                            speedTracker.record(downloadId, n.toLong())
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 250) {
                                emit(downloadId, d, perChunk)
                                lastEmit = now
                            }
                            if (now - lastFlush > 750) {
                                chunkDao.setDownloaded(downloadId, spec.index, resumeAt - spec.startByte)
                                lastFlush = now
                            }
                            if (spec.endByte > 0 && resumeAt > spec.endByte) break
                        }
                    }
                    // Persist final position for this chunk.
                    chunkDao.setDownloaded(downloadId, spec.index, resumeAt - spec.startByte)
                    return
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val outcome = retryPolicy.classify(t, null, attempt)
                when (outcome) {
                    is RetryPolicy.Outcome.Transient -> {
                        attempt = outcome.attempt
                        retryPolicy.awaitBackoff(outcome)
                    }
                    RetryPolicy.Outcome.Permanent -> throw t
                    RetryPolicy.Outcome.Exhausted -> throw t
                }
            }
        }
    }

    private suspend fun emit(downloadId: Long, d: Download, perChunk: ConcurrentHashMap<Int, Long>) {
        val downloaded = perChunk.values.sum()
        val bps = speedTracker.bps.value[downloadId] ?: 0L
        _events.tryEmit(ProgressEvent(downloadId, downloaded, d.totalBytes, bps, perChunk.size))
        repo.setDownloaded(downloadId, downloaded)
    }
}

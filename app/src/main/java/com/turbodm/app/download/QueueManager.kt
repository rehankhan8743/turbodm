package com.turbodm.app.download

import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded-concurrency queue. Caps concurrent downloads at
 * [SettingsRepository.maxParallel]. No semaphores — we just gate on a count and
 * the engine handles the actual lifecycle of each job.
 *
 * Promotion order: priority desc, then createdAt asc — so a "high priority"
 * download always jumps ahead of older queued ones, but within a priority bucket
 * it's FIFO.
 *
 * The queue does NOT persist state. If the process dies, the engine reloads
 * QUEUED items from Room on next start.
 *
 * v2: promotion now routes through the matching [SchemeHandler.fetch] instead
 * of calling [DownloadEngine.start] directly. The handler decides what to do —
 * for `http(s):` it just kicks the engine, for `streaming sites` it resolves
 * the URL via NewPipeExtractor, re-targets the row, and re-queues so the next
 * iteration picks it up via [HttpSchemeHandler]. This was the missing wire that
 * caused streaming URLs to download the page HTML instead of the media.
 */
@Singleton
class QueueManager @Inject constructor(
    private val repo: DownloadRepository,
    private val engine: DownloadEngine,
    private val settings: SettingsRepository,
    private val schemeRegistry: SchemeRegistry
) {

    data class State(val permits: Int, val inFlight: Int, val queued: Int) {
        val free: Int get() = (permits - inFlight).coerceAtLeast(0)
        val full: Boolean get() = inFlight >= permits
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var watcherJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(State(3, 0, 0))
    val state: StateFlow<State> = _state.asStateFlow()

    fun start() {
        if (watcherJob?.isActive == true) return
        watcherJob = scope.launch {
            combine(
                repo.observeByStatuses(listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)),
                settings.flow
            ) { active, snap -> active to snap.maxParallel }
            .collect { (rows, permits) ->
                // Torrent states (ANALYZING etc.) run through TorrentEngine and
                // must NOT consume HTTP download permits — otherwise a stuck
                // torrent would starve the whole queue.
                val queued = rows.filter { it.status == DownloadStatus.QUEUED }
                val active = rows.filter { it.status == DownloadStatus.DOWNLOADING }
                _state.value = State(permits, active.size, queued.size)
                promote(queued, active.size, permits)
            }
        }
    }

    private suspend fun promote(queued: List<Download>, inFlight: Int, permits: Int) {
        mutex.withLock {
            val ordered = queued.sortedWith(
                compareByDescending<Download> { it.priority }.thenBy { it.createdAt }
            )
            val slots = (permits - inFlight).coerceAtLeast(0)
            if (slots <= 0) return@withLock
            ordered.take(slots).forEach { d ->
                dispatch(d)
            }
        }
    }

    /**
     * Routes a queued row through its matching [SchemeHandler.fetch]. For most
     * schemes (http, https, ftp, content, file) this just calls
     * [DownloadEngine.start]. For streaming sites the handler resolves the URL
     * to its direct-media form, updates the row, and re-queues it — the next
     * queue iteration then drives the resolved URL through the HTTP handler.
     *
     * If no handler is registered for the URL (defensive — shouldn't happen
     * because `controller.addAndStart` rejects unknown schemes at creation
     * time), fall back to the engine directly. Better than leaving the row
     * stuck in QUEUED forever.
     */
    private fun dispatch(d: Download) {
        val handler = schemeRegistry.handlerFor(d.url)
        if (handler == null) {
            engine.start(d.id)
        } else {
            scope.launch { handler.fetch(d) }
        }
    }
}

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
 */
@Singleton
class QueueManager @Inject constructor(
    private val repo: DownloadRepository,
    private val engine: DownloadEngine,
    private val settings: SettingsRepository
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
                repo.observeByStatuses(listOf(DownloadStatus.QUEUED)),
                repo.observeByStatuses(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.ANALYZING)),
                settings.flow
            ) { queued, active, snap ->
                Triple(queued, active, snap.maxParallel)
            }.collect { (queued, active, permits) ->
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
                engine.start(d.id)
            }
        }
    }
}

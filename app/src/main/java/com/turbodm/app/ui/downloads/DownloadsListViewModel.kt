package com.turbodm.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.download.DownloadController
import com.turbodm.app.download.SpeedTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsListViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val controller: DownloadController,
    speedTracker: SpeedTracker
) : ViewModel() {
    val downloads = repo.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** Per-id bytes-per-second. Re-emits only when the map actually changes. */
    val speedsById: StateFlow<Map<Long, Long>> = speedTracker.bps
        .map { it.toMap() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun pause(d: Download) { controller.pause(d.id) }
    fun resume(d: Download) { controller.resume(d.id) }
    fun cancel(d: Download) { controller.cancel(d.id) }
    // Route through the controller so the on-disk file is removed along with
    // the DB row. Bare repo.delete() would leave hundreds of MB orphaned.
    fun delete(d: Download) = viewModelScope.launch { controller.delete(d.id) }
}

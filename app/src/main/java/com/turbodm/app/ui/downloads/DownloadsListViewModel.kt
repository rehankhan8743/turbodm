package com.turbodm.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsListViewModel @Inject constructor(
    private val repo: DownloadRepository
) : ViewModel() {
    val downloads = repo.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun pause(d: Download) = viewModelScope.launch { repo.setStatus(d.id, DownloadStatus.PAUSED) }
    fun resume(d: Download) = viewModelScope.launch { repo.setStatus(d.id, DownloadStatus.QUEUED) }
    fun cancel(d: Download) = viewModelScope.launch {
        repo.setStatus(d.id, DownloadStatus.CANCELLED)
    }
    fun delete(d: Download) = viewModelScope.launch { repo.delete(d.id) }
}

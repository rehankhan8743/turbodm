package com.turbodm.app.ui.downloads

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turbodm.app.data.repo.TorrentRepository
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.download.DownloadController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MagnetFilePickerViewModel @Inject constructor(
    private val controller: DownloadController,
    private val torrentRepo: TorrentRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class FileRow(
        val index: Int,
        val path: String,
        val size: Long,
        val selected: Boolean
    )

    data class UiState(
        val torrentId: Long,
        val name: String = "",
        val totalBytes: Long = 0L,
        val files: List<FileRow> = emptyList(),
        val status: DownloadStatus = DownloadStatus.ANALYZING,
        val errorMessage: String? = null,
        val isStarting: Boolean = false,
        val finished: Boolean = false
    ) {
        val selectedCount: Int get() = files.count { it.selected }
    }

    private val torrentId: Long = checkNotNull(savedStateHandle.get<Long>("torrentId")) {
        "MagnetFilePickerViewModel requires a torrentId argument"
    }
    private val selectedOverrides = MutableStateFlow<Map<Int, Boolean>>(emptyMap())

    private val _state = MutableStateFlow(UiState(torrentId))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                torrentRepo.observeById(torrentId),
                torrentRepo.observeFiles(torrentId),
                selectedOverrides
            ) { torrent, files, overrides ->
                val rows = files.map { f ->
                    FileRow(
                        index = f.index,
                        path = f.path,
                        size = f.size,
                        selected = overrides[f.index] ?: f.selected
                    )
                }
                UiState(
                    torrentId = torrentId,
                    name = torrent?.name ?: "",
                    totalBytes = torrent?.totalBytes ?: 0L,
                    files = rows,
                    status = torrent?.status ?: DownloadStatus.ANALYZING,
                    errorMessage = torrent?.errorMessage,
                    isStarting = _state.value.isStarting,
                    finished = _state.value.finished
                )
            }.collect { _state.value = it }
        }
    }

    fun toggleFile(index: Int) {
        val current = state.value.files.firstOrNull { it.index == index } ?: return
        selectedOverrides.value = selectedOverrides.value + (index to !current.selected)
    }

    fun selectAll() {
        selectedOverrides.value = state.value.files.associate { it.index to true }
    }

    fun selectNone() {
        selectedOverrides.value = state.value.files.associate { it.index to false }
    }

    fun start() {
        val selected = state.value.files.filter { it.selected }.map { it.index }.toSet()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isStarting = true, errorMessage = null)
            try {
                controller.startTorrent(torrentId, selected)
                _state.value = _state.value.copy(isStarting = false, finished = true)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isStarting = false,
                    errorMessage = t.message ?: "Failed to start torrent"
                )
            }
        }
    }
}

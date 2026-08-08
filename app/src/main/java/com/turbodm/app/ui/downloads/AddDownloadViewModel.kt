package com.turbodm.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turbodm.app.download.DownloadController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddDownloadViewModel @Inject constructor(
    private val controller: DownloadController
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val expectedSha256: String = "",
        val isSubmitting: Boolean = false,
        val error: String? = null,
        val finished: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setUrl(s: String) { _state.value = _state.value.copy(url = s, error = null) }
    fun setExpectedSha256(s: String) { _state.value = _state.value.copy(expectedSha256 = s) }

    fun submit() {
        val url = _state.value.url.trim()
        val hash = _state.value.expectedSha256.trim().takeIf { it.isNotBlank() }
        if (url.isBlank()) {
            _state.value = _state.value.copy(error = "Enter a URL")
            return
        }
        if (hash != null && !isPlausibleSha256(hash)) {
            _state.value = _state.value.copy(error = "SHA-256 must be 64 hex characters")
            return
        }
        _state.value = _state.value.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            try {
                controller.addAndStart(url, expectedSha256 = hash)
                _state.value = _state.value.copy(isSubmitting = false, finished = true)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    error = t.message ?: "Failed to start download"
                )
            }
        }
    }

    private fun isPlausibleSha256(s: String): Boolean =
        s.length == 64 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

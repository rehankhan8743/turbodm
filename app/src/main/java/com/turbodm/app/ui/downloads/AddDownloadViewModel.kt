package com.turbodm.app.ui.downloads

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turbodm.app.download.DownloadController
import com.turbodm.app.download.SchemeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddDownloadViewModel @Inject constructor(
    private val controller: DownloadController,
    private val schemeRegistry: SchemeRegistry
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val expectedSha256: String = "",
        /** Hour-of-day to schedule the download (0..23), or null for immediate. */
        val scheduledHour: Int? = null,
        val scheduledMinute: Int = 0,
        /** For streaming sites (YouTube, TikTok, Insta…): download only the
         *  audio track. Otherwise the best video is picked. */
        val audioOnly: Boolean = false,
        val isSubmitting: Boolean = false,
        val error: String? = null,
        val finished: Boolean = false,
        val pendingMagnetId: Long? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setUrl(s: String) { _state.value = _state.value.copy(url = s, error = null) }
    fun setExpectedSha256(s: String) { _state.value = _state.value.copy(expectedSha256 = s) }

    /** Toggles scheduling on/off and adjusts time. hour == null means disabled. */
    fun setSchedule(hour: Int?, minute: Int) {
        _state.value = _state.value.copy(
            scheduledHour = hour?.coerceIn(0, 23),
            scheduledMinute = minute.coerceIn(0, 59)
        )
    }

    fun setAudioOnly(v: Boolean) { _state.value = _state.value.copy(audioOnly = v) }

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
        val scheme = SchemeRegistry.extractScheme(url)
        if (scheme == "magnet") {
            _state.value = _state.value.copy(isSubmitting = true, error = null)
            viewModelScope.launch {
                try {
                    val id = controller.addMagnet(url)
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        // Don't set finished=true — the screen routes to the file
                        // picker next; finished is reserved for one-shot adds.
                        pendingMagnetId = id
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "addMagnet failed for $url", t)
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        error = friendlyError(t, "Failed to fetch torrent metadata")
                    )
                }
            }
            return
        }
        _state.value = _state.value.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            try {
                // Compute the scheduled start time, if any. Picker gives us
                // an hour-of-day; anchor it to the *next* occurrence of that
                // hour so "22:00" today at 22:30 becomes tomorrow at 22:00.
                val scheduleAt = _state.value.scheduledHour?.let { hour ->
                    val now = java.util.Calendar.getInstance()
                    val target = (now.clone() as java.util.Calendar).apply {
                        set(java.util.Calendar.HOUR_OF_DAY, hour)
                        set(java.util.Calendar.MINUTE, _state.value.scheduledMinute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    if (target.timeInMillis <= now.timeInMillis) {
                        target.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                    target.timeInMillis
                } ?: 0L
                controller.addAndStart(
                    url,
                    expectedSha256 = hash,
                    scheduleAtEpochMs = scheduleAt,
                    preferAudioOnly = _state.value.audioOnly
                )
                _state.value = _state.value.copy(isSubmitting = false, finished = true)
            } catch (t: Throwable) {
                Log.e(TAG, "addAndStart failed for $url", t)
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    error = friendlyError(t, "Failed to start download")
                )
            }
        }
    }

    /**
     * Renders a throwable into a user-facing string. Prefers the exception's
     * own message; falls back to "ClassName: <fallback>" so the user gets
     * something more diagnostic than a literal short string. The full stack
     * trace is logged separately so `adb logcat -s TurboDM` exposes the cause.
     */
    private fun friendlyError(t: Throwable, fallback: String): String {
        val msg = t.message
        return if (!msg.isNullOrBlank()) msg else "${t.javaClass.simpleName}: $fallback"
    }

    /** Acknowledge navigation so the screen doesn't loop. */
    fun onMagnetNavigated() {
        _state.value = _state.value.copy(pendingMagnetId = null, finished = true)
    }

    private fun isPlausibleSha256(s: String): Boolean =
        s.length == 64 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    private companion object {
        const val TAG = "TurboDM"
    }
}

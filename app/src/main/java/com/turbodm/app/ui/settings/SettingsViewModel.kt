package com.turbodm.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turbodm.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository
) : ViewModel() {
    val state = repo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.Snapshot()
    )

    fun setMaxParallel(v: Int) = viewModelScope.launch { repo.setMaxParallel(v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { repo.setWifiOnly(v) }
    fun setSpeedLimitBps(v: Long) = viewModelScope.launch { repo.setSpeedLimit(v) }
    fun setSegments(v: Int) = viewModelScope.launch { repo.setDefaultSegments(v) }
    fun setUserAgent(v: String) = viewModelScope.launch { repo.setUserAgent(v) }
    fun setDownloadDir(v: String) = viewModelScope.launch { repo.setDownloadDir(v) }
}

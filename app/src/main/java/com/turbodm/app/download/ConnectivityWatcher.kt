package com.turbodm.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
import com.turbodm.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the network and pauses/resumes downloads based on [SettingsRepository.wifiOnly].
 *
 * Pause vs resume are tagged with [PauseReason.NETWORK] so:
 *   - the watcher can auto-resume only what it paused
 *   - user pauses (PauseReason.USER) are never overridden
 *
 * Reacts to two triggers:
 *   1. network changes (gain/loss) — immediate pause/resume
 *   2. settings change (user toggles wifiOnly on/off) — apply current state
 *
 * The `block` semantics: when [SettingsRepository.wifiOnly] is true, a network is
 * "allowed" only if it has TRANSPORT_WIFI. Otherwise, every network is allowed.
 */
@Singleton
class ConnectivityWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val settings: SettingsRepository,
    private val controller: DownloadController
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true

        // React to settings changes (user toggles wifiOnly).
        scope.launch {
            settings.flow
                .map { it.wifiOnly }
                .distinctUntilChanged()
                .collect { reevaluateActiveDownloads() }
        }

        // React to network changes.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { reevaluateActiveDownloads() }
            }
            override fun onLost(network: Network) {
                scope.launch { reevaluateActiveDownloads() }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                scope.launch { reevaluateActiveDownloads() }
            }
        })
    }

    /** Re-decides pause/resume for every active download. */
    private suspend fun reevaluateActiveDownloads() {
        val snap = settings.flow.first()
        val allowed = isNetworkAllowed(snap.wifiOnly)
        if (allowed) {
            // Resume anything we previously paused for network reasons.
            val ids = repo.networkPausedIds()
            for (id in ids) {
                controller.resume(id, PauseReason.NONE) // reset reason so we don't re-resume on next drop
            }
        } else {
            // Pause anything currently active (DOWNLOADING / ANALYZING / QUEUED).
            val active = repo.observeActive().first()
                .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.ANALYZING || it.status == DownloadStatus.QUEUED }
            for (d in active) {
                controller.pause(d.id, PauseReason.NETWORK)
            }
        }
    }

    private fun isNetworkAllowed(wifiOnly: Boolean): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (wifiOnly && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        return true
    }
}

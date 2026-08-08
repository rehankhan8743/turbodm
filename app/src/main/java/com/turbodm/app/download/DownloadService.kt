package com.turbodm.app.download

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.turbodm.app.R
import com.turbodm.app.TurboDMApp
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.DownloadStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the OS from killing active transfers.
 * The real per-download work happens in [DownloadEngine]; the service just
 * holds the foreground state and exposes start/pause/resume/cancel actions.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var controller: DownloadController
    @Inject lateinit var notifier: DownloadNotifier
    @Inject lateinit var repo: DownloadRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        notifier.observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(notifier.showServiceNotification(getString(R.string.service_preparing)))
        when (intent?.action) {
            ACTION_START -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { controller.resume(it) }
            ACTION_PAUSE -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { controller.pause(it) }
            ACTION_CANCEL -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { controller.cancel(it) }
            ACTION_ADD_URL -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotBlank()) {
                    scope.launch { controller.addAndStart(url) }
                }
            }
        }
        // Re-evaluate foreground eligibility after a short delay: only stop when nothing is active.
        scope.launch {
            kotlinx.coroutines.delay(1500)
            val active = repo.observeActive().first().any { it.status.isActive }
            if (!active) stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, DownloadNotifier.SERVICE_NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DownloadNotifier.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        const val ACTION_START = "com.turbodm.START"
        const val ACTION_PAUSE = "com.turbodm.PAUSE"
        const val ACTION_CANCEL = "com.turbodm.CANCEL"
        const val ACTION_ADD_URL = "com.turbodm.ADD_URL"
        const val EXTRA_ID = "id"
        const val EXTRA_URL = "url"

        fun startUrl(context: android.content.Context, url: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_ADD_URL)
                    .putExtra(EXTRA_URL, url)
            )
        }

        fun pause(context: android.content.Context, id: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_PAUSE)
                    .putExtra(EXTRA_ID, id)
            )
        }

        fun cancel(context: android.content.Context, id: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_ID, id)
            )
        }
    }
}

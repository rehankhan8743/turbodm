package com.turbodm.app.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.turbodm.app.R
import com.turbodm.app.TurboDMApp
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val engine: DownloadEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
        as android.app.NotificationManager
    // Last time we posted a notification for a given id — throttle to avoid
    // spamming the shade on fast connections and starving the main thread.
    private val lastNotifiedAt = ConcurrentHashMap<Long, Long>()

    fun observe() {
        scope.launch {
            engine.events.conflate().collectLatest { evt ->
                val now = System.currentTimeMillis()
                val last = lastNotifiedAt[evt.id] ?: 0L
                if (now - last < MIN_NOTIFY_INTERVAL_MS) return@collectLatest
                lastNotifiedAt[evt.id] = now
                val d = repo.get(evt.id) ?: return@collectLatest
                nm.notify(evt.id.toInt(), buildProgress(d.fileName, evt.downloaded, evt.total, evt.bps, evt.id))
            }
        }
        scope.launch {
            repo.observeActive().collectLatest { active ->
                val activeIds = active.map { it.id }.toSet()
                // Drop stale throttle entries so we don't grow the map forever.
                lastNotifiedAt.keys.retainAll(activeIds)
                if (active.isEmpty() && engine.running.value.isEmpty()) {
                    nm.cancel(SERVICE_NOTIFICATION_ID)
                }
            }
        }
    }

    fun showServiceNotification(text: String): Notification = build(text).also {
        nm.notify(SERVICE_NOTIFICATION_ID, it)
    }

    private fun buildProgress(name: String, downloaded: Long, total: Long, bps: Long, id: Long): Notification {
        val max = if (total > 0) total.toInt() else 100
        val prog = if (total > 0) downloaded.toInt() else 0
        val speedSuffix = if (bps > 0) "  ·  ${formatRate(bps)}" else ""
        return build(
            "$name — ${formatBytes(downloaded)} / ${formatBytes(total)}$speedSuffix",
            prog, max, id
        )
    }

    private fun build(text: String, progress: Int = 0, max: Int = 0, id: Long? = null): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = id?.let {
            PendingIntent.getService(
                context, it.toInt(),
                Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL)
                    .putExtra(DownloadService.EXTRA_ID, it),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val builder = NotificationCompat.Builder(context, TurboDMApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (max > 0) builder.setProgress(max, progress, false)
        if (cancelIntent != null) {
            builder.addAction(0, context.getString(R.string.action_cancel), cancelIntent)
        }
        return builder.build()
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024L * 1024L -> "%.1f MB".format(b / 1024.0 / 1024.0)
        else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
    }

    private fun formatRate(bps: Long): String = when {
        bps < 1024 -> "$bps B/s"
        bps < 1024 * 1024 -> "%.0f KB/s".format(bps / 1024.0)
        else -> "%.1f MB/s".format(bps / 1024.0 / 1024.0)
    }

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1
        private const val MIN_NOTIFY_INTERVAL_MS = 750L
    }
}

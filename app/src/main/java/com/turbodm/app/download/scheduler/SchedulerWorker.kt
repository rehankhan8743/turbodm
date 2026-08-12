package com.turbodm.app.download.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.turbodm.app.data.repo.DownloadRepository
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.download.DownloadEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that fires when a scheduled download's time is due.
 *
 * Scheduled downloads sit in [com.turbodm.app.domain.model.DownloadStatus.SCHEDULED]
 * with [com.turbodm.app.domain.model.Download.scheduledForEpochMs] set. When the
 * wall clock passes that timestamp, WorkManager's initial-delay fires us awake
 * and we flip the row back to QUEUED. The normal [com.turbodm.app.download.QueueManager]
 * then picks it up — this worker does NOT run the transfer itself.
 *
 * NetworkType constraint: if the download was scheduled with "Wi-Fi only" in
 * settings, the WorkRequest was created with NetworkType.UNMETERED so the
 * worker doesn't even wake until Wi-Fi is available.
 */
@HiltWorker
class SchedulerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: DownloadRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id <= 0) return Result.failure()
        val d = repo.get(id) ?: return Result.failure()
        // Only flip rows that are still in SCHEDULED; a user may have cancelled
        // or started the row manually while we waited.
        if (d.status != DownloadStatus.SCHEDULED) return Result.success()
        repo.setStatus(id, DownloadStatus.QUEUED)
        return Result.success()
    }

    companion object {
        private const val KEY_DOWNLOAD_ID = "downloadId"
        private const val UNIQUE_PREFIX = "turbodm.schedule."

        /** Schedules a one-shot worker for [downloadId] at [atEpochMs]. */
        fun schedule(context: Context, downloadId: Long, atEpochMs: Long, wifiOnly: Boolean) {
            val delayMs = (atEpochMs - System.currentTimeMillis()).coerceAtLeast(0)
            val data = Data.Builder().putLong(KEY_DOWNLOAD_ID, downloadId).build()
            var builder = OneTimeWorkRequestBuilder<SchedulerWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(UNIQUE_PREFIX + downloadId)
            if (wifiOnly) {
                builder = builder.setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + downloadId,
                ExistingWorkPolicy.REPLACE,
                builder.build()
            )
        }

        /** Cancels a pending schedule, e.g. when the user resumes the row manually. */
        fun cancel(context: Context, downloadId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + downloadId)
        }
    }
}

package com.turbodm.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason

@Entity(
    tableName = "downloads",
    indices = [Index("status"), Index("createdAt")]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val targetPath: String,
    val supportsRange: Boolean,
    val segments: Int,
    val priority: Int,
    val referer: String?,
    val userAgent: String?,
    val cookies: String?,
    @ColumnInfo(defaultValue = "NULL") val expectedSha256: String? = null,
    @ColumnInfo(defaultValue = "NULL") val computedSha256: String? = null,
    @ColumnInfo(defaultValue = "NONE") val pauseReason: PauseReason = PauseReason.NONE,
    @ColumnInfo(defaultValue = "0") val scheduledForEpochMs: Long = 0L,
    // Rules-engine subfolder. NULL = no rule matched; use the global targetPath.
    @ColumnInfo(defaultValue = "NULL") val categoryDir: String? = null,
    @ColumnInfo(defaultValue = "0") val preferAudioOnly: Boolean = false
) {
    fun toDomain() = Download(
        id, url, fileName, mimeType, totalBytes, downloadedBytes, status, errorMessage,
        createdAt, updatedAt, completedAt, targetPath, supportsRange, segments, priority,
        referer, userAgent, cookies, expectedSha256, computedSha256, pauseReason,
        // Domain order: pauseReason, preferAudioOnly, scheduledForEpochMs
        preferAudioOnly, scheduledForEpochMs
    )

    companion object {
        // Positional to keep parity with the entity constructor, whose order is:
        // …, pauseReason, scheduledForEpochMs, categoryDir, preferAudioOnly.
        // The domain model omits categoryDir (derived/internal state) so we
        // pass nil here.
        fun fromDomain(d: Download) = DownloadEntity(
            d.id, d.url, d.fileName, d.mimeType, d.totalBytes, d.downloadedBytes, d.status,
            d.errorMessage, d.createdAt, d.updatedAt, d.completedAt, d.targetPath,
            d.supportsRange, d.segments, d.priority, d.referer, d.userAgent, d.cookies,
            d.expectedSha256, d.computedSha256, d.pauseReason, d.scheduledForEpochMs,
            null /* categoryDir */, d.preferAudioOnly
        )
    }
}

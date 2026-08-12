package com.turbodm.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason

/**
 * Parent row for a BitTorrent download. One `magnet:` URI → one [TorrentEntity]
 * → N [TorrentFileEntity] children.
 *
 * Lives in a separate table from `downloads` because the lifecycle, progress
 * model, and payload differ (multi-file vs single-file). Reusing the
 * `DownloadStatus` enum keeps the UI palette and storage encoding unified; a
 * torrent in `ANALYZING` means "fetching metadata from swarm" — the same state
 * a regular HTTP download uses while `LinkAnalyzer` is running.
 *
 * `infoHash` is null until the swarm reports it; until then the row is keyed
 * only by `id`. We don't enforce a unique constraint on `magnet` because the
 * same link can be added twice (e.g. user wants to re-fetch with different
 * file selection).
 */
@Entity(
    tableName = "torrents",
    indices = [Index("status"), Index("createdAt")]
)
data class TorrentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val magnet: String,
    @ColumnInfo(defaultValue = "NULL") val infoHash: String? = null,
    val name: String,
    @ColumnInfo(defaultValue = "-1") val totalBytes: Long = -1L,
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0L,
    val status: DownloadStatus,
    @ColumnInfo(defaultValue = "NULL") val errorMessage: String? = null,
    val saveDir: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val completedAt: Long? = null,
    @ColumnInfo(defaultValue = "NONE") val pauseReason: PauseReason = PauseReason.NONE
)

package com.turbodm.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One file inside a [TorrentEntity]. The relationship is CASCADE — deleting
 * the parent torrent removes its file rows in the same transaction, so we
 * never leak orphan files.
 *
 * `selected` is the picker state — a file the user wants downloaded. Defaults
 * to true on insert (qBittorrent convention: easier to deselect a few than
 * pick through hundreds). The engine only fetches selected files.
 *
 * `priority` mirrors libtorrent's file_priority_t. Default 0 (normal).
 * Higher values download first; 0 means "skip if selected=false, else normal".
 */
@Entity(
    tableName = "torrent_files",
    indices = [Index("torrentId")],
    foreignKeys = [
        ForeignKey(
            entity = TorrentEntity::class,
            parentColumns = ["id"],
            childColumns = ["torrentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TorrentFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val torrentId: Long,
    val index: Int,
    val path: String,
    val size: Long,
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0L,
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(defaultValue = "1") val selected: Boolean = true
)

package com.turbodm.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.turbodm.app.domain.model.ChunkState

@Entity(
    tableName = "chunks",
    indices = [Index("downloadId")],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val downloadId: Long,
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long
) {
    fun toDomain() = ChunkState(id, downloadId, index, startByte, endByte, downloadedBytes)
    companion object {
        fun fromDomain(c: ChunkState) = ChunkEntity(c.id, c.downloadId, c.index, c.startByte, c.endByte, c.downloadedBytes)
    }
}

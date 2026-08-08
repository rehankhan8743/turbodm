package com.turbodm.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE downloadId = :downloadId ORDER BY `index` ASC")
    suspend fun forDownload(downloadId: Long): List<ChunkEntity>

    @Query("UPDATE chunks SET downloadedBytes = :bytes WHERE id = :id")
    suspend fun setDownloaded(id: Long, bytes: Long)

    @Query("DELETE FROM chunks WHERE downloadId = :id")
    suspend fun deleteForDownload(id: Long)
}

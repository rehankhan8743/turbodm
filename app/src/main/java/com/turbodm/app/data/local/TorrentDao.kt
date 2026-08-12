package com.turbodm.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
import kotlinx.coroutines.flow.Flow

@Dao
interface TorrentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TorrentEntity): Long

    @Query("DELETE FROM torrents WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM torrents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TorrentEntity>>

    @Query("SELECT * FROM torrents WHERE id = :id")
    suspend fun getById(id: Long): TorrentEntity?

    @Query("SELECT * FROM torrents WHERE id = :id")
    fun observeById(id: Long): Flow<TorrentEntity?>

    @Query("UPDATE torrents SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE torrents SET status = :status, pauseReason = :reason, updatedAt = :now WHERE id = :id")
    suspend fun setStatusWithReason(id: Long, status: DownloadStatus, reason: PauseReason, now: Long = System.currentTimeMillis())

    @Query("UPDATE torrents SET downloadedBytes = :bytes, updatedAt = :now WHERE id = :id")
    suspend fun setDownloadedBytes(id: Long, bytes: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE torrents SET infoHash = :hash, name = :name, totalBytes = :total, updatedAt = :now WHERE id = :id")
    suspend fun setMetadata(id: Long, hash: String, name: String, total: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE torrents SET errorMessage = :msg, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setError(id: Long, msg: String?, status: DownloadStatus, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE torrents SET status = 'COMPLETED', completedAt = :now, " +
            "downloadedBytes = totalBytes, updatedAt = :now WHERE id = :id"
    )
    suspend fun markCompleted(id: Long, now: Long = System.currentTimeMillis())
}

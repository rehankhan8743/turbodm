package com.turbodm.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY priority DESC, createdAt ASC")
    fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadEntity?>

    @Query("UPDATE downloads SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = :status, pauseReason = :reason, updatedAt = :now WHERE id = :id")
    suspend fun setStatusWithReason(id: Long, status: DownloadStatus, reason: PauseReason, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET downloadedBytes = :bytes, updatedAt = :now WHERE id = :id")
    suspend fun setDownloadedBytes(id: Long, bytes: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET totalBytes = :total, supportsRange = :supportsRange, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setMetadata(id: Long, total: Long, supportsRange: Boolean, status: DownloadStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET errorMessage = :msg, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setError(id: Long, msg: String?, status: DownloadStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'COMPLETED', completedAt = :now, downloadedBytes = totalBytes, updatedAt = :now WHERE id = :id")
    suspend fun markCompleted(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT id FROM downloads WHERE status = 'PAUSED' AND pauseReason = 'NETWORK'")
    suspend fun networkPausedIds(): List<Long>
}

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

    /**
     * Updates only the totalBytes field. Used by the engine when the size is
     * learned after the transfer completes (chunked transfer-encoding streams
     * where the server didn't send Content-Length up-front). The default
     * `markCompleted` reads totalBytes to set downloadedBytes, so leaving a
     * negative value would overwrite a real byte count with -1.
     */
    @Query("UPDATE downloads SET totalBytes = :total, updatedAt = :now WHERE id = :id")
    suspend fun setTotalBytes(id: Long, total: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET errorMessage = :msg, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setError(id: Long, msg: String?, status: DownloadStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'COMPLETED', completedAt = :now, downloadedBytes = totalBytes, updatedAt = :now WHERE id = :id")
    suspend fun markCompleted(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT id FROM downloads WHERE status = 'PAUSED' AND pauseReason = 'NETWORK'")
    suspend fun networkPausedIds(): List<Long>

    @Query("UPDATE downloads SET computedSha256 = :hash, updatedAt = :now WHERE id = :id")
    suspend fun setComputedSha256(id: Long, hash: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET url = :url, updatedAt = :now WHERE id = :id")
    suspend fun updateUrl(id: Long, url: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET fileName = :fileName, updatedAt = :now WHERE id = :id")
    suspend fun updateFileName(id: Long, fileName: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET targetPath = :targetPath, updatedAt = :now WHERE id = :id")
    suspend fun updateTargetPath(id: Long, targetPath: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET cookies = :cookies, updatedAt = :now WHERE id = :id")
    suspend fun setCookies(id: Long, cookies: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET referer = :referer, updatedAt = :now WHERE id = :id")
    suspend fun setReferer(id: Long, referer: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET supportsRange = :supportsRange, updatedAt = :now WHERE id = :id")
    suspend fun updateSupportsRange(id: Long, supportsRange: Boolean, now: Long = System.currentTimeMillis())
}

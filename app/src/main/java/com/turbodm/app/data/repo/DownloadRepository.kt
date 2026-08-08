package com.turbodm.app.data.repo

import com.turbodm.app.data.local.DownloadDao
import com.turbodm.app.data.local.DownloadEntity
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao
) {
    fun observeAll(): Flow<List<Download>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeActive(): Flow<List<Download>> = dao.observeByStatuses(
        listOf(DownloadStatus.QUEUED, DownloadStatus.ANALYZING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
    ).map { it.map(DownloadEntity::toDomain) }

    suspend fun get(id: Long): Download? = dao.getById(id)?.toDomain()

    suspend fun create(download: Download): Long =
        dao.insert(DownloadEntity.fromDomain(download.copy(id = 0L)))

    suspend fun setStatus(id: Long, status: DownloadStatus) = dao.setStatus(id, status)
    suspend fun setDownloaded(id: Long, bytes: Long) = dao.setDownloadedBytes(id, bytes)
    suspend fun setMetadata(id: Long, total: Long, supportsRange: Boolean, status: DownloadStatus) =
        dao.setMetadata(id, total, supportsRange, status)
    suspend fun setError(id: Long, msg: String?, status: DownloadStatus) = dao.setError(id, msg, status)
    suspend fun markCompleted(id: Long) = dao.markCompleted(id)
    suspend fun delete(id: Long) = dao.deleteById(id)
}

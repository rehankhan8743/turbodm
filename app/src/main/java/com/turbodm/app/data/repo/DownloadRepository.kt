package com.turbodm.app.data.repo

import com.turbodm.app.data.local.DownloadDao
import com.turbodm.app.data.local.DownloadEntity
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason
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

    fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<Download>> =
        dao.observeByStatuses(statuses).map { it.map(DownloadEntity::toDomain) }

    suspend fun get(id: Long): Download? = dao.getById(id)?.toDomain()

    suspend fun create(download: Download): Long =
        dao.insert(DownloadEntity.fromDomain(download.copy(id = 0L)))

    suspend fun setStatus(id: Long, status: DownloadStatus) = dao.setStatus(id, status)
    suspend fun setStatus(id: Long, status: DownloadStatus, reason: PauseReason) =
        dao.setStatusWithReason(id, status, reason)
    suspend fun setDownloaded(id: Long, bytes: Long) = dao.setDownloadedBytes(id, bytes)
    suspend fun setMetadata(id: Long, total: Long, supportsRange: Boolean, status: DownloadStatus) =
        dao.setMetadata(id, total, supportsRange, status)

    /**
     * Updates only the totalBytes field. Used when the engine finishes a
     * download whose size was unknown at probe time (streaming, chunked
     * transfer) and the actual size only becomes known after the body ends.
     */
    suspend fun setTotalBytes(id: Long, total: Long) = dao.setTotalBytes(id, total)
    suspend fun setError(id: Long, msg: String?, status: DownloadStatus) = dao.setError(id, msg, status)
    suspend fun markCompleted(id: Long) = dao.markCompleted(id)
    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun networkPausedIds(): List<Long> = dao.networkPausedIds()
    suspend fun setComputedSha256(id: Long, hash: String) = dao.setComputedSha256(id, hash)

    /** Re-target a download in-place. Used by the streaming handler to swap a
     *  YouTube URL for its resolved direct-media URL before the HTTP engine fetches. */
    suspend fun updateUrl(id: Long, url: String) = dao.updateUrl(id, url)

    /** Re-name a download row in-place. Used by the streaming handler after
     *  resolving the stream — the chosen file name depends on whether audio
     *  or video was picked, and we don't know that at probe time. */
    suspend fun updateFileName(id: Long, fileName: String) = dao.updateFileName(id, fileName)

    /** Move the on-disk target path. Used by the streaming handler after the
     *  finalized filename (with real container extension) is known. */
    suspend fun updateTargetPath(id: Long, targetPath: String) = dao.updateTargetPath(id, targetPath)

    /** Toggle supportsRange on a row. Used by scraper handlers (e.g. tiksave) that
     *  sit behind a redirect chain where intermediate proxies don't all honor Range. */
    suspend fun updateSupportsRange(id: Long, supportsRange: Boolean) =
        dao.updateSupportsRange(id, supportsRange)

    /** Pass cookies through to the engine for hosts that need them (TikTok,
     *  Instagram). Stored on the row so a resumed download re-uses the same
     *  session. */
    suspend fun setCookies(id: Long, cookies: String) = dao.setCookies(id, cookies)

    /** Same as [setCookies], for the Referer/Origin header. */
    suspend fun setReferer(id: Long, referer: String) = dao.setReferer(id, referer)
}

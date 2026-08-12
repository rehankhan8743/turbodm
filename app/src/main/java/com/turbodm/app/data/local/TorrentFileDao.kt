package com.turbodm.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TorrentFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<TorrentFileEntity>)

    @Query("SELECT * FROM torrent_files WHERE torrentId = :torrentId ORDER BY `index` ASC")
    suspend fun forTorrent(torrentId: Long): List<TorrentFileEntity>

    @Query("SELECT * FROM torrent_files WHERE torrentId = :torrentId ORDER BY `index` ASC")
    fun observeForTorrent(torrentId: Long): Flow<List<TorrentFileEntity>>

    @Query("UPDATE torrent_files SET selected = :selected WHERE torrentId = :torrentId AND `index` = :index")
    suspend fun setSelected(torrentId: Long, index: Int, selected: Boolean)

    @Query("UPDATE torrent_files SET downloadedBytes = :bytes WHERE torrentId = :torrentId AND `index` = :index")
    suspend fun setDownloaded(torrentId: Long, index: Int, bytes: Long)

    @Query("DELETE FROM torrent_files WHERE torrentId = :id")
    suspend fun deleteForTorrent(id: Long)

    @Query("SELECT * FROM torrent_files WHERE torrentId = :torrentId AND selected = 1")
    suspend fun selectedForTorrent(torrentId: Long): List<TorrentFileEntity>
}

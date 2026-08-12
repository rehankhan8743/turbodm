package com.turbodm.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [DownloadEntity::class, ChunkEntity::class, TorrentEntity::class, TorrentFileEntity::class],
    version = 7,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TurboDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun chunkDao(): ChunkDao
    abstract fun torrentDao(): TorrentDao
    abstract fun torrentFileDao(): TorrentFileDao

    companion object { const val NAME = "turbodm.db" }
}

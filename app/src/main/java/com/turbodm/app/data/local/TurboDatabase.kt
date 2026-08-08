package com.turbodm.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [DownloadEntity::class, ChunkEntity::class],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TurboDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun chunkDao(): ChunkDao

    companion object { const val NAME = "turbodm.db" }
}

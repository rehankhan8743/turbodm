package com.turbodm.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2:
 *   - The v1 schema already declared `chunks`, but the engine never wrote to it.
 *     This migration just guards the table exists and seeds the indexes.
 *   - Backfills `downloadedBytes` to 0 where legacy rows left it NULL.
 *   - Adds `segments` and `priority` defaults if missing (the entity had them but
 *     Room needs an explicit ALTER for pre-existing devices).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `chunks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `downloadId` INTEGER NOT NULL,
                `index` INTEGER NOT NULL,
                `startByte` INTEGER NOT NULL,
                `endByte` INTEGER NOT NULL,
                `downloadedBytes` INTEGER NOT NULL,
                FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunks_downloadId` ON `chunks` (`downloadId`)")

        // Pre-v2 rows may have NULL downloadedBytes; coerce to 0 so progress math is safe.
        db.execSQL("UPDATE `downloads` SET `downloadedBytes` = 0 WHERE `downloadedBytes` IS NULL")
        db.execSQL("UPDATE `downloads` SET `segments` = 1 WHERE `segments` IS NULL OR `segments` < 1")
        db.execSQL("UPDATE `downloads` SET `priority` = 0 WHERE `priority` IS NULL")
    }
}

/**
 * v2 → v3:
 *   - Adds `pauseReason` column to `downloads`. Default NONE so legacy rows
 *     behave as "not paused" and the connectivity watcher doesn't auto-resume them.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `pauseReason` TEXT NOT NULL DEFAULT 'NONE'")
    }
}

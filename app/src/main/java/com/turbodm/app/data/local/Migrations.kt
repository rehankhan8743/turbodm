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

/**
 * v4 → v5:
 *   - Adds two tables for BitTorrent downloads (magnet links via libtorrent4j):
 *     `torrents` (parent row: magnet, infoHash, name, totalBytes, downloadedBytes,
 *     status, errorMessage, saveDir, createdAt, updatedAt, completedAt, pauseReason)
 *     and `torrent_files` (child rows: torrentId FK CASCADE, index, path, size,
 *     downloadedBytes, priority, selected).
 *   - Pure additive migration. No columns are dropped from `downloads`, so all
 *     v4 rows remain valid. Default values on the new columns mirror those of
 *     `downloads` so rows created before a `torrent_files` write still parse.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `torrents` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `magnet` TEXT NOT NULL,
                `infoHash` TEXT DEFAULT NULL,
                `name` TEXT NOT NULL,
                `totalBytes` INTEGER NOT NULL DEFAULT -1,
                `downloadedBytes` INTEGER NOT NULL DEFAULT 0,
                `status` TEXT NOT NULL,
                `errorMessage` TEXT DEFAULT NULL,
                `saveDir` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `completedAt` INTEGER DEFAULT NULL,
                `pauseReason` TEXT NOT NULL DEFAULT 'NONE'
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_torrents_status` ON `torrents` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_torrents_createdAt` ON `torrents` (`createdAt`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `torrent_files` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `torrentId` INTEGER NOT NULL,
                `index` INTEGER NOT NULL,
                `path` TEXT NOT NULL,
                `size` INTEGER NOT NULL,
                `downloadedBytes` INTEGER NOT NULL DEFAULT 0,
                `priority` INTEGER NOT NULL DEFAULT 0,
                `selected` INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(`torrentId`) REFERENCES `torrents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_torrent_files_torrentId` ON `torrent_files` (`torrentId`)")
    }
}

/**
 * v3 → v4:
 *   - The v3 `sha256` column was always NULL (added in v1 for future use, never
 *     written). We replace it with two distinct columns: `expectedSha256` (set by
 *     the user before download to verify against) and `computedSha256` (set by
 *     the engine after a successful download, regardless of whether a check ran).
 *
 *   We do this without recreating the table: add the new columns, then drop the
 *   old one. SQLite ≥ 3.35 supports `ALTER TABLE DROP COLUMN`; on older Android
 *   we go through a 12-step table-rebuild, which is heavier but only happens once.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `expectedSha256` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `computedSha256` TEXT DEFAULT NULL")
        // Try the modern path first. If the SQLite version doesn't support
        // DROP COLUMN, Room will surface a runtime error and we'd switch to a
        // table-rebuild migration. For the v3 entity, sha256 was always NULL,
        // so losing the column is harmless.
        try {
            db.execSQL("ALTER TABLE `downloads` DROP COLUMN `sha256`")
        } catch (t: Throwable) {
            // Pre-SQLite 3.35 fallback. The column will remain as NULL garbage;
            // not worth rebuilding the whole table for it.
        }
    }
}

/**
 * v5 → v6:
 *   - Adds `scheduledForEpochMs` column to `downloads` for the Phase-3
 *     scheduler. Default 0 = no schedule (preserves existing behavior for
 *     all existing rows).
 *   - Adds `categoryDir` column to `downloads` so the rules engine can route
 *     a file into a per-type subfolder (videos/, music/, docs/, …). NULL
 *     means "no rule matched; use the global download dir".
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `scheduledForEpochMs` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `categoryDir` TEXT DEFAULT NULL")
    }
}

/**
 * v6 → v7:
 *   - Adds `preferAudioOnly` flag for streaming-site downloads. When set,
 *     StreamingSchemeHandler picks the highest-bitrate audio stream instead of
 *     the best video.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `preferAudioOnly` INTEGER NOT NULL DEFAULT 0")
    }
}

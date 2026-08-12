package com.turbodm.app.download.torrent

/**
 * Engine-side, framework-free types for the torrent pipeline. These are
 * decoupled from Room (`TorrentEntity` / `TorrentFileEntity`) so the
 * [TorrentClient] interface stays free of database concerns — the engine maps
 * between the two layers.
 *
 * - [TorrentInfo] is the result of a metadata fetch: one torrent, many files.
 * - [TorrentFileMeta] is a single file inside [TorrentInfo]. `index` is the
 *   libtorrent file index (stable across the lifetime of the session).
 * - [TorrentStatusUpdate] is a progress event emitted by the client.
 */
data class TorrentInfo(
    val name: String,
    val infoHash: String,
    val totalBytes: Long,
    val files: List<TorrentFileMeta>
)

data class TorrentFileMeta(
    val index: Int,
    val path: String,
    val size: Long
)

/**
 * One progress sample for a torrent. `bytesPerSecond` is the swarm's recent
 * download rate — the engine funnels this into [com.turbodm.app.download.SpeedTracker]
 * so the UI sees it alongside HTTP downloads.
 *
 * `fileBytes` is keyed by file `index` and reports downloaded bytes per file
 * (not just totals). The engine writes per-file progress into the
 * `torrent_files` table.
 */
data class TorrentStatusUpdate(
    val torrentId: Long,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val bytesPerSecond: Long,
    val state: TorrentState,
    val fileBytes: Map<Int, Long> = emptyMap(),
    val errorMessage: String? = null
)

enum class TorrentState {
    /** Metadata fetch is in progress; no byte traffic yet. */
    METADATA,
    /** Connecting to peers, no bytes yet. */
    CONNECTING,
    /** Actively downloading. */
    DOWNLOADING,
    /** All selected files complete. */
    FINISHED,
    /** User paused. */
    PAUSED,
    /** Stopped with an error. */
    ERROR
}
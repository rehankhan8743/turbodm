package com.turbodm.app.download.torrent

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the libtorrent4j binding. Real implementations land in
 * `LibtorrentClient.kt` once the native AAR is added. Today, [FakeTorrentClient]
 * drives the same flow against in-memory fake data so the rest of the app
 * (engine, repository, UI) compiles and tests without the native dep.
 *
 * Contract:
 * - [fetchMetadata] resolves the magnet to a [TorrentInfo]. May take seconds
 *   (DHT/PEX lookup). The fake returns immediately. The real impl returns null
 *   on timeout (the caller decides how long to wait).
 * - [addTorrent] hands the file list + priorities to the session and returns
 *   a stable client-side handle id (libtorrent uses the info hash; the fake
 *   uses the torrentId). The engine stores this so [observeStatus] can target
 *   the right torrent.
 * - [observeStatus] is a hot stream of progress samples for one torrent. The
 *   engine collects it and writes progress to the DB + SpeedTracker.
 * - [pause] / [resume] / [remove] are best-effort lifecycle. They don't throw
 *   if the torrent is already in the target state.
 *
 * Thread safety: implementations must be safe to call from any coroutine.
 */
interface TorrentClient {
    /**
     * Resolves a magnet link's metadata without committing to a download.
     * Returns null if metadata cannot be fetched within the caller's timeout.
     */
    suspend fun fetchMetadata(magnet: String): TorrentInfo?

    /**
     * Adds the torrent to the session. `saveDir` is the directory under which
     * files will be written. `selectedIndices` controls which files the swarm
     * fetches (unselected files get priority 0 in libtorrent).
     *
     * Returns a client-side handle id used by [observeStatus]. May throw if
     * the magnet is malformed or the session is full.
     */
    suspend fun addTorrent(
        torrentId: Long,
        magnet: String,
        saveDir: String,
        selectedIndices: List<Int>
    ): String

    /** Hot stream of progress for one torrent. Completes when the torrent is removed. */
    fun observeStatus(clientHandle: String): Flow<TorrentStatusUpdate>

    suspend fun pause(clientHandle: String)
    suspend fun resume(clientHandle: String)
    suspend fun remove(clientHandle: String)
}
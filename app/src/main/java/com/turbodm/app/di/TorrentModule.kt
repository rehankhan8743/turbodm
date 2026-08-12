package com.turbodm.app.di

import com.turbodm.app.download.torrent.FakeTorrentClient
import com.turbodm.app.download.torrent.TorrentClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 6 wires the torrent subsystem against a [FakeTorrentClient] so the rest
 * of the app can be built and tested without the libtorrent4j native binary.
 * When the real AAR is added, this module will swap to `LibtorrentClient` and
 * the rest of the codebase is unaffected.
 */
@Module
@InstallIn(SingletonComponent::class)
object TorrentModule {
    @Provides @Singleton fun provideTorrentClient(): TorrentClient = FakeTorrentClient()
}

package com.turbodm.app.di

import com.turbodm.app.download.RetryPolicy
import com.turbodm.app.download.SchemeHandler
import com.turbodm.app.download.SpeedTracker
import com.turbodm.app.download.handlers.ContentSchemeHandler
import com.turbodm.app.download.handlers.FileSchemeHandler
import com.turbodm.app.download.handlers.FtpSchemeHandler
import com.turbodm.app.download.handlers.HttpSchemeHandler
import com.turbodm.app.download.handlers.MagnetSchemeHandler
import com.turbodm.app.download.handlers.HlsSchemeHandler
import com.turbodm.app.download.handlers.StreamingSchemeHandler
import com.turbodm.app.download.handlers.TikTokSchemeHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides @Singleton fun provideRetryPolicy(): RetryPolicy = RetryPolicy()
    @Provides @Singleton fun provideSpeedTracker(): SpeedTracker = SpeedTracker()

    // Scheme handlers are aggregated into a Set<SchemeHandler> for the registry.
    // Each handler declares its own schemes; the registry routes URLs by scheme.
    @Provides @IntoSet fun bindHttpSchemeHandler(h: HttpSchemeHandler): SchemeHandler = h
    @Provides @IntoSet fun bindFileSchemeHandler(h: FileSchemeHandler): SchemeHandler = h
    @Provides @IntoSet fun bindContentSchemeHandler(h: ContentSchemeHandler): SchemeHandler = h
    @Provides @IntoSet fun bindFtpSchemeHandler(h: FtpSchemeHandler): SchemeHandler = h
    @Provides @IntoSet fun bindStreamingSchemeHandler(h: StreamingSchemeHandler): SchemeHandler = h
    @Provides @IntoSet fun bindTikTokSchemeHandler(h: TikTokSchemeHandler): SchemeHandler = h
    @Provides @IntoSet fun bindHlsSchemeHandler(h: HlsSchemeHandler): SchemeHandler = h
    @Provides @IntoSet fun bindMagnetSchemeHandler(): SchemeHandler = MagnetSchemeHandler()
}

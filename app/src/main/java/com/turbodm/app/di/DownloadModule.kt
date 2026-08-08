package com.turbodm.app.di

import com.turbodm.app.download.RetryPolicy
import com.turbodm.app.download.SpeedTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides @Singleton fun provideRetryPolicy(): RetryPolicy = RetryPolicy()
    @Provides @Singleton fun provideSpeedTracker(): SpeedTracker = SpeedTracker()
}

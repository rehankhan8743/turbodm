package com.turbodm.app.di

import android.content.Context
import androidx.room.Room
import com.turbodm.app.data.local.ChunkDao
import com.turbodm.app.data.local.DownloadDao
import com.turbodm.app.data.local.MIGRATION_1_2
import com.turbodm.app.data.local.TurboDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TurboDatabase =
        Room.databaseBuilder(context, TurboDatabase::class.java, TurboDatabase.NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideDownloadDao(db: TurboDatabase): DownloadDao = db.downloadDao()
    @Provides fun provideChunkDao(db: TurboDatabase): ChunkDao = db.chunkDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // streaming-friendly
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(log)
            .build()
    }
}

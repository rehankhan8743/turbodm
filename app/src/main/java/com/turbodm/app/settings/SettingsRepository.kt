package com.turbodm.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "turbodm_settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val DOWNLOAD_DIR = stringPreferencesKey("download_dir")
        val MAX_PARALLEL = intPreferencesKey("max_parallel")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val SPEED_LIMIT_BPS = longPreferencesKey("speed_limit_bps")
        val DEFAULT_SEGMENTS = intPreferencesKey("default_segments")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val STREAMING_ENABLED = booleanPreferencesKey("streaming_enabled")
        val MAGNET_ENABLED = booleanPreferencesKey("magnet_enabled")
        val RULES_ENGINE_ENABLED = booleanPreferencesKey("rules_engine_enabled")
    }

    data class Snapshot(
        val downloadDir: String = defaultDir(),
        val maxParallel: Int = 3,
        val wifiOnly: Boolean = false,
        val speedLimitBps: Long = 0L,
        val defaultSegments: Int = 4,
        val userAgent: String = DEFAULT_UA,
        val streamingEnabled: Boolean = true,
        val magnetEnabled: Boolean = true,
        // Rules engine: auto-categorize downloads into per-type subfolders
        // (videos/, music/, images/, docs/, packages/, other/) under downloadDir.
        val rulesEngineEnabled: Boolean = true
    )

    val flow: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            downloadDir = p[Keys.DOWNLOAD_DIR] ?: defaultDir(),
            maxParallel = p[Keys.MAX_PARALLEL] ?: 3,
            wifiOnly = p[Keys.WIFI_ONLY] ?: false,
            speedLimitBps = p[Keys.SPEED_LIMIT_BPS] ?: 0L,
            defaultSegments = p[Keys.DEFAULT_SEGMENTS] ?: 4,
            userAgent = p[Keys.USER_AGENT] ?: DEFAULT_UA,
            streamingEnabled = p[Keys.STREAMING_ENABLED] ?: true,
            magnetEnabled = p[Keys.MAGNET_ENABLED] ?: true,
            rulesEngineEnabled = p[Keys.RULES_ENGINE_ENABLED] ?: true
        )
    }

    suspend fun setMaxParallel(value: Int) = context.dataStore.edit { it[Keys.MAX_PARALLEL] = value }
    suspend fun setWifiOnly(value: Boolean) = context.dataStore.edit { it[Keys.WIFI_ONLY] = value }
    suspend fun setSpeedLimit(bps: Long) = context.dataStore.edit { it[Keys.SPEED_LIMIT_BPS] = bps }
    suspend fun setDefaultSegments(value: Int) = context.dataStore.edit { it[Keys.DEFAULT_SEGMENTS] = value }
    suspend fun setUserAgent(ua: String) = context.dataStore.edit { it[Keys.USER_AGENT] = ua }
    suspend fun setDownloadDir(path: String) = context.dataStore.edit { it[Keys.DOWNLOAD_DIR] = path }
    suspend fun setStreamingEnabled(on: Boolean) = context.dataStore.edit { it[Keys.STREAMING_ENABLED] = on }
    suspend fun setMagnetEnabled(on: Boolean) = context.dataStore.edit { it[Keys.MAGNET_ENABLED] = on }
    suspend fun setRulesEngineEnabled(on: Boolean) = context.dataStore.edit { it[Keys.RULES_ENGINE_ENABLED] = on }

    companion object {
        const val DEFAULT_UA = "TurboDM/0.1 (Android)"
        private fun defaultDir() = "/storage/emulated/0/Download/TurboDM"
    }
}

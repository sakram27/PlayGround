package com.freqtrade.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsStore by preferencesDataStore(name = "freqtrade_settings")

data class ServerConfig(
    val baseUrl: String = "http://192.168.1.10:8080",
    val username: String = "freqtrader",
    val password: String = "",
)

class SettingsRepository(private val context: Context) {
    private val KEY_URL = stringPreferencesKey("base_url")
    private val KEY_USER = stringPreferencesKey("username")
    private val KEY_PASS = stringPreferencesKey("password")

    val configFlow: Flow<ServerConfig> = context.settingsStore.data.map { p ->
        ServerConfig(
            baseUrl = p[KEY_URL] ?: "http://192.168.1.10:8080",
            username = p[KEY_USER] ?: "freqtrader",
            password = p[KEY_PASS] ?: "",
        )
    }

    suspend fun save(cfg: ServerConfig) {
        context.settingsStore.edit { e ->
            e[KEY_URL] = cfg.baseUrl.trim().trimEnd('/')
            e[KEY_USER] = cfg.username
            e[KEY_PASS] = cfg.password
        }
    }
}

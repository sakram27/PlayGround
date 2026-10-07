package com.freqdroid.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private val Context.dataStore by preferencesDataStore("freqdroid")

object SettingsKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val USERNAME = stringPreferencesKey("username")
    val PASSWORD = stringPreferencesKey("password")
    val STAKE = stringPreferencesKey("stake")
    val DRY_RUN = booleanPreferencesKey("dry_run")
}

class SettingsStore(private val ctx: Context) {
    suspend fun save(url: String, user: String, pass: String, stake: String, dryRun: Boolean) {
        ctx.dataStore.edit {
            it[SettingsKeys.SERVER_URL] = url.trim().trimEnd('/')
            it[SettingsKeys.USERNAME] = user
            it[SettingsKeys.PASSWORD] = pass
            it[SettingsKeys.STAKE] = stake
            it[SettingsKeys.DRY_RUN] = dryRun
        }
    }
    suspend fun load(): Map<String, Any> {
        val p = ctx.dataStore.data.map { it }.first()
        return mapOf(
            "url" to (p[SettingsKeys.SERVER_URL] ?: ""),
            "username" to (p[SettingsKeys.USERNAME] ?: ""),
            "password" to (p[SettingsKeys.PASSWORD] ?: ""),
            "stake" to (p[SettingsKeys.STAKE] ?: "USDT"),
            "dryRun" to (p[SettingsKeys.DRY_RUN] ?: true)
        )
    }
}

object ApiFactory {
    private fun client(): OkHttpClient {
        val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder().addInterceptor(log).build()
    }

    fun freqtrade(baseUrl: String): FreqtradeApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client())
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(FreqtradeApi::class.java)
    }

    fun binance(): BinanceApi {
        return Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .client(client())
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(BinanceApi::class.java)
    }
}

fun parseKlines(raw: List<List<Any>>): List<Candle> {
    return raw.mapNotNull {
        try {
            Candle(
                openTime = (it[0] as Number).toLong(),
                open = (it[1] as String).toDouble(),
                high = (it[2] as String).toDouble(),
                low = (it[3] as String).toDouble(),
                close = (it[4] as String).toDouble(),
                volume = (it[5] as String).toDouble(),
                closeTime = (it[6] as Number).toLong()
            )
        } catch (_: Exception) { null }
    }
}

fun toBinanceSymbol(pair: String): String {
    // "BTC/USDT" -> "BTCUSDT", "ETH/USDT:USDT" -> "ETHUSDT"
    return pair.uppercase().replace("/", "").replace(":", "").replace("USDTUSDT", "USDT")
}

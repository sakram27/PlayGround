package com.freqtrade.mobile.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import okhttp3.OkHttpClient

// Binance public market data (tanpa API key) untuk mode paper-trading di HP.
data class Ticker24h(
    val symbol: String = "",
    val lastPrice: String = "0",
    val priceChangePercent: String = "0",
    val highPrice: String = "0",
    val lowPrice: String = "0",
    val volume: String = "0",
    val quoteVolume: String = "0",
)

interface BinanceService {
    @GET("api/v3/ticker/24hr")
    suspend fun tickers(): List<Ticker24h>

    @GET("api/v3/klines")
    suspend fun klines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String = "1h",
        @Query("limit") limit: Int = 100,
    ): List<List<Any>>
}

object BinanceClient {
    val api: BinanceService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BinanceService::class.java)
    }
}

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

fun parseKlines(raw: List<List<Any>>): List<Candle> = raw.mapNotNull { r ->
    try {
        Candle(
            openTime = (r[0] as Number).toLong(),
            open = (r[1] as String).toDouble(),
            high = (r[2] as String).toDouble(),
            low = (r[3] as String).toDouble(),
            close = (r[4] as String).toDouble(),
            volume = (r[5] as String).toDouble(),
        )
    } catch (_: Exception) { null }
}

package com.freqdroid.app.data

import retrofit2.http.*

interface FreqtradeApi {
    @POST("api/v1/token/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/v1/show_config")
    suspend fun showConfig(@Header("Authorization") auth: String): Map<String, Any>

    @GET("api/v1/status")
    suspend fun status(@Header("Authorization") auth: String): StatusResponse

    @GET("api/v1/profit")
    suspend fun profit(@Header("Authorization") auth: String): ProfitResponse

    @GET("api/v1/balance")
    suspend fun balance(@Header("Authorization") auth: String): Map<String, Any>

    @GET("api/v1/performance")
    suspend fun performance(@Header("Authorization") auth: String): List<PerformanceEntry>

    @GET("api/v1/daily")
    suspend fun daily(@Header("Authorization") auth: String, @Query("timescale") timescale: Int = 7): Any

    @POST("api/v1/start")
    suspend fun start(@Header("Authorization") auth: String): Map<String, String>

    @POST("api/v1/stop")
    suspend fun stop(@Header("Authorization") auth: String): Map<String, String>

    @POST("api/v1/stopbuy")
    suspend fun stopBuy(@Header("Authorization") auth: String): Map<String, String>

    @POST("api/v1/reload_config")
    suspend fun reloadConfig(@Header("Authorization") auth: String): Map<String, String>

    @POST("api/v1/forceexit")
    suspend fun forceExit(@Header("Authorization") auth: String, @Body body: Map<String, String>): Map<String, String>
}

// Binance public klines: https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=5m&limit=200
interface BinanceApi {
    @GET("api/v3/klines")
    suspend fun klines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String = "5m",
        @Query("limit") limit: Int = 200
    ): List<List<Any>>

    @GET("api/v3/ticker/24hr")
    suspend fun ticker24h(@Query("symbol") symbol: String): Map<String, Any>
}

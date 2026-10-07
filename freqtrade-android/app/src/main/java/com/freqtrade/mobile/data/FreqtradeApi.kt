package com.freqtrade.mobile.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// ---------- Freqtrade REST API models (docs.freqtrade.io/rest-api) ----------
data class LoginRequest(val username: String, val password: String)
data class LoginResponse(@SerializedName("access_token") val accessToken: String)

data class ProfitResponse(
    @SerializedName("profit_closed_coin") val profitClosedCoin: Double = 0.0,
    @SerializedName("profit_closed_ratio_mean") val profitClosedRatioMean: Double = 0.0,
    @SerializedName("profit_closed_percent") val profitClosedPercent: Double = 0.0,
    @SerializedName("profit_closed_fiat") val profitClosedFiat: Double = 0.0,
    @SerializedName("profit_all_coin") val profitAllCoin: Double = 0.0,
    @SerializedName("profit_all_ratio_mean") val profitAllRatioMean: Double = 0.0,
    @SerializedName("profit_all_percent") val profitAllPercent: Double = 0.0,
    @SerializedName("profit_all_fiat") val profitAllFiat: Double = 0.0,
    @SerializedName("trade_count") val tradeCount: Int = 0,
    @SerializedName("closed_trade_count") val closedTradeCount: Int = 0,
    @SerializedName("first_trade_date") val firstTradeDate: String = "",
    @SerializedName("latest_trade_date") val latestTradeDate: String = "",
    @SerializedName("avg_duration") val avgDuration: String = "",
    @SerializedName("best_pair") val bestPair: String = "",
    @SerializedName("best_rate") val bestRate: Double = 0.0,
    @SerializedName("winning_trades") val winningTrades: Int = 0,
    @SerializedName("losing_trades") val losingTrades: Int = 0,
)

data class Trade(
    @SerializedName("trade_id") val tradeId: Int = 0,
    val pair: String = "",
    val side: String = "long",
    @SerializedName("open_rate") val openRate: Double = 0.0,
    @SerializedName("close_rate") val closeRate: Double? = null,
    @SerializedName("current_rate") val currentRate: Double? = null,
    val amount: Double = 0.0,
    val stake_amount: Double = 0.0,
    @SerializedName("profit_ratio") val profitRatio: Double? = null,
    @SerializedName("profit_abs") val profitAbs: Double? = null,
    @SerializedName("open_date") val openDate: String = "",
    @SerializedName("enter_tag") val enterTag: String? = null,
    val timeframe: String = "",
    val leverage: Double? = null,
    @SerializedName("is_open") val isOpen: Boolean = true,
)

data class StatusResponse(val status: String = "")
data class VersionResponse(val version: String = "")
data class BalanceResponse(
    val currencies: List<BalanceCurrency> = emptyList(),
    val total_stake: Double = 0.0,
    val total_value: Double = 0.0,
)
data class BalanceCurrency(
    val currency: String = "",
    val free: Double = 0.0,
    val balance: Double = 0.0,
    val used: Double = 0.0,
    val est_stake: Double = 0.0,
)
data class PerformanceEntry(val pair: String = "", val profit_ratio: Double = 0.0, val profit_abs: Double = 0.0, val count: Int = 0)
data class DailyEntry(val date: String = "", @SerializedName("abs_profit") val absProfit: Double = 0.0, @SerializedName("fiat_value") val fiatValue: Double = 0.0, @SerializedName("trade_count") val tradeCount: Int = 0)

interface FreqtradeService {
    @POST("token/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("ping") suspend fun ping(): Map<String, String>
    @GET("version") suspend fun version(@Header("Authorization") auth: String): VersionResponse
    @GET("status") suspend fun openTrades(@Header("Authorization") auth: String): List<Trade>
    @GET("trades") suspend fun closedTrades(@Header("Authorization") auth: String, @Query("limit") limit: Int = 50): List<Trade>
    @GET("profit") suspend fun profit(@Header("Authorization") auth: String): ProfitResponse
    @GET("balance") suspend fun balance(@Header("Authorization") auth: String): BalanceResponse
    @GET("performance") suspend fun performance(@Header("Authorization") auth: String): List<PerformanceEntry>
    @GET("daily") suspend fun daily(@Header("Authorization") auth: String, @Query("timescale") timescale: Int = 7): Map<String, List<DailyEntry>>
    @GET("show_config") suspend fun showConfig(@Header("Authorization") auth: String): Map<String, Any>
    @POST("start") suspend fun start(@Header("Authorization") auth: String): StatusResponse
    @POST("stop") suspend fun stop(@Header("Authorization") auth: String): StatusResponse
    @POST("pause") suspend fun pause(@Header("Authorization") auth: String): StatusResponse
    @POST("forceexit") suspend fun forceExit(@Header("Authorization") auth: String, @Query("tradeid") tradeId: String): StatusResponse
    @POST("forceenter")
    suspend fun forceEnter(
        @Header("Authorization") auth: String,
        @Query("pair") pair: String,
        @Query("side") side: String = "long",
    ): StatusResponse
}

object FreqtradeClient {
    fun create(baseUrl: String): FreqtradeService {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder().addInterceptor(logging).build()
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        // Semua endpoint diawali api/v1/
        val full = if (url.contains("/api/v1")) url else url + "api/v1/"
        return Retrofit.Builder()
            .baseUrl(full)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FreqtradeService::class.java)
    }
}

fun bearer(token: String) = if (token.startsWith("Bearer ")) token else "Bearer $token"

package com.freqdroid.app.data

// ---- Freqtrade-compatible models (subset of freqtrade REST API) ----
data class LoginRequest(val username: String, val password: String)
data class LoginResponse(val access_token: String)

data class ProfitResponse(
    val profit_closed_coin: Double = 0.0,
    val profit_closed_percent: Double = 0.0,
    val profit_closed_fiat: Double = 0.0,
    val profit_all_coin: Double = 0.0,
    val profit_all_percent: Double = 0.0,
    val profit_all_fiat: Double = 0.0,
    val trade_count: Int = 0,
    val closed_trade_count: Int = 0,
    val winning_trades: Int = 0,
    val losing_trades: Int = 0
)

data class BalanceCurrency(
    val balance: Double = 0.0,
    val est_stake: Double = 0.0,
    val stake: String = ""
)

data class StatusResponse(
    val state: String = "stopped",
    val strategy: String = "",
    val dry_run: Boolean = true,
    val stake_currency: String = "USDT"
)

data class OpenTrade(
    val trade_id: Int = 0,
    val pair: String = "BTC/USDT",
    val is_open: Boolean = true,
    val entry_side: String = "long",
    val amount: Double = 0.0,
    val open_rate: Double = 0.0,
    val current_rate: Double = 0.0,
    val profit_pct: Double = 0.0,
    val profit_abs: Double = 0.0,
    val stake_amount: Double = 0.0,
    val strategy: String = "",
    val timeframe: String = "5m"
)

data class PerformanceEntry(
    val pair: String = "",
    val profit_abs: Double = 0.0,
    val profit_pct: Double = 0.0,
    val profit: Double = 0.0,
    val count: Int = 0
)

// ---- Market (Binance public) ----
data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long
)

data class Ticker(val symbol: String, val price: Double, val changePct24h: Double)

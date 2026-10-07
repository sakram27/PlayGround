package com.freqdroid.app.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freqdroid.app.data.*
import com.freqdroid.app.engine.BacktestResult
import com.freqdroid.app.engine.PaperEngine
import kotlinx.coroutines.launch

data class DemoTrade(
    val id: Int, val pair: String, val side: String,
    val entry: Double, val current: Double, val profitPct: Double, val stake: Double
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val binance = ApiFactory.binance()

    var serverUrl by mutableStateOf(""); private set
    var username by mutableStateOf(""); private set
    var stake by mutableStateOf("USDT"); private set
    var dryRun by mutableStateOf(true); private set
    var useDemo by mutableStateOf(true); private set

    var botState by mutableStateOf("stopped"); private set
    var strategy by mutableStateOf("EmaRsiCross"); private set
    var profitPct by mutableStateOf(0.0); private set
    var profitAbs by mutableStateOf(0.0); private set
    var tradeCount by mutableStateOf(0); private set
    var winRate by mutableStateOf(0.0); private set

    var tickers by mutableStateOf<List<Ticker>>(emptyList()); private set
    var candles by mutableStateOf<List<Candle>>(emptyList()); private set
    var selectedPair by mutableStateOf("BTC/USDT"); private set
    var selectedTf by mutableStateOf("5m"); private set
    var signal by mutableStateOf("WAIT"); private set
    var backtest by mutableStateOf<BacktestResult?>(null); private set
    var demoTrades by mutableStateOf<List<DemoTrade>>(emptyList()); private set
    var statusMsg by mutableStateOf("Mode demo aktif — data dari Binance publik."); private set
    var loading by mutableStateOf(false); private set

    val pairs = listOf("BTC/USDT", "ETH/USDT", "SOL/USDT", "BNB/USDT", "XRP/USDT", "DOGE/USDT")
    val timeframes = listOf("5m", "15m", "1h", "4h", "1d")

    init {
        viewModelScope.launch {
            val s = store.load()
            serverUrl = s["url"] as String
            username = s["username"] as String
            stake = (s["stake"] as String).ifBlank { "USDT" }
            dryRun = s["dryRun"] as Boolean
            useDemo = serverUrl.isBlank()
            refreshMarket()
        }
    }

    fun selectPair(p: String) { selectedPair = p; viewModelScope.launch { refreshMarket() } }
    fun selectTf(tf: String) { selectedTf = tf; viewModelScope.launch { refreshMarket() } }

    suspend fun refreshMarket() {
        try {
            loading = true
            // tickers ringkas: ambil harga tiap pair via ticker24h
            val list = pairs.map { p ->
                try {
                    val t = binance.ticker24h(toBinanceSymbol(p))
                    Ticker(p, (t["lastPrice"] as? String)?.toDouble() ?: 0.0,
                        (t["priceChangePercent"] as? String)?.toDouble() ?: 0.0)
                } catch (_: Exception) { Ticker(p, 0.0, 0.0) }
            }
            tickers = list
            val raw = binance.klines(toBinanceSymbol(selectedPair), selectedTf, 200)
            candles = parseKlines(raw)
            signal = PaperEngine.signal(candles)
            backtest = PaperEngine.backtest(selectedPair, candles)
            // demo paper trades: buat 3 contoh dari sinyal terakhir
            val last = candles.takeLast(1).firstOrNull()
            if (last != null) {
                demoTrades = listOf(
                    DemoTrade(1, selectedPair, "long", last.close * 0.985, last.close,
                        ((last.close - last.close * 0.985) / (last.close * 0.985) * 100), 100.0),
                    DemoTrade(2, "ETH/USDT", "long", last.close * 0.4, last.close * 0.412,
                        3.0, 100.0),
                    DemoTrade(3, "SOL/USDT", "short", last.close * 0.1, last.close * 0.098,
                        2.0, 100.0)
                )
                val wins = backtest?.let { r -> if (r.totalTrades == 0) 0.0 else r.winRate } ?: 0.0
                winRate = wins
                profitPct = backtest?.totalProfitPct ?: 0.0
                profitAbs = profitPct * 2.5
                tradeCount = backtest?.totalTrades ?: 0
            }
            statusMsg = if (useDemo) "Mode demo (Binance publik) • ${candles.size} candles $selectedPair $selectedTf • sinyal: $signal"
            else "Terhubung: $serverUrl"
        } catch (e: Exception) {
            statusMsg = "Gagal muat market: ${e.message}"
        } finally { loading = false }
    }

    fun refresh() = viewModelScope.launch { refreshMarket() }

    fun saveSettings(url: String, user: String, pass: String, stakeC: String, dry: Boolean) {
        viewModelScope.launch {
            store.save(url, user, pass, stakeC, dry)
            serverUrl = url.trim().trimEnd('/')
            username = user
            stake = stakeC
            dryRun = dry
            useDemo = serverUrl.isBlank()
            if (!useDemo) connectFreqtrade(pass) else refreshMarket()
        }
    }

    private suspend fun connectFreqtrade(password: String) {
        try {
            loading = true
            val api = ApiFactory.freqtrade(serverUrl)
            val token = api.login(LoginRequest(username, password)).access_token
            val auth = "Bearer $token"
            val st = api.status(auth)
            botState = st.state
            strategy = st.strategy.ifBlank { "—" }
            val p = api.profit(auth)
            profitPct = p.profit_all_percent
            profitAbs = p.profit_all_fiat
            tradeCount = p.trade_count
            statusMsg = "Terhubung ke bot: $strategy ($botState)"
        } catch (e: Exception) {
            statusMsg = "Gagal ke server freqtrade: ${e.message}. Kembali ke demo."
            useDemo = true
            refreshMarket()
        } finally { loading = false }
    }

    fun botStart() {
        if (useDemo) { botState = "running"; statusMsg = "Bot demo START (dry-run paper trading)"; return }
        viewModelScope.launch {
            try {
                val s = store.load(); val pass = s["password"] as String
                val api = ApiFactory.freqtrade(serverUrl)
                val token = api.login(LoginRequest(username, pass)).access_token
                api.start("Bearer $token"); botState = "running"; statusMsg = "Bot started"
            } catch (e: Exception) { statusMsg = "Start gagal: ${e.message}" }
        }
    }

    fun botStop() {
        if (useDemo) { botState = "stopped"; statusMsg = "Bot demo STOP"; return }
        viewModelScope.launch {
            try {
                val s = store.load(); val pass = s["password"] as String
                val api = ApiFactory.freqtrade(serverUrl)
                val token = api.login(LoginRequest(username, pass)).access_token
                api.stop("Bearer $token"); botState = "stopped"; statusMsg = "Bot stopped"
            } catch (e: Exception) { statusMsg = "Stop gagal: ${e.message}" }
        }
    }
}

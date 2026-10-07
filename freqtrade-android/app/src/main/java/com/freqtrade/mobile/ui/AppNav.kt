package com.freqtrade.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.freqtrade.mobile.data.*
import kotlinx.coroutines.launch

// ================= ViewModels =================

class BotViewModel : ViewModel() {
    var baseUrl by mutableStateOf("http://192.168.1.10:8080")
    var username by mutableStateOf("freqtrader")
    var password by mutableStateOf("")
    var token by mutableStateOf("")
    var connected by mutableStateOf(false)
    var statusMsg by mutableStateOf("Belum terhubung")
    var profit: ProfitResponse? by mutableStateOf(null)
    var openTrades: List<Trade> by mutableStateOf(emptyList())
    var closedTrades: List<Trade> by mutableStateOf(emptyList())
    var version by mutableStateOf("")

    fun service() = FreqtradeClient.create(baseUrl)

    fun connect(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                statusMsg = "Menghubungkan..."
                val svc = service()
                svc.ping()
                val login = svc.login(LoginRequest(username, password))
                token = login.accessToken
                connected = true
                statusMsg = "Terhubung ✓"
                refresh()
                onDone(true)
            } catch (e: Exception) {
                connected = false
                statusMsg = "Gagal: ${e.message?.take(120)}"
                onDone(false)
            }
        }
    }

    fun refresh() {
        if (!connected || token.isEmpty()) return
        viewModelScope.launch {
            try {
                val svc = service(); val auth = bearer(token)
                profit = svc.profit(auth)
                openTrades = svc.openTrades(auth)
                closedTrades = svc.closedTrades(auth, 50)
                version = try { svc.version(auth).version } catch (_: Exception) { "" }
                statusMsg = "Terhubung ✓ ${openTrades.size} open"
            } catch (e: Exception) { statusMsg = "Refresh gagal: ${e.message?.take(100)}" }
        }
    }

    fun botAction(action: suspend FreqtradeService.(String) -> StatusResponse, label: String) {
        viewModelScope.launch {
            try {
                statusMsg = "$label..."
                service().action(bearer(token))
                statusMsg = "$label OK"
                refresh()
            } catch (e: Exception) { statusMsg = "$label gagal: ${e.message?.take(100)}" }
        }
    }

    fun forceExit(tradeId: String) {
        viewModelScope.launch {
            try {
                service().forceExit(bearer(token), tradeId)
                statusMsg = "Force exit $tradeId OK"; refresh()
            } catch (e: Exception) { statusMsg = "Force exit gagal: ${e.message?.take(100)}" }
        }
    }
}

class MarketViewModel : ViewModel() {
    var tickers: List<Ticker24h> by mutableStateOf(emptyList())
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var selectedSymbol by mutableStateOf("BTCUSDT")
    var candles: List<Candle> by mutableStateOf(emptyList())
    var interval by mutableStateOf("1h")

    val filtered get() = if (query.isBlank()) tickers.take(60)
        else tickers.filter { it.symbol.contains(query.uppercase()) }.take(60)

    fun loadTickers() {
        viewModelScope.launch {
            loading = true; error = ""
            try { tickers = BinanceClient.api.tickers().sortedByDescending { it.quoteVolume.toDoubleOrNull() ?: 0.0 }.take(200) }
            catch (e: Exception) { error = "Gagal load market: ${e.message?.take(100)}" }
            loading = false
        }
    }

    fun loadCandles() {
        viewModelScope.launch {
            try { candles = parseKlines(BinanceClient.api.klines(selectedSymbol, interval, 100)) }
            catch (_: Exception) {}
        }
    }
}

class PaperViewModel : ViewModel() {
    val broker = PaperBroker()
    var strategy by mutableStateOf(StrategyId.SMA_CROSS)
    var stakeUsdt by mutableStateOf("50")
    var symbol by mutableStateOf("BTCUSDT")
    var candles: List<Candle> by mutableStateOf(emptyList())
    var backtest: BacktestResult? by mutableStateOf(null)
    var msg by mutableStateOf("Paper trading: uang virtual \$1000, tanpa risiko.")
    var tick by mutableStateOf(0)

    fun load() {
        viewModelScope.launch {
            try {
                candles = parseKlines(BinanceClient.api.klines(symbol, "1h", 200))
                backtest = backtest(strategy, candles)
                autoSignal()
            } catch (e: Exception) { msg = "Gagal load data: ${e.message?.take(100)}" }
        }
    }

    fun autoSignal() {
        if (candles.size < 30) return
        val sig = signalFor(strategy, candles, candles.size - 1)
        val price = candles.last().close
        broker.updatePrices(mapOf(symbol to price))
        msg = "Sinyal ${strategy.title}: $sig @ $price"
    }

    fun buy() {
        val price = candles.lastOrNull()?.close ?: return
        val stake = stakeUsdt.toDoubleOrNull() ?: 50.0
        val t = broker.buy(symbol, price, stake, strategy.title)
        msg = if (t == null) "Saldo kurang! Balance: ${broker.balanceUsdt}" else "BUY ${t.symbol} @ $price (id=${t.id})"
        tick++
    }

    fun sell(id: Int) {
        val price = candles.lastOrNull()?.close ?: return
        val r = broker.sell(id, price)
        msg = if (r == null) "Trade tidak ditemukan" else "SELL id=$id profit=${String.format("%.2f", r)}%"
        tick++
    }
}

// ================= Navigation =================

@Composable
fun FreqTradeNav() {
    val nav = rememberNavController()
    val botVm: BotViewModel = viewModel()
    val marketVm: MarketViewModel = viewModel()
    val paperVm: PaperViewModel = viewModel()
    val ctx = LocalContext.current
    val settings = remember { SettingsRepository(ctx) }
    val savedCfg by settings.configFlow.collectAsState(initial = ServerConfig())

    LaunchedEffect(savedCfg) {
        botVm.baseUrl = savedCfg.baseUrl
        botVm.username = savedCfg.username
        botVm.password = savedCfg.password
    }
    LaunchedEffect(Unit) { marketVm.loadTickers(); marketVm.loadCandles(); paperVm.load() }

    val items = listOf(
        Triple("dashboard", "Bot", Icons.Filled.Home),
        Triple("trades", "Trades", Icons.Filled.List),
        Triple("market", "Market", Icons.Filled.TrendingUp),
        Triple("paper", "Paper", Icons.Filled.SmartToy),
        Triple("connect", "Server", Icons.Filled.Settings),
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                val back by nav.currentBackStackEntryAsState()
                val route = back?.destination?.route
                items.forEach { (r, label, icon) ->
                    NavigationBarItem(
                        selected = route == r,
                        onClick = { nav.navigate(r) { launchSingleTop = true } },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "dashboard", Modifier.padding(pad)) {
            composable("dashboard") { DashboardScreen(botVm) }
            composable("trades") { TradesScreen(botVm) }
            composable("market") { MarketScreen(marketVm) }
            composable("paper") { PaperScreen(paperVm, marketVm) }
            composable("connect") { ConnectScreen(botVm, settings) }
        }
    }
}

// ================= Screens =================

@Composable
fun DashboardScreen(vm: BotViewModel) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("FreqTrade Mobile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Bot trading crypto ala Freqtrade di Android", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Card { Column(Modifier.padding(16.dp)) {
            Text("Status Server", fontWeight = FontWeight.Bold)
            Text(vm.statusMsg)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refresh() }) { Text("Refresh") }
                OutlinedButton(onClick = { vm.botAction({ start(it) }, "Start") }) { Text("Start") }
                OutlinedButton(onClick = { vm.botAction({ stop(it) }, "Stop") }) { Text("Stop") }
            }
            if (vm.version.isNotEmpty()) Text("Freqtrade v${vm.version}", color = Color.Gray)
        } }
        val p = vm.profit
        if (p != null) {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Profit (Closed)", fontWeight = FontWeight.Bold)
                Text("${String.format("%.2f", p.profitClosedPercent)}%  •  ${String.format("%.4f", p.profitClosedCoin)} coin  •  ${String.format("%.2f", p.profitClosedFiat)} fiat")
                Text("Trades: ${p.closedTradeCount} (W:${p.winningTrades} / L:${p.losingTrades})  Winrate: ${if (p.closedTradeCount>0) p.winningTrades*100/p.closedTradeCount else 0}%")
                Text("Best pair: ${p.bestPair} (${String.format("%.2f", p.bestRate)}%)", color = Color.Gray)
            } }
        } else {
            Card { Column(Modifier.padding(16.dp)) {
                Text("Belum ada data profit.")
                Text("Hubungkan ke server Freqtrade di menu Server, atau pakai mode Paper (uang virtual).", color = Color.Gray)
            } }
        }
        Card { Column(Modifier.padding(16.dp)) {
            Text("Open Trades (${vm.openTrades.size})", fontWeight = FontWeight.Bold)
            vm.openTrades.take(5).forEach { t ->
                val pr = (t.profitRatio ?: 0.0) * 100
                val col = if (pr >= 0) Color(0xFF26A69A) else Color(0xFFEF5350)
                Text("${t.pair}  ${String.format("%.2f", pr)}%", color = col)
            }
            if (vm.openTrades.isEmpty()) Text("Tidak ada open trade.", color = Color.Gray)
        } }
    }
}

@Composable
fun TradesScreen(vm: BotViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trades", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { vm.refresh() }) { Text("Refresh") }
        }
        Text("Open (${vm.openTrades.size})", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.openTrades) { t ->
                Card { Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.pair, fontWeight = FontWeight.Bold)
                            Text("Entry ${t.openRate} • Amt ${t.amount} • ${t.enterTag ?: "-"}", color = Color.Gray)
                        }
                        val pr = (t.profitRatio ?: 0.0) * 100
                        Text(String.format("%.2f%%", pr), color = if (pr >= 0) Color(0xFF26A69A) else Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.forceExit(t.tradeId.toString()) }) { Text("Force Exit") }
                    }
                } }
            }
            if (vm.openTrades.isEmpty()) { item { Text("Tidak ada open trade. Jalankan bot atau force-enter dari server.", color = Color.Gray) } }
        }
        Text("Closed terakhir (${vm.closedTrades.size})", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.closedTrades.take(20)) { t ->
                val pr = (t.profitRatio ?: 0.0) * 100
                Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.pair, modifier = Modifier.weight(1f))
                    Text(String.format("%.2f%%", pr), color = if (pr >= 0) Color(0xFF26A69A) else Color(0xFFEF5350))
                } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(vm: MarketViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Market Live (Binance)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(vm.query, { vm.query = it }, Modifier.fillMaxWidth(), label = { Text("Cari pair, mis. BTC") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("15m", "1h", "4h", "1d").forEach { tf ->
                FilterChip(vm.interval == tf, { vm.interval = tf; vm.loadCandles() }, { Text(tf) })
            }
        }
        if (vm.candles.isNotEmpty()) {
            Card { Column(Modifier.padding(12.dp)) {
                Text("${vm.selectedSymbol} • ${vm.interval} • ${vm.candles.lastOrNull()?.close}", fontWeight = FontWeight.Bold)
                CandleChart(vm.candles)
            } }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.error.isNotEmpty()) Text(vm.error, color = Color.Red)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.filtered) { t ->
                val chg = t.priceChangePercent.toDoubleOrNull() ?: 0.0
                Card(Modifier.clickable { vm.selectedSymbol = t.symbol; vm.loadCandles() }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.symbol, fontWeight = FontWeight.Bold)
                            Text("Vol ${t.quoteVolume.take(10)}", color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(t.lastPrice.take(12))
                            Text(String.format("%.2f%%", chg), color = if (chg >= 0) Color(0xFF26A69A) else Color(0xFFEF5350))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperScreen(vm: PaperViewModel, marketVm: MarketViewModel) {
    val scroll = rememberScrollState()
    // sinkron tick agar recompose saat buy/sell
    vm.tick.let {}
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Paper Bot (di HP)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(vm.msg, color = Color.Gray)
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Strategi", fontWeight = FontWeight.Bold)
            StrategyId.entries.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.strategy = s; vm.load() }) {
                    RadioButton(vm.strategy == s, { vm.strategy = s; vm.load() })
                    Column { Text(s.title, fontWeight = FontWeight.Bold); Text(s.desc, color = Color.Gray) }
                }
            }
        } }
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Balance virtual: $${String.format("%.2f", vm.broker.balanceUsdt)}", fontWeight = FontWeight.Bold)
            Text("Open: ${vm.broker.open.size} • Closed: ${vm.broker.closed.size} • Total profit: ${String.format("%.2f", vm.broker.totalProfit())}%")
            OutlinedTextField(vm.symbol, { vm.symbol = it.uppercase() }, label = { Text("Symbol (BTCUSDT)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.stakeUsdt, { vm.stakeUsdt = it }, label = { Text("Stake USDT per trade") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.load() }) { Text("Load + Backtest") }
                Button(onClick = { vm.buy() }) { Text("BUY") }
            }
        } }
        val bt = vm.backtest
        if (bt != null) {
            Card { Column(Modifier.padding(12.dp)) {
                Text("Backtest ${vm.strategy.title}", fontWeight = FontWeight.Bold)
                Text("Trades: ${bt.totalTrades} • Winrate: ${String.format("%.1f", bt.winRate)}% • Profit: ${String.format("%.2f", bt.totalProfitPct)}% • MaxDD: ${String.format("%.2f", bt.maxDrawdownPct)}%")
                ProfitBar(bt.profitPerTrade)
            } }
        }
        if (vm.candles.isNotEmpty()) {
            Card { Column(Modifier.padding(12.dp)) {
                Text("${vm.symbol} • Last ${vm.candles.last().close}", fontWeight = FontWeight.Bold)
                CandleChart(vm.candles)
            } }
        }
        Text("Open paper trades", fontWeight = FontWeight.Bold)
        vm.broker.open.toList().forEach { t ->
            Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#${t.id} ${t.symbol} @ ${t.entryPrice}", fontWeight = FontWeight.Bold)
                    Text("Now ${t.currentPrice} • ${String.format("%.2f", t.profitPct)}%", color = if (t.profitPct >= 0) Color(0xFF26A69A) else Color(0xFFEF5350))
                }
                Button(onClick = { vm.sell(t.id) }) { Text("SELL") }
            } }
        }
        if (vm.broker.open.isEmpty()) Text("Belum ada paper trade. Klik BUY untuk simulasi.", color = Color.Gray)
    }
}

@Composable
fun ConnectScreen(vm: BotViewModel, settings: SettingsRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Server Freqtrade", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Jalankan freqtrade di VPS/PC dengan api_server enabled, lalu koneksikan dari HP. Contoh config server ada di README.", color = Color.Gray)
        OutlinedTextField(vm.baseUrl, { vm.baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL (http://IP:8080)") }, singleLine = true)
        OutlinedTextField(vm.username, { vm.username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
        OutlinedTextField(vm.password, { vm.password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Text(vm.statusMsg, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { settings.save(ServerConfig(vm.baseUrl, vm.username, vm.password)) }
                vm.connect()
            }) { Text("Simpan & Connect") }
            OutlinedButton(onClick = { vm.refresh() }) { Text("Refresh") }
        }
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cara aktifkan API di server:", fontWeight = FontWeight.Bold)
            Text("\"api_server\": {\"enabled\": true, \"listen_ip_address\": \"0.0.0.0\", \"listen_port\": 8080, \"username\": \"freqtrader\", \"password\": \"isi-password-kuat\"}", color = Color.Gray)
            Text("Jangan expose ke internet tanpa password kuat + firewall. Ideal via VPN/Tailscale.", color = Color.Gray)
        } }
    }
}

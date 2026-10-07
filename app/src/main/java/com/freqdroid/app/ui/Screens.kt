package com.freqdroid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.freqdroid.app.data.Candle

val Bg = Color(0xFF0B0E11)
val Card = Color(0xFF12161C)
val Green = Color(0xFF0ECB81)
val Red = Color(0xFFF6465D)
val Yellow = Color(0xFFF0B90B)
val Muted = Color(0xFF8A94A6)

@Composable
fun CandleChart(candles: List<Candle>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.background(Card, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text("Chart ${candles.size} candles", color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (candles.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("Memuat chart…", color = Muted)
            }
        } else {
            val data = candles.takeLast(60)
            Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                val min = data.minOf { it.low }
                val max = data.maxOf { it.high }
                val range = (max - min).takeIf { it > 0 } ?: 1.0
                fun y(p: Double): Float = (size.height - ((p - min) / range * size.height)).toFloat()
                val stepX = size.width / data.size
                data.forEachIndexed { i, c ->
                    val cx = stepX * i + stepX / 2
                    val up = c.close >= c.open
                    val col = if (up) Green else Red
                    drawLine(col, Offset(cx, y(c.high)), Offset(cx, y(c.low)), strokeWidth = 3f)
                    val top = y(maxOf(c.open, c.close)); val bot = y(minOf(c.open, c.close))
                    drawLine(col, Offset(cx, top), Offset(cx, bot), strokeWidth = 9f)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Low ${"%.2f".format(data.minOf { it.low })}", color = Muted, fontSize = 12.sp)
                Text("High ${"%.2f".format(data.maxOf { it.high })}", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, color: Color = Color.White, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.background(Card, RoundedCornerShape(14.dp)).padding(0.dp),
        colors = CardDefaults.cardColors(containerColor = Card)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Muted, fontSize = 12.sp)
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(scroll).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ShowChart, null, tint = Yellow)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("FreqDroid", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(vm.statusMsg, color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Profit %", "${"%.2f".format(vm.profitPct)}%",
                if (vm.profitPct >= 0) Green else Red, Modifier.weight(1f))
            StatCard("Profit Fiat", "$${"%.2f".format(vm.profitAbs)}",
                if (vm.profitAbs >= 0) Green else Red, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Trades", "${vm.tradeCount}", Color.White, Modifier.weight(1f))
            StatCard("Winrate", "${"%.1f".format(vm.winRate)}%", Yellow, Modifier.weight(1f))
            StatCard("Sinyal", vm.signal,
                when (vm.signal) { "BUY" -> Green; "SELL" -> Red; else -> Yellow }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        // pair selector
        Text("Pair", color = Muted, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.pairs) { p ->
                FilterChip(
                    selected = p == vm.selectedPair,
                    onClick = { vm.selectPair(p) },
                    label = { Text(p) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.timeframes) { tf ->
                FilterChip(selected = tf == vm.selectedTf, onClick = { vm.selectTf(tf) }, label = { Text(tf) })
            }
        }
        Spacer(Modifier.height(12.dp))
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        CandleChart(vm.candles, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh Market") }
    }
}

@Composable
fun TradesScreen(vm: AppViewModel) {
    LazyColumn(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Open Trades (${vm.demoTrades.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            vm.backtest?.let { bt ->
                Text("Backtest ${vm.selectedPair}: ${bt.totalTrades} trades • winrate ${"%.1f".format(bt.winRate)}% • total ${"%.2f".format(bt.totalProfitPct)}% • DD ${"%.2f".format(bt.maxDrawdownPct)}%",
                    color = Muted, fontSize = 12.sp)
            }
        }
        items(vm.demoTrades) { t ->
            Card(colors = CardDefaults.cardColors(containerColor = Card)) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.pair, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(t.side.uppercase(), color = if (t.side == "long") Green else Red, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Entry ${"%.2f".format(t.entry)} → ${"%.2f".format(t.current)}", color = Muted, fontSize = 13.sp)
                    Text("${if (t.profitPct >= 0) "+" else ""}${"%.2f".format(t.profitPct)}%  •  Stake $${"%.0f".format(t.stake)}",
                        color = if (t.profitPct >= 0) Green else Red, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Text("Riwayat Backtest (terakhir 10)", color = Color.White, fontWeight = FontWeight.Bold)
            vm.backtest?.trades?.takeLast(10)?.reversed()?.forEach { tr ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("#${tr.entryIndex}→${tr.exitIndex} @ ${"%.2f".format(tr.exitPrice)}", color = Muted, fontSize = 12.sp)
                    Text("${"%.2f".format(tr.profitPct)}%", color = if (tr.profitPct >= 0) Green else Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun BotScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Kontrol Bot", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Card)) {
            Column(Modifier.padding(16.dp)) {
                Text("Status: ${vm.botState.uppercase()}", color = if (vm.botState == "running") Green else Muted, fontWeight = FontWeight.Bold)
                Text("Strategy: ${vm.strategy}", color = Color.White)
                Text("Stake: ${vm.stake} • Dry-run: ${vm.dryRun}", color = Muted, fontSize = 13.sp)
                Text("Mode: ${if (vm.useDemo) "DEMO paper-trading (HP)" else "Server freqtrade"}", color = Yellow, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.botStart() }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Start")
            }
            Button(onClick = { vm.botStop() }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Card)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Perintah ala-Telegram freqtrade:", color = Color.White, fontWeight = FontWeight.Bold)
                listOf("/status — lihat posisi", "/profit — total profit", "/daily — profit harian",
                    "/balance — saldo", "/forceexit — tutup paksa", "/performance — per pair").forEach {
                    Text("• $it", color = Muted, fontSize = 13.sp)
                }
                Text("Di aplikasi ini perintah diterjemahkan jadi tombol Start/Stop + tab Trades.",
                    color = Muted, fontSize = 12.sp)
            }
        }
        Text(vm.statusMsg, color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var url by remember(vm.serverUrl) { mutableStateOf(vm.serverUrl) }
    var user by remember(vm.username) { mutableStateOf(vm.username) }
    var pass by remember { mutableStateOf("") }
    var stake by remember(vm.stake) { mutableStateOf(vm.stake) }
    var dry by remember(vm.dryRun) { mutableStateOf(vm.dryRun) }
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pengaturan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Kosongkan URL server untuk memakai mode DEMO (paper-trading offline di HP pakai data Binance). Isi URL bila punya bot freqtrade Python yang jalan di VPS (aktifkan --api-server).",
            color = Muted, fontSize = 13.sp)
        OutlinedTextField(url, { url = it }, label = { Text("Server freqtrade, cth: http://192.168.1.10:8080") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(user, { user = it }, label = { Text("Username API") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(pass, { pass = it }, label = { Text("Password API") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(stake, { stake = it }, label = { Text("Stake currency (USDT/BTC)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(dry, { dry = it }); Spacer(Modifier.width(8.dp)); Text("Dry-run (tanpa uang asli)", color = Color.White)
        }
        Button(onClick = { vm.saveSettings(url, user, pass, stake, dry) }, modifier = Modifier.fillMaxWidth()) {
            Text("Simpan & Hubungkan")
        }
        Card(colors = CardDefaults.cardColors(containerColor = Card)) {
            Column(Modifier.padding(14.dp)) {
                Text("Contoh config freqtrade (di VPS):", color = Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("\"api_server\": {\"enabled\": true, \"listen_ip_address\": \"0.0.0.0\", \"port\": 8080}", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun BottomNav(nav: NavHostController) {
    NavigationBar(containerColor = Card) {
        val backStack by nav.currentBackStackEntryAsState()
        val cur = backStack?.destination?.route
        listOf(
            Triple("dashboard", "Market", Icons.Filled.ShowChart),
            Triple("trades", "Trades", Icons.Filled.List),
            Triple("bot", "Bot", Icons.Filled.SmartToy),
            Triple("settings", "Server", Icons.Filled.Settings)
        ).forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = cur == route, onClick = { nav.navigate(route) { launchSingleTop = true } },
                icon = { Icon(icon, null) }, label = { Text(label) }
            )
        }
    }
}

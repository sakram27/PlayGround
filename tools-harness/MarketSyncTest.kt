import com.aether.signal.premium.data.*
import com.aether.signal.premium.ui.*

var smpass = 0
var smfail = 0
fun smok(n: String, c: Boolean, x: String = "") {
    if (c) { smpass++; println("PASS $n $x") } else { smfail++; println("FAIL $n $x") }
}

fun main() {
    // ===== Simbol: verbatim untuk request, base eksak, multiplier terverifikasi =====
    smok("sym-base-spot", marketBase("BTCUSDT") == "BTC" && marketBase("1000SATSUSDT") == "1000SATS")
    smok("sym-quote", marketQuote("1000SATSUSDT") == "USDT" && marketQuote("EUR/USD") == "")
    smok("sym-icon-multiplier",
        iconBaseId("1000SATSUSDT") == "sats" &&
            iconBaseId("1MBABYDOGEUSDT") == "babydoge" &&
            iconBaseId("PEPEUSDT") == "pepe" &&
            iconBaseId("BTCUSDT") == "btc" &&
            iconBaseId("1INCHUSDT") == "1inch")
    smok("sym-icon-takdikenal-tanpa-karang",
        iconBaseId("FOOBARUSDT") == "foobar" &&
            logoKeyFor("FOOBARUSDT", setOf("coin_btc")) == null &&
            logoKeyFor("PEPEUSDT", setOf("coin_pepe", "coin_btc")) == "coin_pepe" &&
            logoKeyFor("1000SATSUSDT", setOf("coin_sats")) == "coin_sats")
    smok("sym-futures-ditolak-jujur",
        unlistedMessage("1000PEPEUSDT").contains("1000PEPEUSDT") &&
            unlistedMessage("1000PEPEUSDT").contains("futures") &&
            !isListedSpot("1000PEPEUSDT", setOf("PEPEUSDT")) &&
            isListedSpot("pepeusdt", setOf("PEPEUSDT")))
    // Logo terverifikasi untuk 10 teratas volume spot (subset representatif).
    val knownKeys = setOf("coin_btc", "coin_eth", "coin_sol", "coin_pepe", "coin_sats",
        "coin_1inch", "coin_babydoge", "coin_1000cat", "coin_1000cheems", "coin_0g", "coin_2z")
    smok("sym-logo-top", listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "PEPEUSDT", "1000SATSUSDT",
        "1INCHUSDT", "1MBABYDOGEUSDT", "1000CATUSDT", "1000CHEEMSUSDT", "0GUSDT", "2ZUSDT")
        .all { logoKeyFor(it, knownKeys) != null })

    // ===== Quotes: pilih dari full-list, deterministik, tanpa tukar pair =====
    run {
        val entries = listOf(
            Triple("BTCUSDT", 67000.0, 1.5),
            Triple("btcusdt", 67001.0, 1.6), // duplikat case -> baris pertama menang
            Triple("ETHUSDT", Double.NaN, null), // rusak -> hilang
            Triple("XRPUSDT", 0.0, 0.0) // nol -> hilang
        )
        val b = pickQuotes(listOf("BTCUSDT", "ETHUSDT", "XRPUSDT", "DOGEUSDT"), entries, 123L)
        smok("q-pick", b.ticks["BTCUSDT"]?.price == 67000.0 &&
            b.missing == listOf("DOGEUSDT", "ETHUSDT", "XRPUSDT") &&
            b.ticks.size == 1 && b.at == 123L, "missing=${b.missing}")
    }

    // ===== PairUniverse: sumber tunggal Market = Backtest =====
    run {
        val u = PairUniverse("binance", UNIVERSE_KIND_CRYPTO, 50,
            listOf("A", "B", "C"), false, 1000L)
        smok("u-match", u.matches("binance", UNIVERSE_KIND_CRYPTO) &&
            !u.matches("bybit", UNIVERSE_KIND_CRYPTO) &&
            !u.matches("binance", UNIVERSE_KIND_FOREX))
        smok("u-label", u.sourceLabel(2000L).contains("binance/crypto") &&
            u.sourceLabel(2000L).contains("3 pair"))
        smok("u-label-darurat",
            u.copy(isFallback = true).sourceLabel(2000L).contains("DARURAT"))
        // Backtest memakai universe yang SAMA (irisan + sisa manual).
        smok("u-sel", resolveBacktestSel(listOf("A", "B", "C"), setOf("B", "C", "D")) == listOf("B", "C", "D"))
        smok("u-sel-kosong", resolveBacktestSel(emptyList(), setOf("X")) == listOf("X"))
        smok("u-engine", resolveEngineCandidates(setOf("Z"), listOf("A", "B")) == listOf("Z", "A", "B"))
    }

    // ===== Opsi batas: naik, maks 200, mencakup 10..200 =====
    smok("u-opts", WATCH_LIMIT_OPTIONS == listOf(10, 20, 30, 50, 100, 150, 200) &&
        MAX_WATCH_PAIRS == 200)

    // ===== Tes sinkronisasi A–G (logika murni, tanpa jaringan) =====
    // A(10)/B(50)/C(100)/D(150)/E(200): himpunan Market == himpunan Backtest.
    for (n in listOf(10, 50, 100, 150, 200)) {
        val scan = (1..n).map { "P$it" }
        val uni = PairUniverse("binance", UNIVERSE_KIND_CRYPTO, n, scan, false, 1L)
        val multiSel = scan.toSet() // pengguna pilih semua hasil scan
        val bt = resolveBacktestSel(if (uni.matches("binance", UNIVERSE_KIND_CRYPTO)) uni.pairs else emptyList(), multiSel)
        smok("sync-$n", bt.toSet() == scan.toSet() && bt.size == n, "n=$n")
    }
    // F: ubah 50 -> 100 (snapshot baru menggantikan lama, bukan menumpuk basi).
    run {
        val old = PairUniverse("binance", UNIVERSE_KIND_CRYPTO, 50, (1..50).map { "P$it" }, false, 1L)
        val new = PairUniverse("binance", UNIVERSE_KIND_CRYPTO, 100, (1..100).map { "Q$it" }, false, 2L)
        val bt = resolveBacktestSel(new.pairs, (1..50).map { "P$it" }.toSet())
        smok("sync-f-ganti", bt == (1..50).map { "P$it" } && !bt.contains("Q1"),
            "seleksi lama di luar universe dipertahankan eksplisit, bukan hasil baru")
        smok("sync-f-match", !old.matches("binance", UNIVERSE_KIND_FOREX) && new.matches("binance", UNIVERSE_KIND_CRYPTO))
    }
    // G: gagal sebagian — gagal tidak masuk daftar siap uji.
    run {
        val b = pickQuotes(listOf("A", "B"),
            listOf(Triple("A", 1.0, 0.0)), 5L)
        smok("sync-g-gagal", b.ticks.keys == setOf("A") && b.missing == listOf("B"))
    }

    println("MARKET-SYNC done pass=$smpass fail=$smfail")
    if (smfail > 0) kotlin.system.exitProcess(1)
}

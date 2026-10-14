package com.aether.signal.premium.data

// Identitas simbol pasar (murni, teruji JVM).
//
// Aturan keras (audit Binance spot via exchangeInfo):
// - Simbol pasar dipakai VERBATIM untuk request data (klines, ticker).
//   Jangan pernah mengubah/menormalisasi sebelum memanggil endpoint.
// - Aset dasar diambil dari exchange (baseAsset), BUKAN dari parsing teks.
//   Fungsi di sini hanya untuk kebutuhan tampilan/logo dari simbol pasar.
// - Awalan angka (1000SATS, 1INCH, 1MBABYDOGE, 0G, 2Z) adalah bagian simbol
//   resmi spot — bukan versi futures dan bukan untuk dihapus saat request.
// - Futures USDT-M memakai simbol berbeda (mis. 1000PEPEUSDT tidak ada di
//   spot; spot memakai PEPEUSDT). Aplikasi ini spot-only: simbol futures
//   yang tidak terdaftar dilaporkan tidak tersedia, bukan dialihkan diam-diam.

/** Quote yang dikenal untuk pemisahan base/quote tampilan (terpanjang dulu). */
val KNOWN_QUOTES = listOf("USDT", "USDC", "FDUSD", "TUSD", "BUSD", "BTC", "ETH", "BNB", "USD")

/**
 * Basis terverifikasi berawalan angka -> id aset kanonik untuk logo.
 * Hanya entri terverifikasi via CoinGecko id+nama (lihat LogoCatalog):
 * - 1000SATS = SATS (Ordinals, kontrak ×1000 Binance) -> "sats"
 * - 1MBABYDOGE = Baby Doge Coin -> "babydoge"
 * Selain itu TIDAK ada penskalaan harga: harga klines sudah benar per
 * simbol pasar dan tidak pernah diubah.
 */
val MULTIPLIER_BASES = mapOf(
    "1000SATS" to "sats",
    "1MBABYDOGE" to "babydoge"
)

/** Pisahkan basis dari simbol pasar (akhiran quote eksak, digit dipertahankan). */
fun marketBase(marketSymbol: String, quotes: List<String> = KNOWN_QUOTES): String {
    val s = marketSymbol.trim().uppercase()
    for (q in quotes.sortedByDescending { it.length }) {
        if (s.length > q.length && s.endsWith(q)) return s.dropLast(q.length)
    }
    return s
}

/** Quote dari simbol pasar, atau "" bila tak dikenal / format forex ("A/B"). */
fun marketQuote(marketSymbol: String, quotes: List<String> = KNOWN_QUOTES): String {
    val s = marketSymbol.trim().uppercase()
    if (s.contains("/")) return ""
    for (q in quotes.sortedByDescending { it.length }) {
        if (s.length > q.length && s.endsWith(q)) return q
    }
    return ""
}

/**
 * Id aset kanonik (lowercase) untuk pencarian logo dari basis pasar.
 * Basis multiplier terverifikasi dipetakan eksplisit; sisanya lowercase apa adanya.
 */
fun assetIconId(marketBase: String): String {
    val b = marketBase.trim().uppercase()
    return MULTIPLIER_BASES[b] ?: b.lowercase()
}

/**
 * Id ikon untuk SIMBOL pasar penuh (digit dipertahankan, quote eksak dilepas,
 * lalu peta multiplier). Hanya untuk tampilan — tak pernah dipakai request.
 */
fun iconBaseId(marketSymbol: String): String =
    assetIconId(marketBase(marketSymbol))

/**
 * Kunci resource drawable logo untuk simbol pasar, mis. "coin_pepe".
 * Mengembalikan null bila tidak ada logo terverifikasi (pemanggil memakai
 * placeholder netral — jangan mengarang logo).
 */
fun logoKeyFor(marketSymbol: String, knownKeys: Set<String>): String? {
    val id = assetIconId(marketBase(marketSymbol))
    val key = "coin_$id"
    return if (knownKeys.contains(key)) key else null
}

/** Simbol spot valid = ada di daftar resmi exchange (tanpa transformasi). */
fun isListedSpot(marketSymbol: String, listed: Set<String>): Boolean =
    listed.contains(marketSymbol.trim().uppercase())

/**
 * Pesan jujur untuk simbol tak terdaftar di spot (mis. simbol futures
 * seperti 1000PEPEUSDT). Tidak pernah mengalihkan ke simbol lain diam-diam.
 */
fun unlistedMessage(marketSymbol: String): String {
    val s = marketSymbol.trim().uppercase()
    val digitPrefix = Regex("^[0-9]+").find(s)?.value
    val hint = if (digitPrefix != null)
        " Simbol berawalan angka ($digitPrefix) di futures belum tentu ada di spot — " +
            "gunakan simbol persis dari daftar pasar spot."
    else ""
    return "\"$s\" tidak terdaftar di pasar spot yang dipilih.$hint"
}

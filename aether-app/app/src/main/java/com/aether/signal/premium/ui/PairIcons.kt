package com.aether.signal.premium.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.aether.signal.premium.R

// SATU-SATUNYA sumber pemetaan ikon pair (3D).
// Visual dipisah dari simbol provider/mesin: fungsi ini TIDAK mengubah simbol.
// Sumber: logo crypto CC0-1.0 (spothq/cryptocurrency-icons, bundel lokal),
// bendera flagcdn (bundel lokal), vektor emas/perak/koin digambar sendiri.

object PairIcons {

    /** Basis kripto yang logonya tersedia lokal (coin_<id>.png). */
    private val COIN_IDS = setOf(
        "btc", "eth", "usdt", "sol", "bnb", "xrp", "doge", "ada", "avax",
        "link", "dot", "matic", "trx", "ltc", "bch", "atom", "etc", "fil",
        "apt", "wbtc", "usdc", "dai", "uni"
    )

    /** Basis TANPA logo resmi terverifikasi -> ilustrasi koin netral (dilaporkan). */
    val GENERIC_COINS = setOf(
        "near", "arb", "op", "inj", "sui", "pepe", "ton", "fdusd", "shib",
        "fog", "steth"
    )

    private val QUOTES = listOf("USDT", "USDC", "FDUSD", "TUSD", "BUSD", "BTC", "ETH", "BNB", "USD")

    private val CC_FLAG = mapOf(
        "EUR" to "eu", "USD" to "us", "GBP" to "gb", "JPY" to "jp", "AUD" to "au",
        "CAD" to "ca", "CHF" to "ch", "NZD" to "nz", "IDR" to "id", "SGD" to "sg",
        "MYR" to "my", "INR" to "in", "CNY" to "cn", "KRW" to "kr", "TRY" to "tr",
        "ZAR" to "za"
    )

    fun baseAsset(sym: String): String {
        val s = sym.uppercase().replace(Regex("[^A-Z]"), "")
        for (q in QUOTES.sortedByDescending { it.length }) {
            if (s.length > q.length && s.endsWith(q)) return s.dropLast(q.length)
        }
        return s
    }

    fun isMetal(sym: String): String? {
        val b = baseAsset(sym)
        return when {
            b == "XAU" || b == "GOLD" -> "XAU"
            b == "XAG" || b == "SILVER" -> "XAG"
            else -> null
        }
    }

    /** Pasangan mata uang (base, quote) bila simbol adalah forex, else null. */
    fun forexPair(sym: String): Pair<String, String>? {
        val s = sym.uppercase()
        if (s.contains("/")) {
            val parts = s.split("/").map { it.replace(Regex("[^A-Z]"), "") }
            if (parts.size == 2 && CC_FLAG.containsKey(parts[0]) && (CC_FLAG.containsKey(parts[1]) || parts[1] == "USD")) {
                return parts[0] to parts[1]
            }
            return null
        }
        val flat = s.replace(Regex("[^A-Z]"), "")
        if (flat.length == 6) {
            val a = flat.substring(0, 3); val b = flat.substring(3, 6)
            if (CC_FLAG.containsKey(a) && CC_FLAG.containsKey(b)) return a to b
        }
        return null
    }

    fun flagRes(ccy: String): Int {
        val code = CC_FLAG[ccy.uppercase()] ?: return 0
        return when (code) {
            "eu" -> R.drawable.flag_eu; "us" -> R.drawable.flag_us
            "gb" -> R.drawable.flag_gb; "jp" -> R.drawable.flag_jp
            "au" -> R.drawable.flag_au; "ca" -> R.drawable.flag_ca
            "ch" -> R.drawable.flag_ch; "nz" -> R.drawable.flag_nz
            "id" -> R.drawable.flag_id; "sg" -> R.drawable.flag_sg
            "my" -> R.drawable.flag_my; "in" -> R.drawable.flag_in
            "cn" -> R.drawable.flag_cn; "kr" -> R.drawable.flag_kr
            "tr" -> R.drawable.flag_tr; "za" -> R.drawable.flag_za
            else -> 0
        }
    }

    /** Drawable final untuk simbol apa pun: logo / bendera ganda / emas / generik. */
    fun iconFor(ctx: Context, sym: String): Drawable {
        isMetal(sym)?.let {
            val id = if (it == "XAU") R.drawable.ic_gold else R.drawable.ic_silver
            return ContextCompat.getDrawable(ctx, id)!!
        }
        forexPair(sym)?.let { (a, b) ->
            val ra = flagRes(a); val rb = flagRes(b)
            if (ra != 0 && rb != 0) return DualFlagDrawable(ctx, ra, rb)
            if (ra != 0) return ContextCompat.getDrawable(ctx, ra)!!
        }
        val base = baseAsset(sym).lowercase()
        if (base == "btc" || base == "eth") {
            // BTC/USD & ETH/USD Yahoo memakai logo kriptonya.
            return coinOrGeneric(ctx, base)
        }
        if (COIN_IDS.contains(base)) return coinOrGeneric(ctx, base)
        return ContextCompat.getDrawable(ctx, R.drawable.ic_coin_generic)!!
    }

    private fun coinOrGeneric(ctx: Context, base: String): Drawable {
        val id = ctx.resources.getIdentifier("coin_$base", "drawable", ctx.packageName)
        if (id != 0) {
            try {
                ContextCompat.getDrawable(ctx, id)?.let { return it }
            } catch (e: Exception) { /* jatuh ke generik */ }
        }
        return ContextCompat.getDrawable(ctx, R.drawable.ic_coin_generic)!!
    }

    /** Resource statis bila pemanggil butuh id (tanpa forex ganda). */
    fun iconRes(sym: String): Int {
        isMetal(sym)?.let { return if (it == "XAU") R.drawable.ic_gold else R.drawable.ic_silver }
        val base = baseAsset(sym).lowercase()
        if (base == "xau") return R.drawable.ic_gold
        if (base == "xag") return R.drawable.ic_silver
        return R.drawable.ic_coin_generic
    }
}

/** Dua bendera berdampingan dalam lingkaran (base kiri, quote kanan). */
class DualFlagDrawable(ctx: Context, resA: Int, resB: Int) : Drawable() {
    private val a: Bitmap = (ContextCompat.getDrawable(ctx, resA) as? BitmapDrawable)?.bitmap
        ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val b: Bitmap = (ContextCompat.getDrawable(ctx, resB) as? BitmapDrawable)?.bitmap
        ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val clip = Path()

    override fun draw(cv: Canvas) {
        val r = bounds
        if (r.isEmpty) return
        clip.reset()
        val cx = r.exactCenterX(); val cy = r.exactCenterY()
        val rad = minOf(r.width(), r.height()) / 2f
        clip.addCircle(cx, cy, rad, Path.Direction.CW)
        cv.save()
        cv.clipPath(clip)
        val half = RectF(r.left.toFloat(), r.top.toFloat(), cx, r.bottom.toFloat())
        val halfB = RectF(cx, r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        cv.drawBitmap(a, null, half, paint)
        cv.drawBitmap(b, null, halfB, paint)
        // garis pemisah tipis
        paint.color = 0xFF253244.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        cv.drawLine(cx, r.top.toFloat(), cx, r.bottom.toFloat(), paint)
        paint.style = Paint.Style.FILL
        cv.restore()
        // cincin luar
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF253244.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        cv.drawCircle(cx, cy, rad - 1f, ring)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("deprecated", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 84
    override fun getIntrinsicHeight() = 84
}

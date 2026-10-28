package com.demir.radyo

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** BerkOS'la aynı dil: sıcak mürekkep beyazı, soluk, tek vurgu pirinç. */
object Renk {
    val TR: Locale = Locale("tr", "TR")
    const val MUREKKEP = 0xFFF3ECE0.toInt()
    const val SOLUK = 0xFFBDB3A3.toInt()
    const val PIRINC = 0xFFE6C07E.toInt()
    const val KOYU = 0xFF1A1714.toInt()

    fun ayarlar(ctx: Context): SharedPreferences = ctx.getSharedPreferences("radyo", Context.MODE_PRIVATE)
}

object Yazi {
    lateinit var serif: Typeface; private set
    lateinit var serifItalik: Typeface; private set
    lateinit var sans: Typeface; private set
    lateinit var sansKalin: Typeface; private set

    fun yukle(ctx: Context) {
        if (::serif.isInitialized) return
        val a = ctx.applicationContext.assets
        serif = Typeface.createFromAsset(a, "fonts/instrument_serif.ttf")
        serifItalik = Typeface.createFromAsset(a, "fonts/instrument_serif_italic.ttf")
        sans = Typeface.Builder(a, "fonts/manrope.ttf").setFontVariationSettings("'wght' 500").build()
        sansKalin = Typeface.Builder(a, "fonts/manrope.ttf").setFontVariationSettings("'wght' 700").build()
    }
}

object Ag {
    private val havuz = Executors.newFixedThreadPool(3)
    private val ana = Handler(Looper.getMainLooper())

    fun arkada(gorev: () -> Unit) {
        havuz.execute { runCatching(gorev).onFailure { Log.w("Radyo", "arka plan işi patladı", it) } }
    }

    fun anada(gorev: () -> Unit) {
        ana.post(gorev)
    }

    private fun baglan(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", "BerkOSRadyo/1.0 (Google TV)")
    }

    fun metin(url: String): String {
        val c = baglan(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}: $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun dosyaya(url: String, hedef: File) {
        val c = baglan(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}: $url")
            val gecici = File(hedef.path + ".part")
            c.inputStream.use { girdi -> gecici.outputStream().use { girdi.copyTo(it) } }
            if (!gecici.renameTo(hedef)) throw IOException("yeniden adlandırılamadı: $hedef")
        } finally {
            c.disconnect()
        }
    }
}

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun View.dp(v: Number): Int = context.dp(v)

fun TextView.stil(tip: Typeface, sp: Float, renk: Int = Renk.MUREKKEP, harfAraligi: Float = 0f, golge: Boolean = true) {
    typeface = tip
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(renk)
    letterSpacing = harfAraligi
    if (golge) setShadowLayer(dp(10).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
}

fun String.buyukTr(): String = uppercase(Renk.TR)

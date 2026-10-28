package com.demir.salon

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

data class Konum(val ad: String, val enlem: Double, val boylam: Double)

object Salon {
    /** Sürümün sabit konumu yoksa ([Ek.VARSAYILAN_KONUM]) ilk açılışta IP'den yaklaşık şehir bulunur; Ayarlar > Şehir'den değişir. */
    private val VARSAYILAN = Konum("Ankara", 39.9334, 32.8597)
    val TR: Locale = Locale("tr", "TR")

    /** Google TV / Android TV'nin kendi ana ekranları. Ev tuşu yardımcısı bunları görünce Salon'u öne alır. */
    val STOK_ANA_EKRANLAR = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
    )

    const val MUREKKEP = 0xFFF3ECE0.toInt()
    const val SOLUK = 0xFFBDB3A3.toInt()
    const val PIRINC = 0xFFE6C07E.toInt()

    fun ayarlar(ctx: Context): SharedPreferences = ctx.getSharedPreferences("salon", Context.MODE_PRIVATE)

    fun konum(ctx: Context): Konum {
        val p = ayarlar(ctx)
        val ad = p.getString("konum_ad", null) ?: return Ek.VARSAYILAN_KONUM ?: VARSAYILAN
        return Konum(ad, p.getFloat("konum_enlem", 0f).toDouble(), p.getFloat("konum_boylam", 0f).toDouble())
    }

    /** Şehir değişince eski şehrin havası ve ezan vakitleri silinir, bir sonraki istekte yenisi iner. */
    fun konumKaydet(ctx: Context, k: Konum) {
        val p = ayarlar(ctx)
        val e = p.edit()
            .putString("konum_ad", k.ad).putFloat("konum_enlem", k.enlem.toFloat()).putFloat("konum_boylam", k.boylam.toFloat())
            .remove("hava_json").remove("hava_zaman")
        p.all.keys.filter { it.startsWith("ezan_") }.forEach { e.remove(it) }
        e.apply()
    }

    /** İlk açılış: şehir seçilmediyse IP'den yaklaşık şehri bulur (bir kere dener). */
    fun konumBul(ctx: Context, bitti: () -> Unit) {
        val p = ayarlar(ctx)
        if (Ek.VARSAYILAN_KONUM != null || p.contains("konum_ad") || p.getBoolean("konum_denendi", false)) return
        Ag.arkada {
            val o = org.json.JSONObject(Ag.metin("https://get.geojs.io/v1/ip/geo.json"))
            p.edit().putBoolean("konum_denendi", true).apply()
            if (o.optString("country_code") != "TR") return@arkada
            val sehir = o.optString("city").ifBlank { o.optString("region") }.ifBlank { return@arkada }
            // Şehir 81 ilden biriyse il merkezi (adı Türkçe yazılsın), değilse IP'nin verdiği nokta.
            val il = Sehirler.ILLER.firstOrNull { Sehirler.sade(it.ad) == Sehirler.sade(sehir) }
            konumKaydet(ctx, il ?: Konum(sehir, o.getString("latitude").toDouble(), o.getString("longitude").toDouble()))
            Ag.anada(bitti)
        }
    }
}

object Sehirler {
    /** 81 il merkezi (Open-Meteo yer aramasından bir kere alındı; TV'de ağ gerektirmesin, Kocaeli gibi adlar yanlış yere düşmesin). */
    val ILLER = listOf(
        Konum("Adana", 36.9862, 35.3253), Konum("Adıyaman", 37.7644, 38.2763),
        Konum("Afyonkarahisar", 38.7567, 30.5433), Konum("Ağrı", 39.7147, 43.0401),
        Konum("Aksaray", 38.3725, 34.0254), Konum("Amasya", 40.6533, 35.8331), Konum("Ankara", 39.9199, 32.8543),
        Konum("Antalya", 36.9081, 30.6956), Konum("Ardahan", 41.1087, 42.7022), Konum("Artvin", 41.1816, 41.8217),
        Konum("Aydın", 37.845, 27.8396), Konum("Balıkesir", 39.6492, 27.8861), Konum("Bartın", 41.6358, 32.3375),
        Konum("Batman", 37.8874, 41.1322), Konum("Bayburt", 40.2563, 40.2229), Konum("Bilecik", 40.1419, 29.9793),
        Konum("Bingöl", 38.8847, 40.4939), Konum("Bitlis", 38.4012, 42.1078), Konum("Bolu", 40.7358, 31.6061),
        Konum("Burdur", 37.7203, 30.2908), Konum("Bursa", 40.1956, 29.0601), Konum("Çanakkale", 40.1555, 26.4127),
        Konum("Çankırı", 40.5999, 33.6153), Konum("Çorum", 40.5489, 34.9533), Konum("Denizli", 37.7742, 29.0875),
        Konum("Diyarbakır", 37.9136, 40.2172), Konum("Düzce", 40.8389, 31.1639), Konum("Edirne", 41.6772, 26.556),
        Konum("Elazığ", 38.6743, 39.2232), Konum("Erzincan", 39.7392, 39.4901), Konum("Erzurum", 39.9086, 41.2769),
        Konum("Eskişehir", 39.7767, 30.5206), Konum("Gaziantep", 37.0594, 37.3825), Konum("Giresun", 40.917, 38.3874),
        Konum("Gümüşhane", 40.46, 39.4718), Konum("Hakkari", 37.5744, 43.7408), Konum("Hatay", 36.2066, 36.1572),
        Konum("Iğdır", 39.9237, 44.045), Konum("Isparta", 37.7644, 30.5522), Konum("İstanbul", 41.0138, 28.9497),
        Konum("İzmir", 38.4127, 27.1384), Konum("Kahramanmaraş", 37.5847, 36.9264),
        Konum("Karabük", 41.2049, 32.6277), Konum("Karaman", 37.1811, 33.215), Konum("Kars", 40.5983, 43.0855),
        Konum("Kastamonu", 41.3781, 33.7753), Konum("Kayseri", 38.7322, 35.4853), Konum("Kilis", 36.7161, 37.115),
        Konum("Kırıkkale", 39.8453, 33.5064), Konum("Kırklareli", 41.7351, 27.2252),
        Konum("Kırşehir", 39.1458, 34.1639), Konum("Kocaeli", 40.765, 29.9293), Konum("Konya", 37.8713, 32.4846),
        Konum("Kütahya", 39.4242, 29.9833), Konum("Malatya", 38.3502, 38.3167), Konum("Manisa", 38.612, 27.4265),
        Konum("Mardin", 37.3131, 40.7436), Konum("Mersin", 36.812, 34.6389), Konum("Muğla", 37.2181, 28.3665),
        Konum("Muş", 38.7316, 41.4848), Konum("Nevşehir", 38.625, 34.7122), Konum("Niğde", 37.9658, 34.6793),
        Konum("Ordu", 40.984, 37.8735), Konum("Osmaniye", 37.0742, 36.2478), Konum("Rize", 41.0208, 40.5219),
        Konum("Sakarya", 40.7806, 30.4033), Konum("Samsun", 41.2798, 36.3361), Konum("Siirt", 37.9293, 41.9413),
        Konum("Sinop", 42.0268, 35.1625), Konum("Sivas", 39.7483, 37.0161), Konum("Şanlıurfa", 37.1671, 38.7939),
        Konum("Şırnak", 37.5139, 42.4543), Konum("Tekirdağ", 40.9781, 27.511), Konum("Tokat", 40.3139, 36.5544),
        Konum("Trabzon", 41.005, 39.7269), Konum("Tunceli", 39.0992, 39.5435), Konum("Uşak", 38.6735, 29.4058),
        Konum("Van", 38.4946, 43.3832), Konum("Yalova", 40.655, 29.2769), Konum("Yozgat", 39.82, 34.8044),
        Konum("Zonguldak", 41.4514, 31.7931),
    )
    val IL: List<String> = ILLER.map { it.ad }

    /** "İstanbul" ile "Istanbul", "Şanlıurfa" ile "Sanliurfa" eşleşsin. */
    fun sade(s: String): String = s.lowercase(Salon.TR)
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u').replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace(" province", "").replace(" ili", "").trim()

    fun konum(il: String): Konum? = ILLER.firstOrNull { it.ad == il }
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
        havuz.execute { runCatching(gorev).onFailure { Log.w("Salon", "arka plan işi patladı", it) } }
    }

    fun anada(gorev: () -> Unit) {
        ana.post(gorev)
    }

    private const val TARAYICI = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private fun baglan(url: String, tarayici: Boolean = false) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 25_000
        setRequestProperty("User-Agent", if (tarayici) TARAYICI else "BerkOS/1.1 (Google TV ana ekrani)")
        setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
        setRequestProperty("AIC-User-Agent", "berkos-tv-launcher")
    }

    /** Adres yönlendirmesiz 200 dönüyor mu (YouTube Shorts tespiti için). */
    fun yonlendirmeYok(url: String): Boolean {
        val c = baglan(url, tarayici = true)
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            return c.responseCode == 200
        } finally {
            c.disconnect()
        }
    }

    /** JSON gövdeli POST (JustWatch). */
    fun gonder(url: String, govde: String): String {
        val c = baglan(url)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(govde.toByteArray()) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}: $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun metin(url: String, tarayici: Boolean = false): String {
        val c = baglan(url, tarayici)
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

/** Yazıya yazı tipi, boyut ve renk verir; tablonun üstünde okunsun diye hafif gölge ekler. */
fun TextView.stil(tip: Typeface, sp: Float, renk: Int = Salon.MUREKKEP, harfAraligi: Float = 0f, golge: Boolean = true) {
    typeface = tip
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(renk)
    letterSpacing = harfAraligi
    if (golge) setShadowLayer(dp(10).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
}

fun String.buyukTr(): String = uppercase(Salon.TR)

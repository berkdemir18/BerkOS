package com.demir.radyo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.Collator
import java.util.concurrent.Executors
import kotlin.math.abs

/** Bir radyo. [adresler] sırayla denenir (aynı radyonun Radio Browser'daki kopyaları yedek olur). */
class Istasyon(
    val id: String,
    val ad: String,
    val adresler: List<String>,
    val hls: List<Boolean>,
    val logo: String,
    val sehir: String,
    val kat: List<String>,
    val puan: Double,
    val etiket: String,
) {
    /** Logosu olmayan radyonun kutusu: addan çıkan, hep aynı kalan sakin bir renk. */
    val renk: Int by lazy {
        val hsv = floatArrayOf((abs(ad.hashCode()) % 360).toFloat(), .38f, .34f)
        Color.HSVToColor(hsv)
    }
}

class Kategori(val kod: String, val ad: String)

/**
 * Radyo listesi: uygulamayla gelen, bilgisayarda tek tek dinlenip denenmiş Türkiye radyoları
 * (araclar/radyo_listesi.py üretir). Bir radyonun bütün adresleri ölmüşse Radio Browser'dan
 * o radyonun yeni adresi aranır, bulunursa TV'de saklanır.
 */
object Radyolar {
    val KATEGORILER = listOf(
        Kategori("favori", "Favorilerim"),
        Kategori("populer", "Popüler"),
        Kategori("memleket", Ek.MEMLEKET_ADI),
        Kategori("turku", "Türkü & Halk"),
        Kategori("sanat", "Sanat & Nostalji"),
        Kategori("arabesk", "Arabesk & Fantezi"),
        Kategori("pop", "Pop"),
        Kategori("haber", "Haber & Spor"),
        Kategori("dini", "Dini"),
        Kategori("yabanci", "Yabancı & Caz"),
        Kategori("hepsi", "Tümü · A–Z"),
    )

    /** Yerel radyolar: önce bu sırayla, sonra şehri [Ek.MEMLEKET_SEHIR]'e uyanlar. */
    private val MEMLEKET = listOf("ankarahavalari", "seymen", "ostim", "ankarauniversitesiradyosuilef", "bilkent", "odtu",
        "gimsa", "hedef", "trafik1042ankara")
    private val MEMLEKET_SEHIR = Regex(Ek.MEMLEKET_SEHIR, RegexOption.IGNORE_CASE)
    private const val POPULER_SAYI = 40

    /** Ankara FM frekansları (ShiftDelete 2025 listesi). Listede olmayan radyonun kadranında "İNTERNET" yazar. */
    private val FREKANS = mapOf(
        "pal" to 87.7f, "trtfm" to 88.0f, "diyanetkuran" to 88.2f, "trafik1042ankara" to 88.4f, "baba" to 88.6f,
        "show" to 88.8f, "seymen" to 89.0f, "dost" to 89.2f, "7" to 89.8f, "vav" to 90.0f, "haberturk" to 90.4f,
        "gimsa" to 90.6f, "super" to 90.8f, "ankarauniversitesiradyosuilef" to 91.0f, "trt3" to 91.2f,
        "palnostalji" to 91.4f, "hedef" to 91.8f, "trt1" to 93.3f, "viva" to 93.6f, "virgin" to 93.8f,
        "diyanet" to 94.0f, "trthaber" to 95.0f, "slowturk" to 95.3f, "maxmaximummusic" to 95.8f, "maxfm" to 95.8f,
        "max" to 95.8f, "ostim" to 96.0f, "bayram" to 96.2f, "aspor" to 96.4f, "bilkent" to 96.6f, "park" to 96.9f,
        "metro" to 97.2f, "number1turk" to 97.4f, "spor" to 97.7f, "45lik" to 97.8f, "best" to 98.3f,
        "trtturku" to 98.6f, "fenomen" to 98.8f, "banko" to 99.1f, "palstation" to 99.3f, "powerpop" to 99.5f,
        "ahaber" to 99.7f, "power" to 100.0f, "kafa" to 100.7f, "paldoga" to 100.9f, "megasite" to 101.1f,
        "number1" to 101.5f, "kralpop" to 101.8f, "kral" to 102.4f, "trtnagme" to 102.8f, "odtu" to 103.1f,
        "erkam" to 103.4f, "d" to 104.0f, "tgrt" to 104.4f, "ntv" to 104.7f, "moral" to 104.9f, "alem" to 105.3f,
        "semerkand" to 105.8f, "joyturk" to 106.0f, "omur" to 106.3f, "joy" to 106.5f, "powerturk" to 106.9f,
        "avrasyaturk" to 107.1f, "akra" to 107.4f, "cnnturk" to 107.6f,
    )

    fun frekans(id: String?): Float? = FREKANS[id]

    @Volatile private var liste: List<Istasyon>? = null

    fun hepsi(ctx: Context): List<Istasyon> = liste ?: synchronized(this) {
        liste ?: yukle(ctx).also { liste = it }
    }

    fun bul(ctx: Context, id: String?): Istasyon? = id?.let { i -> hepsi(ctx).firstOrNull { it.id == i } }

    private fun yukle(ctx: Context): List<Istasyon> {
        val metin = ctx.assets.open("radyolar.json").bufferedReader().use { it.readText() }
        val a = JSONObject(metin).getJSONArray("istasyonlar")
        val onarilan = onarilanlar(ctx)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val id = o.getString("id")
            var adresler = o.getJSONArray("adresler").let { j -> (0 until j.length()).map { j.getString(it) } }
            var hls = o.optJSONArray("hls")?.let { j -> (0 until j.length()).map { j.getBoolean(it) } } ?: adresler.map { false }
            // TV'de onarılmış adresler önce denensin.
            onarilan.optJSONArray(id)?.let { j ->
                val yeni = (0 until j.length()).map { j.getJSONObject(it) }
                adresler = yeni.map { it.getString("u") } + adresler
                hls = yeni.map { it.optBoolean("h") } + hls
            }
            Istasyon(id, o.getString("ad"), adresler, hls, o.optString("logo"), o.optString("sehir"),
                o.getJSONArray("kat").let { j -> (0 until j.length()).map { j.getString(it) } },
                o.optDouble("puan", 0.0), o.optString("etiket"))
        }
    }

    fun kategori(ctx: Context, kod: String): List<Istasyon> {
        val h = hepsi(ctx)
        return when (kod) {
            "favori" -> favoriler(ctx).mapNotNull { f -> h.firstOrNull { it.id == f } }
            "populer" -> h.take(POPULER_SAYI)
            "memleket" -> MEMLEKET.mapNotNull { m -> h.firstOrNull { it.id == m } } +
                h.filter { it.id !in MEMLEKET && MEMLEKET_SEHIR.containsMatchIn(it.sehir + " " + it.ad) }
            "hepsi" -> Collator.getInstance(Renk.TR).let { c -> h.sortedWith { a, b -> c.compare(a.ad, b.ad) } }
            else -> h.filter { kod in it.kat }
        }
    }

    // ---------------------------------------------------------------- favoriler, son dinlenen

    fun favoriler(ctx: Context): List<String> =
        Renk.ayarlar(ctx).getString("radyo_favori", "")!!.split(',').filter { it.isNotBlank() }

    fun favoriMi(ctx: Context, id: String) = id in favoriler(ctx)

    /** Döner: artık favori mi. */
    fun favoriDegistir(ctx: Context, id: String): Boolean {
        val f = favoriler(ctx).toMutableList()
        val oldu = if (f.remove(id)) false else { f += id; true }
        Renk.ayarlar(ctx).edit().putString("radyo_favori", f.joinToString(",")).apply()
        return oldu
    }

    // ---------------------------------------------------------------- ölü radyonun yeni adresi

    private fun onarilanlar(ctx: Context): JSONObject =
        runCatching { JSONObject(Renk.ayarlar(ctx).getString("radyo_onarilan", "{}")!!) }.getOrDefault(JSONObject())

    /**
     * Radyonun bütün adresleri açılmadı: Radio Browser'da aynı adla aranır, denenmemiş adresler döner ve
     * saklanır. Arka planda çağrılır. Döner: yeni adresli istasyon ya da null.
     */
    fun onar(ctx: Context, ist: Istasyon): Istasyon? {
        val ad = URLEncoder.encode(ist.ad, "UTF-8").replace("+", "%20")
        val j = runCatching {
            JSONArray(Ag.metin("https://de1.api.radio-browser.info/json/stations/byname/$ad?countrycode=TR&hidebroken=true&order=clickcount&reverse=true&limit=8"))
        }.getOrNull() ?: return null
        val yeni = (0 until j.length()).map { j.getJSONObject(it) }
            .filter { it.optInt("lastcheckok") == 1 }
            .map { it.optString("url_resolved") to (it.optInt("hls") == 1) }
            .filter { (u, _) -> u.isNotBlank() && u !in ist.adresler }
            .distinctBy { it.first }.take(3)
        if (yeni.isEmpty()) return null
        val kayit = onarilanlar(ctx)
        kayit.put(ist.id, JSONArray().apply { for ((u, h) in yeni) put(JSONObject().put("u", u).put("h", h)) })
        Renk.ayarlar(ctx).edit().putString("radyo_onarilan", kayit.toString()).apply()
        synchronized(this) { liste = null }
        return bul(ctx, ist.id)
    }
}

/**
 * Radyo logoları. Arka planda indirilir, diskte ve bellekte tutulur. Logo yazılımsal bitmap olarak
 * çözülür (rengine bakılacak): kenarının rengi kutunun zemini olur, logo kutuya kesintisiz oturur;
 * en canlı rengi radyo ekranındaki ışığın rengi olur.
 */
object Logo {
    class Sonuc(val resim: Bitmap, val zemin: Int?, val renk: Int, val koyu: Boolean)

    private val havuz = Executors.newFixedThreadPool(3)
    private val bellek = object : LruCache<String, Sonuc>(10 * 1024 * 1024) {
        override fun sizeOf(k: String, s: Sonuc) = s.resim.byteCount
    }
    private val olmayan = HashSet<String>()
    private const val BOYUT = 220

    fun hazir(url: String): Sonuc? = bellek.get(url)

    fun iste(ctx: Context, url: String, cb: (Sonuc?) -> Unit) {
        if (url.isBlank() || url in olmayan) { cb(null); return }
        bellek.get(url)?.let { cb(it); return }
        val uyg = ctx.applicationContext
        havuz.execute {
            val s = runCatching { getir(uyg, url) }.getOrNull()
            if (s != null) bellek.put(url, s) else synchronized(olmayan) { olmayan += url }
            Ag.anada { cb(s) }
        }
    }

    private fun getir(ctx: Context, url: String): Sonuc? {
        val klasor = File(ctx.cacheDir, "radyo_logo").apply { mkdirs() }
        val f = File(klasor, Integer.toHexString(url.hashCode()) + ".img")
        if (!f.exists()) Ag.dosyaya(url, f)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) { f.delete(); return null }
        var orn = 1
        while (minOf(o.outWidth, o.outHeight) / (orn * 2) >= BOYUT) orn *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = orn; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        return b.kirp().let { k -> Sonuc(k, zemin(b), renk(b), koyuMu(k)) }
    }

    /** Logonun etrafındaki boş (saydam) kenarı at: küçük logolar kutuda kaybolmasın. */
    private fun Bitmap.kirp(): Bitmap {
        var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            if (Color.alpha(getPixel(x, y)) > 24) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        }
        if (x1 < 0 || (x0 <= 2 && y0 <= 2 && x1 >= width - 3 && y1 >= height - 3)) return this
        return Bitmap.createBitmap(this, x0, y0, (x1 - x0 + 2).coerceAtMost(width - x0), (y1 - y0 + 2).coerceAtMost(height - y0))
    }

    /** Kenar pikselleri dolu ve birbirine yakınsa onların ortalaması (logonun kendi zemini); saydamsa null. */
    private fun zemin(b: Bitmap): Int? {
        val kenar = ArrayList<Int>()
        val adim = maxOf(1, minOf(b.width, b.height) / 24)
        for (x in 0 until b.width step adim) { kenar += b.getPixel(x, 0); kenar += b.getPixel(x, b.height - 1) }
        for (y in 0 until b.height step adim) { kenar += b.getPixel(0, y); kenar += b.getPixel(b.width - 1, y) }
        val dolu = kenar.filter { Color.alpha(it) > 200 }
        if (dolu.size < kenar.size * .7f) return null
        val r = dolu.sumOf { Color.red(it) } / dolu.size
        val g = dolu.sumOf { Color.green(it) } / dolu.size
        val m = dolu.sumOf { Color.blue(it) } / dolu.size
        val sapma = dolu.sumOf { abs(Color.red(it) - r) + abs(Color.green(it) - g) + abs(Color.blue(it) - m) } / dolu.size
        return if (sapma < 60) Color.rgb(r, g, m) else null
    }

    /** Logonun en belirgin canlı rengi (yoksa genel tonu). */
    private fun renk(b: Bitmap): Int {
        val k = Bitmap.createScaledBitmap(b, 24, 24, true)
        val hsv = FloatArray(3)
        var r = 0L; var g = 0L; var m = 0L; var n = 0L
        var tr = 0L; var tg = 0L; var tm = 0L; var tn = 0L
        for (x in 0 until 24) for (y in 0 until 24) {
            val p = k.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            tr += Color.red(p); tg += Color.green(p); tm += Color.blue(p); tn++
            if (hsv[1] > .35f && hsv[2] > .3f) { r += Color.red(p); g += Color.green(p); m += Color.blue(p); n++ }
        }
        return when {
            n > 8 -> Color.rgb((r / n).toInt(), (g / n).toInt(), (m / n).toInt())
            tn > 0 -> Color.rgb((tr / tn).toInt(), (tg / tn).toInt(), (tm / tn).toInt())
            else -> Renk.PIRINC
        }
    }

    /** Saydam logonun açık mı koyu mu olduğu: koyu logo koyu kutuda kaybolmasın. */
    private fun koyuMu(b: Bitmap): Boolean {
        val k = Bitmap.createScaledBitmap(b, 16, 16, true)
        var t = 0f; var n = 0
        for (x in 0 until 16) for (y in 0 until 16) {
            val p = k.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            t += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f; n++
        }
        return n > 0 && t / n < .32f
    }
}

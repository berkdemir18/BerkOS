package com.demir.salon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max

data class Eser(
    val ad: String,
    val sanatci: String,
    val yil: String,
    val tur: String, // tablo | uzay | aile
    val w: Int,
    val h: Int,
    val varlik: String? = null, // APK içindeki dosya
    val dosya: File? = null, // sonradan indirilen
) {
    val oran: Float get() = w.toFloat() / h
}

/**
 * Eserler iki yerden gelir: APK'ya gömülü olanlar (internetsiz de çalışsın) ve
 * koleksiyon.json'daki "uzak" listesinden TV'nin zamanla indirdikleri (her gün yeni bir şey çıksın).
 */
object Koleksiyon {
    private const val UZAK_TAVAN = 60
    private const val INDIRME_ARASI_MS = 6 * 60 * 60_000L

    private var gomulu: List<Eser>? = null
    private var uzak: JSONArray = JSONArray()
    @Volatile private var indiriyor = false

    @Synchronized
    fun yukle(ctx: Context): List<Eser> {
        val g = gomulu ?: run {
            val o = JSONObject(ctx.assets.open("koleksiyon.json").bufferedReader().use { it.readText() })
            uzak = o.getJSONArray("uzak")
            val a = o.getJSONArray("gomulu")
            // Sürüme özel eserler (genel sürümde yok).
            val aile = Ek.koleksiyon(ctx)
            ((0 until a.length()).map { a.getJSONObject(it) } + (0 until aile.length()).map { aile.getJSONObject(it) }).map { e ->
                Eser(e.getString("ad"), e.getString("sanatci"), e.getString("yil"), e.getString("tur"),
                    e.getInt("w"), e.getInt("h"), varlik = e.getString("dosya"))
            }.also { gomulu = it }
        }
        return g + indirilenler(ctx) + kendiDuvarlari(ctx)
    }

    /** Kendi duvar kağıtların: adb ile files/duvar/ altına atılır, adı dosya adıdır. */
    private fun kendiDuvarlari(ctx: Context): List<Eser> {
        val d = File(ctx.getExternalFilesDir(null) ?: return emptyList(), "duvar")
        return d.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { f ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth <= 0) null
                else Eser(f.nameWithoutExtension, "", "", "tablo", o.outWidth, o.outHeight, dosya = f)
            }
    }

    private fun klasor(ctx: Context) = File(ctx.filesDir, "uzak").apply { mkdirs() }

    private fun kunye(ctx: Context): JSONObject {
        val f = File(klasor(ctx), "kunye.json")
        return if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject()) else JSONObject()
    }

    private fun indirilenler(ctx: Context): List<Eser> {
        val k = kunye(ctx)
        val d = klasor(ctx)
        return k.keys().asSequence().mapNotNull { ad ->
            val f = File(d, ad)
            if (!f.exists()) return@mapNotNull null
            val e = k.getJSONObject(ad)
            Eser(e.getString("ad"), e.getString("sanatci"), e.getString("yil"), e.getString("tur"),
                e.getInt("w"), e.getInt("h"), dosya = f)
        }.toList()
    }

    /** Ekrana sığacak kadar küçültüp açar; büyük resim TV'nin belleğini şişirmesin. */
    fun bitmap(ctx: Context, e: Eser, enFazla: Int): Bitmap? = runCatching {
        fun ac(): InputStream = e.varlik?.let { ctx.assets.open("koleksiyon/$it") } ?: FileInputStream(e.dosya!!)
        val sinir = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ac().use { BitmapFactory.decodeStream(it, null, sinir) }
        var ornek = 1
        while (max(sinir.outWidth, sinir.outHeight) / (ornek * 2) >= enFazla) ornek *= 2
        ac().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ornek }) }
    }.getOrNull()

    /** Arka planda birkaç yeni eser indirir; 6 saatte bir, en fazla 60 tane biriktirir. */
    fun uzaklariIndir(ctx: Context) {
        val p = Salon.ayarlar(ctx)
        if (indiriyor || System.currentTimeMillis() - p.getLong("uzak_son", 0) < INDIRME_ARASI_MS) return
        val uygulama = ctx.applicationContext
        indiriyor = true
        Ag.arkada {
            try {
                yukle(uygulama)
                val k = kunye(uygulama)
                if (k.length() >= UZAK_TAVAN || uzak.length() == 0) return@arkada
                val adaylar = (0 until uzak.length()).map { uzak.getJSONObject(it) }
                    .filter { !k.has(dosyaAdi(it.getString("url"))) }
                    .shuffled().take(3)
                for (a in adaylar) {
                    val ad = dosyaAdi(a.getString("url"))
                    val f = File(klasor(uygulama), ad)
                    runCatching { Ag.dosyaya(a.getString("url"), f) }.onFailure { return@arkada }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    if (o.outWidth <= 0) { f.delete(); continue }
                    k.put(ad, JSONObject()
                        .put("ad", a.getString("ad")).put("sanatci", a.getString("sanatci"))
                        .put("yil", a.getString("yil")).put("tur", a.getString("tur"))
                        .put("w", o.outWidth).put("h", o.outHeight))
                    File(klasor(uygulama), "kunye.json").writeText(k.toString())
                }
                p.edit().putLong("uzak_son", System.currentTimeMillis()).apply()
            } finally {
                indiriyor = false
            }
        }
    }

    private fun dosyaAdi(url: String) = "u${abs(url.hashCode())}.jpg"
}

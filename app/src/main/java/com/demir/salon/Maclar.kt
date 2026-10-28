package com.demir.salon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Sıradaki maç. [canli]: şu an oynanıyor; [skor] canlıysa ya da [bitti]yse "1-0". */
class Mac(
    val takim: String,
    val ev: String,
    val deplasman: String,
    val evLogo: String,
    val depLogo: String,
    val zaman: LocalDateTime,
    val lig: String,
    val canli: Boolean,
    val skor: String,
    /** ESPN maç numarası ve lig kodu (detay sayfası bunlarla açılır). */
    val id: String = "",
    val ligKodu: String = "",
    /** Bitmiş maç (bitişinden sonra 18 saat sonucu ekranda kalır). */
    val bitti: Boolean = false,
)

/**
 * Üç büyüklerin (Beşiktaş, Galatasaray, Fenerbahçe) sıradaki maçları. ESPN'in açık
 * fikstür servisi (anahtar istemez): lig + kupa + Avrupa. Saatte bir tazelenir, arada TV'de saklanır.
 */
object Maclar {
    private val TAKIMLAR = listOf(
        "Beşiktaş" to 1895, "Galatasaray" to 432, "Fenerbahçe" to 436,
    )
    private const val SURE_MS = 60 * 60_000L
    private const val SONUC_SAAT = 18

    /** ESPN İngilizce yazıyor; tabelada Türkçesi dursun. */
    fun turkce(ad: String) = ADLAR[ad] ?: ad
    fun ligAdi(ad: String) = LIGLER[ad] ?: ad

    private val ADLAR = mapOf(
        "Besiktas" to "Beşiktaş", "Fenerbahce" to "Fenerbahçe", "Istanbul Basaksehir" to "Başakşehir",
        "Caykur Rizespor" to "Rizespor", "Kasimpasa" to "Kasımpaşa", "Genclerbirligi" to "Gençlerbirliği",
        "Goztepe" to "Göztepe", "Eyupspor" to "Eyüpspor", "Erzurum BB" to "Erzurumspor", "Kocaelispor" to "Kocaelispor",
        "Antalyaspor" to "Antalyaspor", "Amed SFK" to "Amedspor", "Fatih Karagumruk" to "Karagümrük", "Alanyaspor" to "Alanyaspor", "Kayserispor" to "Kayserispor",
        "Gaziantep FK" to "Gaziantep FK", "Samsunspor" to "Samsunspor", "Konyaspor" to "Konyaspor",
    )
    private val LIGLER = mapOf(
        "Turkish Super Lig" to "Süper Lig", "UEFA Europa League" to "Avrupa Ligi",
        "UEFA Champions League" to "Şampiyonlar Ligi", "UEFA Conference League" to "Konferans Ligi",
        "Turkish Cup" to "Türkiye Kupası", "Turkish Super Cup" to "Süper Kupa",
    )

    fun getir(ctx: Context, cb: (List<Mac>) -> Unit) {
        val f = java.io.File(ctx.filesDir, "maclar2.json")
        val eldeki = oku(f)
        eldeki?.let(cb)
        // Maç başlamak üzereyse ya da oynanıyorsa 5 dakikada bir, değilse saatte bir.
        val yakin = eldeki?.any { it.canli || java.time.Duration.between(LocalDateTime.now(), it.zaman).toMinutes() in -150..30 } == true
        val sure = if (yakin) 5 * 60_000L else SURE_MS
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < sure) return
        Ag.arkada {
            val a = JSONArray()
            for ((ad, id) in TAKIMLAR) {
                runCatching { siradaki(ad, id) }.getOrNull()?.let { a.put(it) }
            }
            if (a.length() > 0) {
                f.writeText(a.toString())
                oku(f)?.let { Ag.anada { cb(it) } }
            }
        }
    }

    /** Takımın şu an oynanmamış ilk maçı (maç bitince detay sayfasında "sıradaki maç"). */
    fun sonraki(takim: String): Mac? {
        val id = TAKIMLAR.firstOrNull { it.first == takim }?.second ?: return null
        val o = siradaki(takim, id, sadeceGelecek = true) ?: return null
        return Mac(takim, o.getString("ev"), o.getString("dep"), o.optString("evLogo"), o.optString("depLogo"),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(o.getLong("zaman")), ZoneId.systemDefault()), o.optString("lig"),
            false, "", o.optString("id"), o.optString("ligKodu"))
    }

    private fun siradaki(ad: String, id: Int, sadeceGelecek: Boolean = false): JSONObject? {
        // Biten maçın sonucu bitişten (başlama + 2 sa) sonra 18 saat ekranda kalsın.
        if (!sadeceGelecek) runCatching { sonBiten(ad, id) }.getOrNull()?.let { return it }
        // Fikstür servisi sadece oynanmamış (ve oynanan) maçları veriyor.
        val d = JSONObject(Ag.metin("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/$id/schedule?fixture=true"))
        val olaylar = d.optJSONArray("events") ?: return null
        val simdi = Instant.now()
        for (i in 0 until olaylar.length()) {
            val e = olaylar.getJSONObject(i)
            val durum = durum(e)
            val zaman = zaman(e)
            if (durum == "post" || (sadeceGelecek && durum != "pre")) continue
            if (durum == "pre" && zaman.isBefore(simdi.minusSeconds(3 * 3600))) continue
            macJson(ad, e)?.let { return it }
        }
        return null
    }

    /** Sonuçlar servisinden en son biten maç; bitişinden 18 saat geçtiyse null. */
    private fun sonBiten(ad: String, id: Int): JSONObject? {
        val d = JSONObject(Ag.metin("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/$id/schedule"))
        val olaylar = d.optJSONArray("events") ?: return null
        val e = (0 until olaylar.length()).map { olaylar.getJSONObject(it) }
            .filter { durum(it) == "post" }.maxByOrNull { zaman(it) } ?: return null
        if (zaman(e).plusSeconds((2 + SONUC_SAAT) * 3600L).isBefore(Instant.now())) return null
        return macJson(ad, e)
    }

    private fun durum(e: JSONObject) = e.getJSONArray("competitions").getJSONObject(0)
        .optJSONObject("status")?.optJSONObject("type")?.optString("state") ?: "pre"

    private fun zaman(e: JSONObject): Instant = Instant.parse(e.getString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it })

    private fun macJson(ad: String, e: JSONObject): JSONObject? {
        val c = e.getJSONArray("competitions").getJSONObject(0)
        val durum = durum(e)
        val rakipler = c.getJSONArray("competitors")
        var ev: JSONObject? = null; var dep: JSONObject? = null
        for (j in 0 until rakipler.length()) {
            val r = rakipler.getJSONObject(j)
            if (r.optString("homeAway") == "home") ev = r else dep = r
        }
        if (ev == null || dep == null) return null
        fun takimAdi(r: JSONObject) = r.getJSONObject("team").optString("displayName").let { ADLAR[it] ?: it }
        fun logo(r: JSONObject) = r.getJSONObject("team").let { t ->
            t.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: t.optString("logo")
        }
        fun gol(r: JSONObject) = r.optJSONObject("score")?.optString("displayValue") ?: r.optString("score")
        val lig = e.optJSONObject("league")?.optString("name") ?: c.optJSONObject("league")?.optString("name") ?: ""
        val ligKodu = e.optJSONObject("league")?.optString("slug") ?: ""
        return JSONObject().put("takim", ad).put("ev", takimAdi(ev)).put("dep", takimAdi(dep))
            .put("evLogo", logo(ev)).put("depLogo", logo(dep)).put("zaman", zaman(e).toEpochMilli())
            .put("lig", LIGLER[lig] ?: lig).put("canli", durum == "in")
            .put("skor", if (durum == "in" || durum == "post") "${gol(ev)}-${gol(dep)}" else "")
            .put("bitti", durum == "post")
            .put("id", e.optString("id")).put("ligKodu", ligKodu)
    }

    private fun oku(f: java.io.File): List<Mac>? {
        if (!f.exists()) return null
        val a = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return null
        val bolge = ZoneId.systemDefault()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Mac(o.getString("takim"), o.getString("ev"), o.getString("dep"), o.optString("evLogo"), o.optString("depLogo"),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(o.getLong("zaman")), bolge), o.optString("lig"),
                o.optBoolean("canli"), o.optString("skor"), o.optString("id"), o.optString("ligKodu"), o.optBoolean("bitti"))
        }.sortedBy { it.zaman }
            // Derbide iki takımın "sıradaki maçı" aynı maç: bir kez göster.
            .distinctBy { "${it.ev}|${it.deplasman}|${it.zaman}" }
    }
}

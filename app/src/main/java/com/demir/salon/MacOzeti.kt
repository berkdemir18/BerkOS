package com.demir.salon

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class Taraf(val ad: String, val logo: String, val renk: Int, val skor: String, val id: String)
class Olay(val dakika: String, val tur: String, val oyuncu: String, val ev: Boolean)
class Oyuncu(val forma: String, val ad: String, val mevki: String, val ilk11: Boolean)
class Form(val sonuc: Char, val rakip: String, val skor: String)
class Sira(val sira: Int, val takim: String, val oynanan: String, val puan: String, val vurgu: Boolean)
class EskiMac(val tarih: String, val ev: String, val dep: String, val skor: String)

/**
 * ESPN'in maç özeti (summary servisi): skor, dakika, goller/kartlar, kadrolar, istatistikler, son 5 maç,
 * puan durumu, aralarındaki maçlar. Anahtar istemez.
 */
class MacOzeti(
    val ev: Taraf, val dep: Taraf,
    /** "pre" | "in" | "post" */
    val durum: String,
    val dakika: String,
    val zaman: LocalDateTime,
    val lig: String,
    val stat: String,
    val hakem: String,
    val olaylar: List<Olay>,
    val degisiklikler: List<Olay>,
    val evKadro: List<Oyuncu>, val depKadro: List<Oyuncu>,
    /** (ad, ev değeri, deplasman değeri) */
    val istatistik: List<Triple<String, Float, Float>>,
    val evForm: List<Form>, val depForm: List<Form>,
    val puan: List<Sira>,
    val aralarinda: List<EskiMac>,
) {
    companion object {
        fun getir(ligKodu: String, id: String): MacOzeti {
            val kod = ligKodu.ifBlank { "all" }
            return coz(JSONObject(Ag.metin("https://site.api.espn.com/apis/site/v2/sports/soccer/$kod/summary?event=$id")))
        }

        private fun renk(s: String?, yedek: Int): Int =
            runCatching { (0xFF000000 or s!!.toLong(16)).toInt() }.getOrDefault(yedek)

        private fun coz(d: JSONObject): MacOzeti {
            val c = d.getJSONObject("header").getJSONArray("competitions").getJSONObject(0)
            val durumJ = c.optJSONObject("status")
            val durum = durumJ?.optJSONObject("type")?.optString("state") ?: "pre"
            var ev: Taraf? = null; var dep: Taraf? = null
            val rakipler = c.getJSONArray("competitors")
            for (i in 0 until rakipler.length()) {
                val r = rakipler.getJSONObject(i)
                val t = r.getJSONObject("team")
                val logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: t.optString("logo")
                val taraf = Taraf(Maclar.turkce(t.optString("displayName")), logo, renk(t.optString("color"), 0xFFE8E0D4.toInt()),
                    r.optString("score"), t.optString("id"))
                if (r.optString("homeAway") == "home") ev = taraf else dep = taraf
            }
            ev!!; dep!!
            val zaman = LocalDateTime.ofInstant(Instant.parse(c.getString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }), ZoneId.systemDefault())
            val dakika = durumJ?.optString("displayClock").orEmpty()
            val lig = d.optJSONObject("header")?.optJSONObject("league")?.optString("name")
                ?: d.optJSONObject("standings")?.optString("header")?.removeSuffix(" Standings").orEmpty()
            val bilgi = d.optJSONObject("gameInfo")
            val stat = bilgi?.optJSONObject("venue")?.let { v -> listOf(v.optString("fullName"), v.optJSONObject("address")?.optString("city").orEmpty()).filter { it.isNotBlank() }.distinct().joinToString(", ") }.orEmpty()
            val hakem = bilgi?.optJSONArray("officials")?.optJSONObject(0)?.optString("displayName").orEmpty()

            // Önemli anlar: goller, kartlar; değişiklikler ayrı.
            val olaylar = mutableListOf<Olay>(); val degisiklik = mutableListOf<Olay>()
            d.optJSONArray("keyEvents")?.let { a ->
                for (i in 0 until a.length()) {
                    val k = a.getJSONObject(i)
                    val tur = k.optJSONObject("type")?.optString("text").orEmpty()
                    val oyuncu = k.optJSONArray("participants")?.optJSONObject(0)?.optJSONObject("athlete")?.optString("displayName").orEmpty()
                    val takim = k.optJSONObject("team")?.optString("id").orEmpty()
                    val dk = k.optJSONObject("clock")?.optString("displayValue").orEmpty()
                    val o = Olay(dk, tur, oyuncu, takim == ev.id)
                    when {
                        tur.contains("Goal", true) || tur.contains("Penalty - Scored", true) -> olaylar += Olay(dk, "gol", oyuncu, o.ev)
                        tur.contains("Yellow", true) -> olaylar += Olay(dk, "sari", oyuncu, o.ev)
                        tur.contains("Red", true) -> olaylar += Olay(dk, "kirmizi", oyuncu, o.ev)
                        tur.contains("Substitution", true) -> degisiklik += o
                    }
                }
            }
            fun kadro(id: String): List<Oyuncu> {
                val r = d.optJSONArray("rosters") ?: return emptyList()
                for (i in 0 until r.length()) {
                    val x = r.getJSONObject(i)
                    if (x.optJSONObject("team")?.optString("id") != id) continue
                    val l = x.optJSONArray("roster") ?: return emptyList()
                    return (0 until l.length()).map { l.getJSONObject(it) }.map { p ->
                        Oyuncu(p.optString("jersey"), p.optJSONObject("athlete")?.optString("displayName").orEmpty(),
                            p.optJSONObject("position")?.optString("abbreviation").orEmpty(), p.optBoolean("starter"))
                    }
                }
                return emptyList()
            }
            // İstatistikler: iki takımın aynı adlı değerleri yan yana.
            val ist = mutableListOf<Triple<String, Float, Float>>()
            d.optJSONObject("boxscore")?.optJSONArray("teams")?.let { tl ->
                fun degerler(id: String): Map<String, Float> {
                    for (i in 0 until tl.length()) {
                        val t = tl.getJSONObject(i)
                        if (t.optJSONObject("team")?.optString("id") != id) continue
                        val s = t.optJSONArray("statistics") ?: return emptyMap()
                        return (0 until s.length()).map { s.getJSONObject(it) }
                            .associate { it.optString("name") to (it.optString("displayValue").toFloatOrNull() ?: 0f) }
                    }
                    return emptyMap()
                }
                val e = degerler(ev.id); val de = degerler(dep.id)
                for ((anahtar, ad) in listOf("possessionPct" to "Topa sahip olma", "totalShots" to "Şut", "shotsOnTarget" to "İsabetli şut",
                    "wonCorners" to "Korner", "foulsCommitted" to "Faul", "offsides" to "Ofsayt")) {
                    if (anahtar in e || anahtar in de) ist += Triple(ad, e[anahtar] ?: 0f, de[anahtar] ?: 0f)
                }
            }
            fun form(id: String): List<Form> {
                val a = d.optJSONArray("lastFiveGames") ?: return emptyList()
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    if (x.optJSONObject("team")?.optString("id") != id) continue
                    val ev2 = x.optJSONArray("events") ?: return emptyList()
                    return (0 until ev2.length()).map { ev2.getJSONObject(it) }.map { g ->
                        Form(when (g.optString("gameResult")) { "W" -> 'G'; "L" -> 'M'; else -> 'B' },
                            Maclar.turkce(g.optJSONObject("opponent")?.optString("displayName").orEmpty()), g.optString("score"))
                    }
                }
                return emptyList()
            }
            // Puan durumu: iki takımın etrafı (birer üst-alt).
            val puan = mutableListOf<Sira>()
            d.optJSONObject("standings")?.optJSONArray("groups")?.optJSONObject(0)?.optJSONObject("standings")?.optJSONArray("entries")?.let { en ->
                val hepsi = (0 until en.length()).map { en.getJSONObject(it) }.map { x ->
                    val s = x.optJSONArray("stats")
                    fun st(n: String) = (0 until (s?.length() ?: 0)).map { s!!.getJSONObject(it) }.firstOrNull { it.optString("name") == n }?.optString("displayValue").orEmpty()
                    Triple(x.optString("id"), Maclar.turkce(x.optString("team")), Triple(st("rank").toIntOrNull() ?: 0, st("gamesPlayed"), st("points")))
                }.sortedBy { it.third.first }
                val onemli = hepsi.indices.filter { hepsi[it].first == ev.id || hepsi[it].first == dep.id }
                val secilen = onemli.flatMap { (it - 1)..(it + 1) }.filter { it in hepsi.indices }.distinct().sorted()
                for (i in secilen) {
                    val (tid, ad, s) = hepsi[i]
                    puan += Sira(s.first, ad, s.second, s.third, tid == ev.id || tid == dep.id)
                }
            }
            // Aralarındaki son maçlar.
            val aralarinda = mutableListOf<EskiMac>()
            d.optJSONArray("seasonseries")?.optJSONObject(0)?.optJSONArray("events")?.let { a ->
                for (i in 0 until a.length()) {
                    val g = a.getJSONObject(i)
                    if (g.optString("status") != "post") continue
                    val r = g.optJSONArray("competitors") ?: continue
                    var h: JSONObject? = null; var aw: JSONObject? = null
                    for (j in 0 until r.length()) r.getJSONObject(j).let { if (it.optString("homeAway") == "home") h = it else aw = it }
                    val hh = h ?: continue; val aa = aw ?: continue
                    fun ad(x: JSONObject) = Maclar.turkce(x.optJSONObject("team")?.optString("displayName").orEmpty())
                    aralarinda += EskiMac(g.optString("date").take(10), ad(hh), ad(aa), "${hh.optString("score")}-${aa.optString("score")}")
                }
            }
            return MacOzeti(ev, dep, durum, dakika, zaman, Maclar.ligAdi(lig), stat, hakem, olaylar, degisiklik,
                kadro(ev.id), kadro(dep.id), ist, form(ev.id), form(dep.id), puan, aralarinda.take(5))
        }
    }
}

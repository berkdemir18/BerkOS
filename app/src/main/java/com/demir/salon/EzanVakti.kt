package com.demir.salon

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

data class Vakit(val ad: String, val zaman: LocalDateTime)

/**
 * Diyanet usulü (Aladhan, method=13) namaz vakitleri. Bir ayın tamamı tek seferde indirilip saklanır,
 * yani ay içinde internet gitse de vakitler görünür.
 */
object EzanVakti {
    private val ADLAR = listOf("İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı")
    private val ANAHTARLAR = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")

    /** Bugünün 6 vakti + (varsa) yarınınkiler; Yatsı'dan sonra "sıradaki" yarının İmsak'ı olsun diye. */
    fun getir(ctx: Context, cb: (List<Vakit>) -> Unit) {
        val bugun = LocalDate.now()
        val yarin = bugun.plusDays(1)
        vakitler(ctx, bugun)?.let(cb)
        if (sakli(ctx, bugun) && sakli(ctx, yarin)) return
        Ag.arkada {
            if (!sakli(ctx, bugun)) ayIndir(ctx, bugun)
            if (!sakli(ctx, yarin)) ayIndir(ctx, yarin)
            vakitler(ctx, bugun)?.let { v -> Ag.anada { cb(v) } }
        }
    }

    /** İnternete çıkmadan, saklı olanlardan bugünün (ve yarının) vakitleri. */
    fun bugun(ctx: Context): List<Vakit>? = vakitler(ctx, LocalDate.now())

    private fun anahtar(g: LocalDate) = String.format(Locale.ROOT, "ezan_%d_%02d", g.year, g.monthValue)

    private fun sakli(ctx: Context, g: LocalDate) = Salon.ayarlar(ctx).contains(anahtar(g))

    private fun ayIndir(ctx: Context, g: LocalDate) {
        val k = Salon.konum(ctx)
        val j = Ag.metin(
            "https://api.aladhan.com/v1/calendar/${g.year}/${g.monthValue}" +
                "?latitude=${k.enlem}&longitude=${k.boylam}&method=13"
        )
        JSONObject(j).getJSONArray("data") // bozuk cevabı saklamayalım
        Salon.ayarlar(ctx).edit().putString(anahtar(g), j).apply()
    }

    private fun gun(ctx: Context, g: LocalDate): List<Vakit>? {
        val j = Salon.ayarlar(ctx).getString(anahtar(g), null) ?: return null
        val veri = runCatching { JSONObject(j).getJSONArray("data") }.getOrNull() ?: return null
        val aranan = String.format(Locale.ROOT, "%02d-%02d-%d", g.dayOfMonth, g.monthValue, g.year)
        for (i in 0 until veri.length()) {
            val e = veri.getJSONObject(i)
            if (e.getJSONObject("date").getJSONObject("gregorian").getString("date") != aranan) continue
            val t = e.getJSONObject("timings")
            return ANAHTARLAR.mapIndexed { n, k -> Vakit(ADLAR[n], g.atTime(LocalTime.parse(t.getString(k).take(5)))) }
        }
        return null
    }

    private fun vakitler(ctx: Context, bugun: LocalDate): List<Vakit>? =
        gun(ctx, bugun)?.let { it + (gun(ctx, bugun.plusDays(1)) ?: emptyList()) }
}

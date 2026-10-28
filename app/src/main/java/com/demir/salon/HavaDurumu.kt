package com.demir.salon

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

enum class HavaTuru { ACIK, AZ_BULUTLU, BULUTLU, SIS, YAGMUR, KAR, FIRTINA }

data class GunTahmini(val tarih: LocalDate, val kod: Int, val enYuksek: Int, val enDusuk: Int)

data class Hava(
    val sicaklik: Int,
    val hissedilen: Int,
    val kod: Int,
    val gunduz: Boolean,
    val gunler: List<GunTahmini>,
)

/** Open-Meteo'dan (anahtar istemez) seçili şehrin havası. Son sonuç önbellekte durur, internet giderse o gösterilir. */
object HavaDurumu {
    private const val TAZE_MS = 20 * 60_000L
    private fun adres(k: Konum) = "https://api.open-meteo.com/v1/forecast?latitude=${k.enlem}&longitude=${k.boylam}" +
        "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
        "&timezone=Europe%2FIstanbul&forecast_days=5"

    /** Önce önbellektekini, sonra (eskiyse) tazesini verir; [cb] iki kez çağrılabilir. */
    fun getir(ctx: Context, cb: (Hava) -> Unit) {
        val p = Salon.ayarlar(ctx)
        val onbellek = p.getString("hava_json", null)
        onbellek?.let { j -> runCatching { coz(j) }.getOrNull()?.let(cb) }
        if (onbellek != null && System.currentTimeMillis() - p.getLong("hava_zaman", 0) < TAZE_MS) return
        Ag.arkada {
            val j = Ag.metin(adres(Salon.konum(ctx)))
            val h = coz(j)
            p.edit().putString("hava_json", j).putLong("hava_zaman", System.currentTimeMillis()).apply()
            Ag.anada { cb(h) }
        }
    }

    private fun coz(j: String): Hava {
        val o = JSONObject(j)
        val c = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val t = d.getJSONArray("time")
        val k = d.getJSONArray("weather_code")
        val mx = d.getJSONArray("temperature_2m_max")
        val mn = d.getJSONArray("temperature_2m_min")
        val gunler = (0 until t.length()).map {
            GunTahmini(LocalDate.parse(t.getString(it)), k.getInt(it), mx.getDouble(it).roundToInt(), mn.getDouble(it).roundToInt())
        }
        return Hava(
            c.getDouble("temperature_2m").roundToInt(),
            c.getDouble("apparent_temperature").roundToInt(),
            c.getInt("weather_code"),
            c.getInt("is_day") == 1,
            gunler,
        )
    }

    fun aciklama(kod: Int): String = when (kod) {
        0 -> "Açık"
        1 -> "Az bulutlu"
        2 -> "Parçalı bulutlu"
        3 -> "Kapalı"
        45, 48 -> "Sisli"
        51, 53, 55 -> "Çisenti"
        56, 57 -> "Dondurucu çisenti"
        61 -> "Hafif yağmurlu"
        63 -> "Yağmurlu"
        65 -> "Kuvvetli yağmur"
        66, 67 -> "Dondurucu yağmur"
        71 -> "Hafif kar"
        73 -> "Karlı"
        75 -> "Yoğun kar"
        77 -> "Kar taneleri"
        80 -> "Hafif sağanak"
        81 -> "Sağanak yağış"
        82 -> "Şiddetli sağanak"
        85, 86 -> "Kar sağanağı"
        95 -> "Gök gürültülü fırtına"
        96, 99 -> "Dolulu fırtına"
        else -> "—"
    }

    fun tur(kod: Int): HavaTuru = when (kod) {
        0 -> HavaTuru.ACIK
        1, 2 -> HavaTuru.AZ_BULUTLU
        3 -> HavaTuru.BULUTLU
        45, 48 -> HavaTuru.SIS
        in 51..67, in 80..82 -> HavaTuru.YAGMUR
        in 71..77, 85, 86 -> HavaTuru.KAR
        in 95..99 -> HavaTuru.FIRTINA
        else -> HavaTuru.BULUTLU
    }
}

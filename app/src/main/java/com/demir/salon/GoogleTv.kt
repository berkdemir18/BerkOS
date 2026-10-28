package com.demir.salon

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Google TV ana ekranının "İzlemeye devam edin" satırındaki bir kart. */
class GtvKart(val baslik: String, val uygulama: String, val ayrinti: String)

/**
 * Google TV, Netflix ve Prime'ın "izlemeye devam" bilgisini kendi sunucusundan alıyor; başka uygulamaya
 * vermiyor. Ama kendi ana ekranında gösteriyor: Ev tuşu yardımcısı o ekran her göründüğünde (TV açılınca,
 * Ev tuşuna basınca) satırdaki kartların adlarını okur, BerkOS aynı sırayla kendi kartlarını çizer.
 * Sadece bu satırın yazıları okunur; başka uygulamanın ekranına bakılmaz.
 */
object GoogleTv {
    private val SATIR_ADLARI = setOf("İzlemeye devam edin", "Continue watching")

    /** Satır bulunup okunduysa true. */
    fun oku(ctx: Context, kok: AccessibilityNodeInfo?): Boolean {
        val satir = kok?.let { bul(it, 0) } ?: return false
        val kartlar = mutableListOf<GtvKart>()
        topla(satir, kartlar, 0)
        if (kartlar.isEmpty()) return false
        // Google'ın ekranında ilk beş-altı kart görünür; eskiler silinmesin, yenilerin arkasında kalsın.
        val simdi = System.currentTimeMillis()
        val a = JSONArray()
        val gorulen = HashSet<String>()
        for (k in kartlar) {
            if (!gorulen.add(k.baslik + "|" + k.uygulama)) continue
            a.put(JSONObject().put("b", k.baslik).put("u", k.uygulama).put("a", k.ayrinti).put("t", simdi))
        }
        val eski = runCatching { JSONArray(Salon.ayarlar(ctx).getString("gtv_devam", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until eski.length()) {
            val o = eski.getJSONObject(i)
            if (a.length() >= 20) break
            if (simdi - o.optLong("t", simdi) > 21 * 86_400_000L) continue
            if (gorulen.add(o.getString("b") + "|" + o.getString("u"))) a.put(o)
        }
        Salon.ayarlar(ctx).edit().putString("gtv_devam", a.toString()).putLong("gtv_zaman", System.currentTimeMillis()).apply()
        return true
    }

    fun kartlar(ctx: Context): Pair<List<GtvKart>, Long> {
        val p = Salon.ayarlar(ctx)
        val a = runCatching { JSONArray(p.getString("gtv_devam", "[]")) }.getOrDefault(JSONArray())
        val liste = (0 until a.length()).map { a.getJSONObject(it).let { o -> GtvKart(o.getString("b"), o.getString("u"), o.optString("a")) } }
        return liste to p.getLong("gtv_zaman", 0)
    }

    private fun bul(n: AccessibilityNodeInfo, derinlik: Int): AccessibilityNodeInfo? {
        if (derinlik > 30) return null
        if (n.contentDescription?.toString() in SATIR_ADLARI && n.childCount > 0) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            bul(c, derinlik + 1)?.let { return it }
        }
        return null
    }

    /** Kart yazısı: "Organize İşler, Netflix • 1. Sezon, 3. Bölüm" ya da "Video adı, YouTube • Kanal". */
    private fun topla(n: AccessibilityNodeInfo, liste: MutableList<GtvKart>, derinlik: Int) {
        if (derinlik > 12) return
        val d = n.contentDescription?.toString()
        if (d != null && " • " in d) {
            val nokta = d.indexOf(" • ")
            val sol = d.substring(0, nokta)
            val virgul = sol.lastIndexOf(", ")
            if (virgul > 0) {
                liste += GtvKart(sol.substring(0, virgul).trim(), sol.substring(virgul + 2).trim(), d.substring(nokta + 3).trim())
                return
            }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { topla(it, liste, derinlik + 1) }
    }
}

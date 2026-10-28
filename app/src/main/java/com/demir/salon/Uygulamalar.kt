package com.demir.salon

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import java.text.Collator

class Uygulama(val paket: String, val ad: String, val bilgi: ActivityInfo, val tvUygulamasi: Boolean)

object Uygulamalar {
    /** İlk açılışta, yüklüyse dock'a bu sırayla konur. Sonra kullanıcı nasıl dizerse öyle kalır. */
    private val VARSAYILAN_DOCK = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        RadyoKoprusu.PAKET,
        "com.google.android.youtube.tvmusic",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.wbd.stream",
        "com.spotify.tv.android",
        "com.google.android.tv",
    )
    private const val DOCK_ILK_SAYI = 6

    /** TV'de yüklü, açılabilen bütün uygulamalar (TV uygulamaları + telefon uygulamaları), alfabetik. */
    fun hepsi(ctx: Context): List<Uygulama> {
        val pm = ctx.packageManager
        val bulunan = LinkedHashMap<String, Uygulama>()
        for ((kategori, tv) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(kategori), 0)) {
                val p = r.activityInfo.packageName
                if (p == ctx.packageName || p in bulunan || p in Salon.STOK_ANA_EKRANLAR) continue
                bulunan[p] = Uygulama(p, r.loadLabel(pm).toString().trim(), r.activityInfo, tv)
            }
        }
        val siralayici = Collator.getInstance(Salon.TR)
        return bulunan.values.sortedWith { a, b -> siralayici.compare(ekrandakiAd(a), ekrandakiAd(b)) }
    }

    /**
     * Sıralama afişte yazan ada göre: bazı uygulamaların Türkçe etiketi afişindekinden farklı
     * ("Dosya Yöneticisi+" afişte "File Manager +", "Play Store" afişte "Google Play Store").
     */
    private val AFISTEKI_AD = mapOf(
        "com.alphainventor.filemanager" to "File Manager +",
        "com.google.android.videos" to "Google Play Movies",
        "com.google.android.play.games" to "Google Play Games",
        "com.android.vending" to "Google Play Store",
    )

    private fun ekrandakiAd(u: Uygulama) = AFISTEKI_AD[u.paket] ?: u.ad

    fun ac(ctx: Context, u: Uygulama) {
        val pm = ctx.packageManager
        val niyet = (if (u.tvUygulamasi) pm.getLeanbackLaunchIntentForPackage(u.paket) else null)
            ?: pm.getLaunchIntentForPackage(u.paket)
            ?: return
        runCatching { ctx.startActivity(niyet.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun banner(ctx: Context, u: Uygulama): Drawable? {
        val pm = ctx.packageManager
        return u.bilgi.loadBanner(pm) ?: u.bilgi.applicationInfo.loadBanner(pm)
    }

    fun ikon(ctx: Context, u: Uygulama): Drawable = u.bilgi.loadIcon(ctx.packageManager)

    fun dockPaketleri(ctx: Context, hepsi: List<Uygulama>): List<String> {
        val p = Salon.ayarlar(ctx)
        val kayitli = p.getString("dock", null)
        if (kayitli != null) {
            val liste = kayitli.split(',').filter { it.isNotBlank() }.toMutableList()
            // Radyo sonradan kuruldu: bir kereliğine üçüncü sıraya eklenir; kullanıcı kaldırırsa bir daha gelmez.
            if (!p.getBoolean("radyo_dockta", false) && hepsi.any { it.paket == RadyoKoprusu.PAKET }) {
                p.edit().putBoolean("radyo_dockta", true).apply()
                if (RadyoKoprusu.PAKET !in liste) { liste.add(minOf(2, liste.size), RadyoKoprusu.PAKET); dockKaydet(ctx, liste) }
            }
            return liste
        }
        val yuklu = hepsi.map { it.paket }.toSet()
        val v = VARSAYILAN_DOCK.filter { it in yuklu }.take(DOCK_ILK_SAYI).toMutableList()
        for (u in hepsi) {
            if (v.size >= DOCK_ILK_SAYI) break
            if (u.tvUygulamasi && u.paket !in v) v += u.paket
        }
        return v
    }

    fun dock(ctx: Context, hepsi: List<Uygulama>): List<Uygulama> {
        val harita = hepsi.associateBy { it.paket }
        return dockPaketleri(ctx, hepsi).mapNotNull { harita[it] }
    }

    fun dockKaydet(ctx: Context, paketler: List<String>) {
        Salon.ayarlar(ctx).edit().putString("dock", paketler.joinToString(",")).apply()
    }
}

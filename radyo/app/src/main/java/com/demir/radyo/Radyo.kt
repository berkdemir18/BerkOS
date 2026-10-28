package com.demir.radyo

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Çalan radyonun durumu ve komutlar. Ekranlar buradan okur ve [dinle] ile değişikliği duyar; komutlar
 * servise gider. Servisi bir MediaController ayakta tutar (bağlı kaldıkça Android servisi kapatmaz).
 */
object Radyo {
    @Volatile internal var servis: RadyoServisi? = null

    var istasyon: Istasyon? = null; private set
    var caliyor = false; private set
    var yukleniyor = false; private set
    var duraklatildi = false; private set
    var hata: String? = null; private set
    var sarki = ""; private set
    /** Uyku zamanlayıcısı: radyonun susacağı an (ms), 0 = kapalı. */
    var uykuBitis = 0L; private set

    val UYKU_SURELERI = listOf(0, 15, 30, 60, 90)

    private val ana = Handler(Looper.getMainLooper())
    private val dinleyiciler = LinkedHashSet<() -> Unit>()
    private var baglanti: ListenableFuture<MediaController>? = null
    private val bekleyen = ArrayList<(RadyoServisi) -> Unit>()
    private var gecisGorevi: Runnable? = null

    fun dinle(f: () -> Unit) { dinleyiciler += f }
    fun birak(f: () -> Unit) { dinleyiciler -= f }
    private fun haber() { for (f in dinleyiciler.toList()) f() }

    // ---------------------------------------------------------------- servisten gelen

    internal fun durumDegisti(ist: Istasyon?, caliyor: Boolean, yukleniyor: Boolean, hata: String?, duraklatildi: Boolean = false) {
        ana.post {
            // İstasyon geçişi bekliyorsa (kumandayla hızlı hızlı değiştiriliyor) ekrandaki ad geri atlamasın.
            if (gecisGorevi == null) istasyon = ist
            this.caliyor = caliyor
            this.yukleniyor = yukleniyor
            this.hata = hata
            this.duraklatildi = duraklatildi
            haber()
        }
    }

    internal fun sarkiDegisti(s: String) {
        ana.post {
            val ad = istasyon?.ad.orEmpty()
            // Bazı radyolar şarkı yerine kendi adını ya da reklam metnini yolluyor.
            val temiz = s.trim().trim('-', '–', ' ')
            sarki = if (temiz.length < 3 || temiz.equals(ad, true) || ad.isNotEmpty() && temiz.lowercase(Renk.TR).startsWith(ad.lowercase(Renk.TR))) "" else temiz
            haber()
        }
    }

    internal fun uykuBitti() { uykuBitis = 0; haber() }

    // ---------------------------------------------------------------- komutlar

    private fun servisle(ctx: Context, is_: (RadyoServisi) -> Unit) {
        servis?.let { is_(it); return }
        bekleyen += is_
        if (baglanti != null) return
        val uyg = ctx.applicationContext
        baglanti = MediaController.Builder(uyg, SessionToken(uyg, ComponentName(uyg, RadyoServisi::class.java))).buildAsync().also { f ->
            f.addListener({
                if (runCatching { f.get() }.isFailure) baglanti = null
                val s = servis ?: return@addListener
                val liste = bekleyen.toList(); bekleyen.clear()
                for (b in liste) b(s)
            }, { ana.post(it) })
        }
    }

    /** [sira]: radyonun seçildiği liste (kategori); sonraki/önceki bu listede gezer. */
    fun cal(ctx: Context, ist: Istasyon, sira: List<String>? = null) {
        gecisGorevi?.let { ana.removeCallbacks(it) }; gecisGorevi = null
        val p = Renk.ayarlar(ctx).edit().putString("son", ist.id)
        if (sira != null) p.putString("sira", sira.joinToString(","))
        p.apply()
        istasyon = ist; hata = null; sarki = ""; yukleniyor = true; caliyor = false; duraklatildi = false
        haber()
        servisle(ctx) { it.cal(ist) }
    }

    /** Ekrandaki radyo hemen değişir, çalma kumanda durunca başlar (hızlı geçerken her radyo tek tek açılmasın). */
    fun sonraki(ctx: Context, yon: Int) {
        val liste = sira(ctx)
        if (liste.isEmpty()) return
        val simdiki = liste.indexOfFirst { it.id == istasyon?.id }
        val yeni = liste[Math.floorMod(if (simdiki < 0) 0 else simdiki + yon, liste.size)]
        istasyon = yeni; sarki = ""; hata = null; yukleniyor = true; caliyor = false
        haber()
        gecisGorevi?.let { ana.removeCallbacks(it) }
        gecisGorevi = Runnable { gecisGorevi = null; cal(ctx, yeni) }.also { ana.postDelayed(it, 650) }
    }

    fun calDurdur(ctx: Context) {
        when {
            caliyor || yukleniyor -> servisle(ctx) { it.duraklat() }
            istasyon != null -> servisle(ctx) { it.devam() }
            else -> sonIstasyon(ctx)?.let { cal(ctx, it) }
        }
    }

    fun kapat(ctx: Context) {
        uykuBitis = 0
        servisle(ctx) { it.kapat() }
    }

    /** Uyku: kapalı → 15 → 30 → 60 → 90 dk → kapalı. Döner: yeni süre (dk). */
    fun uykuDegistir(ctx: Context): Int {
        val simdiki = if (uykuBitis == 0L) 0 else UYKU_SURELERI.firstOrNull { it * 60_000L >= uykuBitis - System.currentTimeMillis() - 30_000 } ?: 0
        val yeni = UYKU_SURELERI[(UYKU_SURELERI.indexOf(simdiki) + 1) % UYKU_SURELERI.size]
        uykuBitis = if (yeni == 0) 0 else System.currentTimeMillis() + yeni * 60_000L
        val b = uykuBitis
        servisle(ctx) { it.uyku(b) }
        haber()
        return yeni
    }

    fun sonIstasyon(ctx: Context): Istasyon? = Radyolar.bul(ctx, Renk.ayarlar(ctx).getString("son", null))

    /** Sonraki/önceki hangi listede: son seçilen kategori; yoksa favoriler, o da yoksa popüler. */
    private fun sira(ctx: Context): List<Istasyon> {
        val kayitli = Renk.ayarlar(ctx).getString("sira", "")!!.split(',').filter { it.isNotBlank() }
        val h = Radyolar.hepsi(ctx)
        val liste = kayitli.mapNotNull { k -> h.firstOrNull { it.id == k } }
        if (liste.isNotEmpty()) return liste
        return Radyolar.kategori(ctx, "favori").ifEmpty { Radyolar.kategori(ctx, "populer") }
    }
}

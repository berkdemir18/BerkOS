package com.demir.salon

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** TV'de oynatılmış bir şey: dizi bölümü, film, YouTube videosu. */
class Izlenen(
    val paket: String,
    val baslik: String,
    val alt: String,
    val medyaId: String?,
    var konum: Long,
    var sure: Long,
    var son: Long,
    var resim: String?,
) {
    val anahtar get() = "$paket|$baslik"

    /** Ne oynadığını söylemeyen uygulama (Netflix, Prime): sadece "bu uygulamada izleniyordu" bilinir. */
    val genel get() = medyaId == GENEL

    /** YouTube video kimliği (11 karakter) biliniyorsa. */
    val videoId get() = medyaId?.takeIf { paket == YOUTUBE && Regex("[A-Za-z0-9_-]{11}").matches(it) }

    /** YouTube Shorts: 3 dakikadan kısa video ya da başlığında #shorts geçen. */
    val shorts get() = paket == YOUTUBE && ((sure in 1..180_000) || baslik.contains("#shorts", ignoreCase = true))

    /** Bitmemiş mi: en az bir dakika izlenmiş, sonuna gelinmemiş. Süresi bilinmeyenler de sayılır. Shorts hiç girmez. */
    val yarim get() = !shorts && konum >= 60_000 && (sure <= 0 || konum < sure * 0.94)

    fun json() = JSONObject().put("p", paket).put("b", baslik).put("a", alt).put("i", medyaId ?: "")
        .put("k", konum).put("s", sure).put("t", son).put("r", resim ?: "")

    companion object {
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val GENEL = "#genel"
        fun oku(o: JSONObject) = Izlenen(
            o.getString("p"), o.getString("b"), o.optString("a"), o.optString("i").ifBlank { null },
            o.optLong("k"), o.optLong("s"), o.optLong("t"), o.optString("r").ifBlank { null },
        )
    }
}

/**
 * "İzlemeye devam et" kaydı. Netflix, YouTube, Prime gibi uygulamalar oynattıkları şeyi Android'e
 * "şu an çalıyor" diye bildirir (kumanda, Asistan bunun için kullanır). [MedyaDinleyici] bu bildirimi
 * dinler; başlık, kapak resmi, kaldığı dakika TV'nin içinde saklanır, hiçbir yere gönderilmez.
 */
object Izlenenler {
    private const val TAVAN = 60

    /** Müzik uygulamaları "izlemeye devam et"e girmesin. */
    private val MUZIK = setOf(
        RadyoKoprusu.PAKET,
        "com.google.android.youtube.tvmusic",
        "com.spotify.tv.android",
        "com.google.android.music",
        "deezer.android.tv",
    )

    /** Uygulama hâlâ açıksa kaldığı yere tam dönen oynatıcı ekranı (sadece bellekte durur). */
    val oturumlar = HashMap<String, PendingIntent>()

    /** Her uygulamada en son ne oynuyordu, hangi durumdaydı: başlık değişince eskisini son saniyesiyle kapatmak için. */
    private val sonDurum = HashMap<String, Pair<String, PlaybackState>>()

    @Volatile private var bellek: MutableList<Izlenen>? = null
    private fun dosya(ctx: Context) = File(ctx.filesDir, "izlenenler.json")
    fun kapakKlasoru(ctx: Context) = File(ctx.filesDir, "kapak").apply { mkdirs() }

    @Synchronized
    fun hepsi(ctx: Context): MutableList<Izlenen> {
        bellek?.let { return it }
        val liste = runCatching {
            val a = JSONArray(dosya(ctx).readText())
            MutableList(a.length()) { Izlenen.oku(a.getJSONObject(it)) }
        }.getOrDefault(mutableListOf())
        bellek = liste
        // İlk sürüm ekrandan okunan oynatıcıların alt satırını (kaynak sitesi + çözünürlük) kaydetmişti; eskileri de temizle.
        var temizlendi = false
        for ((i, x) in liste.withIndex()) {
            if (x.paket in Ek.EKRANDAN_OKUNAN && kaynakYazisi(x.alt)) {
                liste[i] = Izlenen(x.paket, x.baslik, "", x.medyaId, x.konum, x.sure, x.son, x.resim)
                temizlendi = true
            }
        }
        if (temizlendi) yaz(ctx)
        return liste
    }

    /** "RoketDizi • FourPichive - 1920x1080" gibi: dizi adı değil, kaynak bilgisi. */
    private fun kaynakYazisi(s: String) = " • " in s || KAYNAK.containsMatchIn(s)
    private val KAYNAK = Regex("\\d{3,4}x\\d{3,4}")

    /** Yarım kalanlar, en son izlenen başta. */
    fun devamEt(ctx: Context): List<Izlenen> = synchronized(this) { hepsi(ctx).filter { it.yarim }.sortedByDescending { it.son } }

    /** Belli bir uygulamada izlenen başlıklar (öneriler bunlara göre yapılır). */
    fun uygulamada(ctx: Context, paket: String): List<Izlenen> = synchronized(this) {
        hepsi(ctx).filter { it.paket == paket }.sortedByDescending { it.son }
    }

    /**
     * Ev tuşu yardımcısı bir uygulamanın [sure] boyunca önde kaldığını haber verir. O sırada uygulama
     * ne izlendiğini bildirmediyse (Netflix, Prime), uygulamanın kendisi "kaldığın yerden devam" olur.
     */
    @Synchronized
    fun onde(ctx: Context, paket: String, baslangic: Long, sure: Long) {
        if (sure < 2 * 60_000 || paket in MUZIK) return
        val liste = hepsi(ctx)
        if (liste.any { it.paket == paket && !it.genel && it.son >= baslangic }) return
        val ad = runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(paket, 0)).toString() } }
            .getOrNull() ?: return
        val eski = liste.firstOrNull { it.paket == paket && it.genel }
        if (eski != null) liste.remove(eski)
        liste.add(0, Izlenen(paket, ad, "", Izlenen.GENEL, sure, 0, System.currentTimeMillis(), null))
        yaz(ctx)
    }

    /** Uygulamanın ekranında en son görülen yapım adı (dizi/film sayfası ya da oynatıcı). */
    fun ekranBasligiKaydet(ctx: Context, paket: String, baslik: String, alt: String) {
        Salon.ayarlar(ctx).edit().putString("ekran_baslik_$paket",
            JSONObject().put("b", baslik).put("a", alt).put("t", System.currentTimeMillis()).toString()).apply()
    }

    /** Dizi/film sayfasında görülen yapım adı (oynatıcıdaki bölüm adının hangi diziye ait olduğu). */
    fun yapimKaydet(ctx: Context, paket: String, ad: String) {
        Salon.ayarlar(ctx).edit().putString("ekran_yapim_$paket",
            JSONObject().put("b", ad).put("t", System.currentTimeMillis()).toString()).apply()
    }

    fun yapim(ctx: Context, paket: String): String? {
        val o = runCatching { JSONObject(Salon.ayarlar(ctx).getString("ekran_yapim_$paket", null) ?: return null) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - o.optLong("t") > 6 * 60 * 60_000L) return null
        return o.optString("b").takeIf { it.isNotBlank() }
    }

    /** Son 3 saatte görülmüşse (ad, ayrıntı). */
    fun ekranBasligi(ctx: Context, paket: String): Pair<String, String>? {
        val o = runCatching { JSONObject(Salon.ayarlar(ctx).getString("ekran_baslik_$paket", null) ?: return null) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - o.optLong("t") > 3 * 60 * 60_000L) return null
        return o.optString("b").takeIf { it.isNotBlank() }?.let { it to o.optString("a") }
    }

    /**
     * Oynatıcısı Android'e bilgi vermeyen uygulamalar ([Ek.EKRANDAN_OKUNAN]) için: ekranındaki başlık ve süreden.
     * [konum]/[sure] milisaniye; bilinmiyorsa 0.
     */
    @Synchronized
    fun ekrandan(ctx: Context, paket: String, baslik: String, alt: String, konum: Long, sure: Long) {
        if (baslik.isBlank()) return
        val liste = hepsi(ctx)
        // İlk sürüm bölüm adını başlık, kaynak bilgisini alt satır diye kaydetmişti; onları temizle.
        liste.removeAll { it.paket == paket && kaynakYazisi(it.alt) }
        val eski = liste.firstOrNull { it.paket == paket && it.baslik == baslik }
        if (eski != null) {
            if (konum > 0) eski.konum = konum
            if (sure > 0) eski.sure = sure
            eski.son = System.currentTimeMillis()
            liste.remove(eski); liste.add(0, eski)
        } else {
            liste.add(0, Izlenen(paket, baslik, alt, null, konum, sure, System.currentTimeMillis(), null))
            while (liste.size > TAVAN) liste.removeAt(liste.size - 1)
        }
        yaz(ctx)
    }

    @Synchronized
    fun sil(ctx: Context, anahtar: String) {
        hepsi(ctx).removeAll { it.anahtar == anahtar }
        yaz(ctx)
    }

    /** Bir oynatıcıdan gelen anlık durumu işler. Kapak resmi gerekiyorsa ayrıca kaydedilir. */
    fun guncelle(ctx: Context, paket: String, m: MediaMetadata?, d: PlaybackState?, ekran: PendingIntent?) {
        if (m == null || paket == ctx.packageName || paket in MUZIK) return
        var baslik = (m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_TITLE))
            ?.trim().orEmpty()
        var alt = listOf(
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_AUTHOR,
        ).firstNotNullOfOrNull { m.getString(it)?.trim()?.takeIf { s -> s.isNotEmpty() && s != baslik } }.orEmpty()
        // Bazı oynatıcıların oturumunda ad yok, sadece dakika var: adı az önce açılan dizi/film sayfasından al.
        if (baslik.isEmpty()) ekranBasligi(ctx, paket)?.let { (b, a) -> baslik = b; if (alt.isEmpty()) alt = a }
        if (baslik.isEmpty()) return
        val sure = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val konum = d?.let { anlikKonum(it) } ?: 0L
        // YouTube sıradaki videoya geçince önceki videonun kaldığı yer kaybolmasın.
        sonDurum[paket]?.let { (eskiBaslik, eskiDurum) ->
            if (eskiBaslik != baslik) synchronized(this) {
                hepsi(ctx).firstOrNull { it.paket == paket && it.baslik == eskiBaslik }?.let { e ->
                    val k = anlikKonum(eskiDurum)
                    if (k > e.konum) { e.konum = k; yaz(ctx) }
                }
            }
        }
        if (d != null && d.state != PlaybackState.STATE_NONE) sonDurum[paket] = baslik to d
        ekran?.let { oturumlar["$paket|$baslik"] = it }

        val yeni: Izlenen
        synchronized(this) {
            val liste = hepsi(ctx)
            val eski = liste.firstOrNull { it.paket == paket && it.baslik == baslik }
            val oynuyor = d?.state == PlaybackState.STATE_PLAYING
            if (eski != null) {
                if (konum > 0) eski.konum = konum
                if (sure > 0) eski.sure = sure
                if (oynuyor || konum > 0) eski.son = System.currentTimeMillis()
                liste.remove(eski); liste.add(0, eski)
                yeni = eski
            } else {
                // Sadece oynatılmaya başlanmış şeyler kaydedilir; menüde üstünden geçilenler değil.
                if (!oynuyor && konum < 60_000) return
                yeni = Izlenen(paket, baslik, alt, m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    konum, sure, System.currentTimeMillis(), null)
                liste.add(0, yeni)
                while (liste.size > TAVAN) liste.removeAt(liste.size - 1).resim?.let { File(kapakKlasoru(ctx), it).delete() }
            }
            yaz(ctx)
        }
        if (yeni.resim == null) kapakKaydet(ctx, yeni, m)
    }

    private fun anlikKonum(d: PlaybackState): Long {
        var k = d.position
        if (d.state == PlaybackState.STATE_PLAYING && d.lastPositionUpdateTime > 0) {
            k += ((SystemClock.elapsedRealtime() - d.lastPositionUpdateTime) * d.playbackSpeed).toLong()
        }
        return k.coerceAtLeast(0)
    }

    private fun kapakKaydet(ctx: Context, x: Izlenen, m: MediaMetadata) {
        val bmp = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { runCatching { m.getBitmap(it) }.getOrNull() }
        val uri = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { m.getString(it)?.takeIf { s -> s.startsWith("http") } }
        if (bmp == null && uri == null) return
        val ad = "k" + Integer.toHexString(x.anahtar.hashCode()) + ".jpg"
        val hedef = File(kapakKlasoru(ctx), ad)
        // Bitmap'i hemen kopyala: oynatıcı birazdan geri dönüştürebilir.
        val kopya = bmp?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        Ag.arkada {
            val ok = if (kopya != null) {
                val olcek = minOf(1f, 640f / kopya.width)
                val k = if (olcek < 1f) Bitmap.createScaledBitmap(kopya, 640, (kopya.height * olcek).toInt().coerceAtLeast(1), true) else kopya
                hedef.outputStream().use { k.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            } else {
                runCatching { Ag.dosyaya(uri!!, hedef) }.isSuccess
            }
            if (ok) synchronized(this) { x.resim = ad; yaz(ctx) }
        }
    }

    private val ana = Handler(Looper.getMainLooper())
    @Volatile private var yazmaBekliyor = false

    /**
     * Diske yazmayı 1,5 sn sonraya, arka plana bırak: oynatıcılar saniyede birkaç kez durum bildirebiliyor,
     * her birinde ana iş parçacığında dosya yazmak TV'yi yoruyordu. Arada gelenler tek yazmada birleşir.
     */
    private fun yaz(ctx: Context) {
        if (yazmaBekliyor) return
        yazmaBekliyor = true
        val uygulama = ctx.applicationContext
        ana.postDelayed({ Ag.arkada { yazmaBekliyor = false; hemenYaz(uygulama) } }, 1500)
    }

    @Synchronized
    private fun hemenYaz(ctx: Context) {
        val a = JSONArray()
        for (x in hepsi(ctx)) a.put(x.json())
        val gecici = File(dosya(ctx).path + ".part")
        gecici.writeText(a.toString())
        gecici.renameTo(dosya(ctx))
    }
}

/**
 * Bildirim dinleyicisi olarak açılır (kur.ps1 adb ile açıyor); bildirimlere bakmaz, sadece Android'in
 * "şu an çalıyor" listesine erişmek için bu kapıdan girer.
 */
class MedyaDinleyici : NotificationListenerService() {
    private val ana = Handler(Looper.getMainLooper())
    private val takip = HashMap<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var yonetici: MediaSessionManager? = null

    private val degisti = MediaSessionManager.OnActiveSessionsChangedListener { bagla(it ?: emptyList()) }

    /** Oynarken dakikada bir kaldığı yeri kaydet; TV aniden kapanırsa da kaybolmasın. */
    private val tik = object : Runnable {
        override fun run() {
            for ((c, _) in takip.values) if (c.playbackState?.state == PlaybackState.STATE_PLAYING) kaydet(c)
            ana.postDelayed(this, 60_000)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val y = getSystemService(MediaSessionManager::class.java) ?: return
        yonetici = y
        val ben = ComponentName(this, MedyaDinleyici::class.java)
        runCatching {
            y.addOnActiveSessionsChangedListener(degisti, ben, ana)
            bagla(y.getActiveSessions(ben))
        }.onFailure { Log.w("BerkOSMedya", "oturumlara erişilemedi", it) }
        ana.removeCallbacks(tik)
        ana.postDelayed(tik, 60_000)
    }

    override fun onListenerDisconnected() {
        RadyoKoprusu.oturumlar(emptyList())
        runCatching { yonetici?.removeOnActiveSessionsChangedListener(degisti) }
        for ((c, cb) in takip.values) { kaydet(c); c.unregisterCallback(cb) }
        takip.clear()
        ana.removeCallbacks(tik)
        super.onListenerDisconnected()
    }

    private fun bagla(liste: List<MediaController>) {
        RadyoKoprusu.oturumlar(liste)
        val simdiki = liste.associateBy { it.sessionToken }
        for (t in takip.keys.toList()) if (t !in simdiki) {
            takip.remove(t)?.let { (c, cb) -> kaydet(c); c.unregisterCallback(cb) }
        }
        for ((t, c) in simdiki) {
            if (t in takip) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(m: MediaMetadata?) = kaydet(c)
                override fun onPlaybackStateChanged(d: PlaybackState?) = kaydet(c)
                override fun onSessionDestroyed() { kaydet(c) }
            }
            c.registerCallback(cb, ana)
            takip[t] = c to cb
            kaydet(c)
        }
    }

    private fun kaydet(c: MediaController) {
        runCatching { Izlenenler.guncelle(this, c.packageName, c.metadata, c.playbackState, c.sessionActivity) }
    }
}

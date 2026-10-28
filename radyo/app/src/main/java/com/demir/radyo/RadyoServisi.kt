package com.demir.radyo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Radyoyu çalan servis. Ekran (Radyo ya da BerkOS) kapansa da çalmaya devam eder; kumandadaki oynat/durdur
 * tuşu ve Google TV'nin kendi "şu an çalıyor" kartı da buraya gelir.
 *
 * Bir adres açılmazsa ya da yayın koparsa radyonun yedek adreslerine geçer; hepsi ölmüşse Radio Browser'dan
 * yeni adres arar. TV kapanınca (bekleme) susar: kapalı ekranda boşuna çalmasın.
 */
@OptIn(UnstableApi::class)
class RadyoServisi : MediaSessionService() {
    private var oturum: MediaSession? = null
    private lateinit var oynatici: ExoPlayer
    private val ana = Handler(Looper.getMainLooper())

    private var istasyon: Istasyon? = null
    private var adresSira = 0
    private var deneme = 0
    private var onarildi = false

    private val ekranKapandi = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (oynatici.playWhenReady) oynatici.pause() }
    }

    private val uykuGorevi = Runnable { uykuBitti() }

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("BerkOSRadyo/1.0 (Google TV)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
        oynatici = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        oynatici.addListener(dinleyici)
        val ac = PendingIntent.getActivity(this, 0, Intent(this, RadyoActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        oturum = MediaSession.Builder(this, disaAcik()).setCallback(geriCagri).setSessionActivity(ac).build()
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ekranKapandi, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(ekranKapandi, f)
        Radyo.servis = this
    }

    /**
     * Oturumun dışarıya (BerkOS ana ekranı, Google TV, kumanda) gösterdiği oynatıcı: tek oğe olsa da
     * ileri/geri yapılabilir (istasyon değişir), başlıkta radyonun adı, altında çalan şarkı yazar.
     */
    private fun disaAcik() = object : ForwardingPlayer(oynatici) {
        private val ekKomutlar = Player.Commands.Builder()
            .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(ekKomutlar).build()
        override fun isCommandAvailable(k: Int) = ekKomutlar.contains(k) || super.isCommandAvailable(k)
        override fun seekToNext() = Radyo.sonraki(this@RadyoServisi, 1)
        override fun seekToNextMediaItem() = Radyo.sonraki(this@RadyoServisi, 1)
        override fun seekToPrevious() = Radyo.sonraki(this@RadyoServisi, -1)
        override fun seekToPreviousMediaItem() = Radyo.sonraki(this@RadyoServisi, -1)

        /** TV yeniden açıldıktan sonra kumandadan "oynat": son dinlenen radyo gelsin. */
        override fun play() {
            if (oynatici.mediaItemCount == 0) Radyo.sonIstasyon(this@RadyoServisi)?.let { Radyo.cal(this@RadyoServisi, it) }
            else super.play()
        }

        override fun getMediaMetadata(): MediaMetadata {
            val ist = istasyon ?: return super.getMediaMetadata()
            return MediaMetadata.Builder()
                .setTitle(ist.ad).setDisplayTitle(ist.ad).setStation(ist.ad)
                .setArtist(sarki.ifEmpty { "Canlı yayın" }).setSubtitle(sarki)
                // BerkOS'taki eski radyonun kadranı frekansı buradan okur ("" = internet radyosu).
                .setAlbumTitle(Radyolar.frekans(ist.id)?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "")
                .setIsPlayable(true).setIsBrowsable(false)
                .apply { if (ist.logo.isNotBlank()) setArtworkUri(Uri.parse(ist.logo)) }
                .build()
        }
    }

    /** Şu an çalan şarkı (ICY / ID3), oturum başlığı için. */
    private var sarki = ""

    override fun onGetSession(bilgi: MediaSession.ControllerInfo): MediaSession? = oturum

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!oynatici.playWhenReady || oynatici.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(ekranKapandi) }
        ana.removeCallbacksAndMessages(null)
        Radyo.servis = null
        oturum?.run { player.release(); release() }
        oturum = null
        Radyo.durumDegisti(null, caliyor = false, yukleniyor = false, hata = null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- denetim (Radyo nesnesinden)

    fun cal(ist: Istasyon) {
        istasyon = ist
        adresSira = 0
        deneme = 0
        onarildi = false
        duraklamaZamani = 0
        sarki = ""
        Radyo.sarkiDegisti("")
        yukle()
    }

    fun uyku(bitis: Long) {
        ana.removeCallbacks(uykuGorevi)
        oynatici.volume = 1f
        if (bitis > 0) ana.postAtTime(uykuGorevi, SystemClock.uptimeMillis() + (bitis - System.currentTimeMillis()))
    }

    /** Uyku süresi doldu: ses 8 saniyede kısılıp durur. */
    private fun uykuBitti() {
        val adim = 40
        for (i in 1..adim) ana.postDelayed({
            oynatici.volume = 1f - i / adim.toFloat()
            if (i == adim) { oynatici.pause(); oynatici.volume = 1f; Radyo.uykuBitti() }
        }, i * 200L)
    }

    private var duraklamaZamani = 0L

    fun duraklat() = oynatici.pause()

    fun devam() {
        if (istasyon == null) return
        if (oynatici.playbackState == Player.STATE_IDLE) yukle() else oynatici.play()
    }

    fun kapat() {
        ana.removeCallbacks(uykuGorevi)
        istasyon = null
        oynatici.stop()
        oynatici.clearMediaItems()
        Radyo.durumDegisti(null, caliyor = false, yukleniyor = false, hata = null)
    }

    private fun yukle() {
        val ist = istasyon ?: return
        oynatici.setMediaItem(oge(ist, adresSira.coerceIn(0, ist.adresler.size - 1)))
        oynatici.prepare()
        oynatici.play()
    }

    private fun oge(ist: Istasyon, sira: Int): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(ist.ad).setDisplayTitle(ist.ad).setStation(ist.ad)
            .setArtist("Radyo")
            .setIsPlayable(true).setIsBrowsable(false)
            .apply { if (ist.logo.isNotBlank()) setArtworkUri(Uri.parse(ist.logo)) }
            .build()
        return MediaItem.Builder()
            .setMediaId(ist.id)
            .setUri(ist.adresler[sira])
            .setMediaMetadata(meta)
            .apply { if (ist.hls.getOrElse(sira) { false } || ist.adresler[sira].contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
    }

    private val geriCagri = object : MediaSession.Callback {
        /** Dışarıdan (Google TV, sesli asistan) sadece radyonun kimliği gelir; adresini buradan buluruz. */
        override fun onAddMediaItems(
            s: MediaSession, c: MediaSession.ControllerInfo, ogeler: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(ogeler.mapNotNull { o ->
            Radyolar.bul(this@RadyoServisi, o.mediaId)?.let { oge(it, 0) }
        }.toMutableList())
    }

    private val dinleyici = object : Player.Listener {
        override fun onMediaItemTransition(o: MediaItem?, neden: Int) {
            // Dışarıdan başka radyo seçildi (Google TV): hangi radyoda olduğumuzu güncelle.
            val id = o?.mediaId ?: return
            if (id != istasyon?.id) { istasyon = Radyolar.bul(this@RadyoServisi, id); adresSira = 0; deneme = 0; onarildi = false }
        }

        /**
         * Kumandadan da durdurulup başlatılabiliyor: uzun duraklamadan sonra eski tampon çalmasın,
         * canlı yayının ucundan başlansın.
         */
        override fun onPlayWhenReadyChanged(oynasin: Boolean, neden: Int) {
            if (!oynasin) { duraklamaZamani = SystemClock.elapsedRealtime(); return }
            if (duraklamaZamani > 0 && SystemClock.elapsedRealtime() - duraklamaZamani > 20_000) {
                duraklamaZamani = 0
                ana.post { yukle() }
            }
        }

        override fun onEvents(p: Player, e: Player.Events) {
            val yukleniyor = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady
            if (p.isPlaying) deneme = 0
            Radyo.durumDegisti(istasyon, caliyor = p.isPlaying, yukleniyor = yukleniyor, hata = null,
                duraklatildi = !p.playWhenReady && istasyon != null && p.playbackState != Player.STATE_IDLE)
        }

        override fun onMetadata(m: Metadata) {
            var baslik: String? = null
            var sanatci: String? = null
            for (i in 0 until m.length()) when (val x = m.get(i)) {
                is IcyInfo -> baslik = x.title
                is TextInformationFrame -> when (x.id) {
                    "TIT2" -> baslik = x.values.firstOrNull()
                    "TPE1" -> sanatci = x.values.firstOrNull()
                }
            }
            val s = listOfNotNull(sanatci, baslik).joinToString(" – ").trim()
            if (s.isNotEmpty()) { sarki = s; Radyo.sarkiDegisti(s) }
        }

        override fun onPlayerError(h: PlaybackException) {
            val ist = istasyon ?: return
            deneme++
            val toplam = ist.adresler.size
            // HLS'te canlı pencerenin gerisinde kaldık: aynı adresi baştan aç.
            if (h.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                ana.postDelayed({ oynatici.seekToDefaultPosition(); oynatici.prepare(); oynatici.play() }, 300); return
            }
            if (deneme <= toplam * 2) {
                adresSira = (adresSira + 1) % toplam
                Radyo.durumDegisti(ist, caliyor = false, yukleniyor = true, hata = null)
                ana.postDelayed({ if (istasyon === ist) yukle() }, if (deneme <= toplam) 400L else 2_500L)
                return
            }
            if (!onarildi) {
                onarildi = true
                Radyo.durumDegisti(ist, caliyor = false, yukleniyor = true, hata = null)
                Ag.arkada {
                    val yeni = Radyolar.onar(this@RadyoServisi, ist)
                    Ag.anada {
                        if (istasyon !== ist) return@anada
                        if (yeni != null) { istasyon = yeni; adresSira = 0; deneme = 0; yukle() }
                        else Radyo.durumDegisti(ist, false, false, "Bu radyo şu an yayın yapmıyor")
                    }
                }
                return
            }
            Radyo.durumDegisti(ist, false, false, "Bu radyo şu an yayın yapmıyor")
        }
    }
}

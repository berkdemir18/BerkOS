package com.demir.salon

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Radyo uygulaması (ayrı APK, com.demir.radyo) çalarken ana ekran ve Sergi ne çaldığını buradan okur.
 * Radyo'nun medya oturumunu "izlemeye devam et" dinleyicisi zaten görüyor ([MedyaDinleyici]); buraya verir.
 * İstasyon değiştirmek oturumun ileri/geri komutu: Radyo onları sonraki/önceki radyo olarak yorumlar.
 */
object RadyoKoprusu {
    const val PAKET = "com.demir.radyo"

    private var denetleyici: MediaController? = null
    private val ana = Handler(Looper.getMainLooper())
    private val dinleyiciler = LinkedHashSet<() -> Unit>()
    /** Kumandayla istasyon değiştirildi, yeni radyonun adı henüz gelmedi. */
    var degisiyor = false; private set
    private val degisimBitti = Runnable { degisiyor = false; haber() }

    private val geriCagri = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) { ana.removeCallbacks(degisimBitti); degisiyor = false; haber() }
        override fun onPlaybackStateChanged(d: PlaybackState?) = haber()
        override fun onSessionDestroyed() { birak(); haber() }
    }

    fun dinle(f: () -> Unit) { dinleyiciler += f }
    fun birak(f: () -> Unit) { dinleyiciler -= f }
    private fun haber() { for (f in dinleyiciler.toList()) f() }

    /** MedyaDinleyici her oturum listesi değişiminde çağırır. */
    fun oturumlar(liste: List<MediaController>) {
        val yeni = liste.firstOrNull { it.packageName == PAKET }
        if (yeni?.sessionToken == denetleyici?.sessionToken) return
        birak()
        denetleyici = yeni?.also { it.registerCallback(geriCagri, ana) }
        ana.post { haber() }
    }

    private fun birak() {
        denetleyici?.unregisterCallback(geriCagri)
        denetleyici = null
    }

    private val durum get() = denetleyici?.playbackState?.state ?: PlaybackState.STATE_NONE
    private val meta get() = denetleyici?.metadata

    /** Ana ekranda kart görünsün mü: çalıyor, bağlanıyor ya da duraklatılmış. */
    val gorunsun get() = denetleyici != null && meta != null && durum in setOf(PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS)
    val caliyor get() = durum == PlaybackState.STATE_PLAYING
    val yukleniyor get() = durum == PlaybackState.STATE_BUFFERING || durum == PlaybackState.STATE_CONNECTING
    val ad: String get() = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
    /** Çalan şarkı; radyo şarkı adı yollamıyorsa boş. */
    val sarki: String get() = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().takeIf { it != "Canlı yayın" }.orEmpty()
    /** Ankara FM frekansı; internet radyosuysa null. */
    val frekans: Float? get() = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.toFloatOrNull()
    val logo: Bitmap? get() = meta?.let { m ->
        listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { runCatching { m.getBitmap(it) }.getOrNull() }
    }

    fun sonraki(yon: Int) {
        val t = denetleyici?.transportControls ?: return
        if (yon > 0) t.skipToNext() else t.skipToPrevious()
        degisiyor = true
        ana.removeCallbacks(degisimBitti)
        ana.postDelayed(degisimBitti, 4_000)
        haber()
    }

    fun calDurdur() {
        val t = denetleyici?.transportControls ?: return
        if (caliyor || yukleniyor) t.pause() else t.play()
    }

    fun kapat() { denetleyici?.transportControls?.stop() }

    fun ac(ctx: Context) {
        val pm = ctx.packageManager
        val niyet = pm.getLeanbackLaunchIntentForPackage(PAKET) ?: pm.getLaunchIntentForPackage(PAKET) ?: return
        runCatching { ctx.startActivity(niyet.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

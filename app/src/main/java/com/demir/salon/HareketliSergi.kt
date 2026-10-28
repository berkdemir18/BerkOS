package com.demir.salon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Hareketli sergi sahnesi: döngü video (şömine, yağmur, kar…). Mixkit'in ücretsiz lisanslı videoları. */
class Sahne(val id: String, val ad: String, val kat: String, val video: String, val resim: String, val ses: String, val sesSeviye: Float)

/**
 * Hareketli sahneler (assets/sahneler.json). Otomatik sergiye girmez; sadece Sergi menüsünün "Hareketli"
 * sekmesinden seçilince oynar. Video ilk seferde internetten akar, arkada indirilir; sonra diskten oynar.
 */
object Sahneler {
    @Volatile private var liste: List<Sahne>? = null
    private val inenler = HashSet<String>()

    fun hepsi(ctx: Context): List<Sahne> = liste ?: synchronized(this) {
        liste ?: runCatching {
            val a = JSONObject(ctx.assets.open("sahneler.json").bufferedReader().use { it.readText() }).getJSONArray("sahneler")
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Sahne(it.getString("id"), it.getString("ad"), it.getString("kat"), it.getString("video"), it.getString("resim"),
                    it.optString("ses"), it.optDouble("sesSeviye", .8).toFloat())
            }
        }.getOrDefault(emptyList()).also { liste = it }
    }

    fun dosya(ctx: Context, s: Sahne) = File(File(ctx.filesDir, "sahne").apply { mkdirs() }, "${s.id}.mp4")

    /** Ortam sesi (şömine çıtırtısı, yağmur…); aynı ses birkaç sahnede ortak, adı adresinden. */
    fun sesDosyasi(ctx: Context, s: Sahne) = File(File(ctx.filesDir, "sahne").apply { mkdirs() }, "ses_" + s.ses.substringAfterLast('/'))

    /** Diskte yoksa arkada indir (aynı anda bir kez). */
    fun indir(url: String, f: File) {
        if (url.isBlank() || f.exists()) return
        synchronized(inenler) { if (!inenler.add(f.path)) return }
        Ag.arkada {
            try { Ag.dosyaya(url, f) } finally { synchronized(inenler) { inenler.remove(f.path) } }
        }
    }
}

/**
 * Seçilen hareketli sahneyi tam ekran, sessiz ve döngüde oynatır; köşede saat, tarih ve çalan radyo.
 * Video ses istemez (radyo çalmaya devam etsin). Sağ/sol sıradaki sahneye geçer; başka tuş kapatır
 * (medya tuşları radyoya gider).
 */
class HareketliSergiActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var perde: View
    private lateinit var saat: TextView
    private lateinit var tarih: TextView
    private lateinit var radyo: TextView
    private lateinit var ad: TextView
    private val ana = Handler(Looper.getMainLooper())
    private var sahneler = listOf<Sahne>()
    private var sira = 0
    private val radyoDinleyici: () -> Unit = { radyoYaz(); sesAyarla() }
    /** Sahnenin ortam sesi: radyo çalmıyorken duyulur, radyo çalınca kısılır. */
    private var sesCalar: android.media.MediaPlayer? = null
    private var sesSeviye = .8f
    private var sesHazir = false
    private var sesSimdi = 0f
    private var sesHedef = -1f
    private val adSonu = Runnable { ad.animate().alpha(0f).setDuration(600).start() }
    private val saatGorevi = object : Runnable {
        override fun run() {
            saatYaz()
            ana.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Yazi.yukle(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val kok = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        video = VideoView(this).apply {
            // Ses odağı istemesin: yoksa Android radyoyu durdurur.
            if (android.os.Build.VERSION.SDK_INT >= 26) setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                start()
                perde.animate().alpha(0f).setDuration(900).start()
            }
            setOnErrorListener { _, _, _ -> ad.text = "Bu sahne açılamadı (internet?)"; ad.alpha = 1f; true }
        }
        kok.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        perde = View(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        kok.addView(perde, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val kutu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        saat = TextView(this).apply { stil(Yazi.serif, 64f); includeFontPadding = false }
        tarih = TextView(this).apply { stil(Yazi.sansKalin, 11f, Salon.MUREKKEP, 0.22f) }
        radyo = TextView(this).apply {
            stil(Yazi.sans, 12.5f, Salon.PIRINC)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(320)
            setPadding(0, dp(8), 0, 0)
        }
        for (t in listOf(saat, tarih, radyo)) { t.gravity = Gravity.END; kutu.addView(t) }
        kok.addView(kutu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { topMargin = dp(36); marginEnd = dp(48) })
        ad = TextView(this).apply { stil(Yazi.serifItalik, 24f) }
        kok.addView(ad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dp(40); marginStart = dp(48) })
        setContentView(kok)

        sahneler = Sahneler.hepsi(this)
        sira = sahneler.indexOfFirst { it.id == intent.getStringExtra("id") }.coerceAtLeast(0)
        oynat()
    }

    override fun onStart() {
        super.onStart()
        RadyoKoprusu.dinle(radyoDinleyici)
        radyoYaz()
        saatGorevi.run()
    }

    override fun onStop() {
        RadyoKoprusu.birak(radyoDinleyici)
        ana.removeCallbacksAndMessages(null)
        video.stopPlayback()
        sesBirak()
        super.onStop()
        finish()
    }

    private fun oynat() {
        val s = sahneler.getOrNull(sira) ?: run { finish(); return }
        perde.animate().cancel(); perde.alpha = 1f
        val f = Sahneler.dosya(this, s)
        video.setVideoURI(if (f.exists()) Uri.fromFile(f) else Uri.parse(s.video))
        if (!f.exists()) Sahneler.indir(s.video, f)
        sesBaslat(s)
        ad.text = s.ad
        ad.alpha = 1f
        ana.removeCallbacks(adSonu)
        ana.postDelayed(adSonu, 4_000)
    }

    private fun sesBaslat(s: Sahne) {
        sesBirak()
        if (s.ses.isBlank()) return
        sesSeviye = s.sesSeviye
        val f = Sahneler.sesDosyasi(this, s)
        val mp = android.media.MediaPlayer()
        sesCalar = mp
        runCatching {
            mp.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            if (f.exists()) mp.setDataSource(f.path) else { mp.setDataSource(s.ses); Sahneler.indir(s.ses, f) }
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { if (sesCalar === it) { sesHazir = true; it.start(); sesAyarla() } }
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepareAsync()
        }
    }

    /** Radyo çalıyorsa ortam sesi susar; çalmıyorsa yavaşça açılır (sadece hedef değişince, ~0,7 sn'de). */
    private fun sesAyarla() {
        val mp = sesCalar ?: return
        if (!sesHazir) return
        val hedef = if (RadyoKoprusu.gorunsun && (RadyoKoprusu.caliyor || RadyoKoprusu.yukleniyor || RadyoKoprusu.degisiyor)) 0f else sesSeviye
        if (hedef == sesHedef) return
        sesHedef = hedef
        val bas = sesSimdi
        val adim = 12
        for (i in 1..adim) ana.postDelayed({
            if (sesCalar === mp && sesHedef == hedef) runCatching {
                sesSimdi = bas + (hedef - bas) * i / adim
                mp.setVolume(sesSimdi, sesSimdi)
            }
        }, i * 60L)
    }

    private fun sesBirak() {
        sesHazir = false
        sesSimdi = 0f; sesHedef = -1f
        sesCalar?.let { runCatching { it.stop() }; it.release() }
        sesCalar = null
    }

    private fun saatYaz() {
        val simdi = LocalDateTime.now()
        saat.text = simdi.format(DateTimeFormatter.ofPattern("HH:mm"))
        tarih.text = simdi.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Salon.TR)).buyukTr()
    }

    private fun radyoYaz() {
        val acik = RadyoKoprusu.gorunsun && RadyoKoprusu.caliyor
        radyo.text = if (!acik) "" else listOf(RadyoKoprusu.ad, RadyoKoprusu.sarki).filter { it.isNotBlank() }.joinToString("  ·  ", prefix = "♪  ")
        radyo.visibility = if (acik) View.VISIBLE else View.GONE
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val medya = e.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE)
        if (medya) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val yon = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
            sira = Math.floorMod(sira + yon, sahneler.size)
            oynat()
            return true
        }
        if (e.action == KeyEvent.ACTION_UP && e.keyCode !in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) finish()
        return true
    }

    companion object {
        fun ac(ctx: Context, s: Sahne) {
            ctx.startActivity(Intent(ctx, HareketliSergiActivity::class.java).putExtra("id", s.id))
        }
    }
}

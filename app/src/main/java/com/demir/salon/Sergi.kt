package com.demir.salon

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.service.dreams.DreamService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.view.accessibility.AccessibilityEvent

/** TV'nin kendi ekran koruyucusu olarak seçildiğinde çalışan sergi. */
class SalonDream : DreamService() {
    private var sergi: SergiGorunumu? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        Yazi.yukle(this)
        sergi = SergiGorunumu(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        sergi?.baslat()
    }

    override fun onDreamingStopped() {
        sergi?.durdur()
        super.onDreamingStopped()
    }
}

/** Aynı sergi, ana ekranda bir süre dokunulmayınca ya da ayarlardan elle açılınca. Herhangi bir tuş kapatır. */
class SergiActivity : Activity() {
    private lateinit var sergi: SergiGorunumu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Yazi.yukle(this)
        sergi = SergiGorunumu(this)
        setContentView(sergi)
    }

    override fun onStart() {
        super.onStart()
        sergi.baslat()
    }

    override fun onStop() {
        sergi.durdur()
        super.onStop()
        finish()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}

/**
 * Ev tuşu yardımcısı (erişilebilirlik servisi). Google TV kendi ana ekranını açtığı an Salon'u öne alır;
 * böylece TV açılınca da, Ev tuşuna basınca da Salon gelir. Ekrandaki içeriği okumaz, sadece hangi
 * uygulamanın öne geldiğine bakar. Bunu iki iş için daha kullanır: ne izlediğini söylemeyen uygulamaları
 * (Netflix, Prime) "izlemeye devam et"e eklemek ve ezan vakti girince köşede kısa bir yazı göstermek.
 */
class EvTusuServisi : AccessibilityService() {
    private var son = 0L
    private var bekle = 0L
    private val yonlendirmeler = ArrayDeque<Long>()
    private val ana = Handler(Looper.getMainLooper())

    /** Şu an önde olan uygulama ve ne zamandan beri. */
    private var ondeki: String? = null
    private var ondeBaslangic = 0L

    /** Ekran kapalıyken (TV uykuda) gelen olaylara hiç bakılmaz. */
    private var ekranAcik = true

    private val ekranAlici = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            ekranAcik = i.action != Intent.ACTION_SCREEN_OFF
            if (!ekranAcik) { ondeBitti(null); ana.removeCallbacks(gecikmeliYonlendir) }
        }
    }

    private val ezanBak = object : Runnable {
        override fun run() {
            if (ekranAcik) {
                runCatching { ezanKontrol() }
                runCatching { Hatirlatici.zamaniGelen(this@EvTusuServisi)?.let { (b, a) -> koseYazisi(b, a) } }
            }
            ana.postDelayed(this, 20_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        calisan = this
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(ekranAlici, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(ekranAlici, f)
        ekranAcik = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        ana.removeCallbacks(ezanBak)
        ana.post(ezanBak)
    }

    override fun onDestroy() {
        calisan = null
        runCatching { unregisterReceiver(ekranAlici) }
        ana.removeCallbacksAndMessages(null)
        okuyucu.shutdownNow()
        ondeBitti(null)
        super.onDestroy()
    }

    /** Google TV ekranı açıldı ama satırı henüz çizilmediyse: en çok bu kadar bekleyip yine de geç. */
    private val gecikmeliYonlendir = Runnable { yonlendir() }
    private var yonlendirmeBekliyor = false

    /** Google TV ekranı içerik değiştirdikçe (100 ms'de bir) olay yağdırıyor; ağacı en fazla bu sıklıkla tara. */
    private var sonGtvOkuma = 0L

    private var sonEkranSorgusu = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!ekranAcik) {
            // TV uyanırken Google TV'nin ilk olayı ekran-açıldı yayınından önce gelebilir: arada bir sor.
            val t = SystemClock.elapsedRealtime()
            if (t - sonEkranSorgusu < 1_000) return
            sonEkranSorgusu = t
            ekranAcik = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
            if (!ekranAcik) return
        }
        val paket = e.packageName?.toString() ?: return
        val durum = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (durum && paket !in GECICI && !paket.contains("inputmethod")) ondeBitti(paket)
        if (Ek.pencereOlayi(this, paket)) return
        if (paket !in Salon.STOK_ANA_EKRANLAR) return
        // Google TV Streamer Ev'de kendi ana ekranını adıyla açıyor (HOME rolü BerkOS'ta olsa da; tuş da
        // erişilebilirliğe gelmiyor). Satırını okumayı beklerken 1,4 sn Google TV görünüyordu: penceresi
        // açılır açılmaz üstüne BerkOS'un duvarı, hemen BerkOS.
        if (durum && yonlendirmeli()) {
            // Google TV tek basışta birkaç pencere olayı yolluyor; BerkOS öne geçtikten sonra gelen geç olay
            // perdeyi bir daha açıp ekranı iki kez karartıyordu. BerkOS'u yeni açtıysak geç olayları say(ma).
            // (Etkin pencereye bakmak ilk olayı da kaçırıp perdeyi geciktiriyordu.)
            if (SystemClock.elapsedRealtime() - son < 1_500) return
            perdeAc()
            if (!yonlendir()) perdeKaldir()
            return
        }
        if (durum) gtvPenceresi = true
        val simdi = SystemClock.elapsedRealtime()
        if (okunuyor || (!durum && !yonlendirmeBekliyor && simdi - sonGtvOkuma < 1_500)) return
        gtvOku()
    }

    /** Ağaç taraması kendi iş parçacığında: her düğüm ayrı bir sistem çağrısı, ana iş parçacığında BerkOS'un açılışını donduruyordu. */
    private val okuyucu = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var okunuyor = false
    /** Okuma sürerken Google TV penceresi yeniden açıldıysa okuma bitince bir daha bakılır. */
    private var gtvPenceresi = false

    /** Google TV ekranı göründü: "İzlemeye devam edin" satırını oku (BerkOS aynı kartları gösterecek), sonra yönlendir. */
    private fun gtvOku() {
        val durum = gtvPenceresi
        gtvPenceresi = false
        sonGtvOkuma = SystemClock.elapsedRealtime()
        okunuyor = true
        val kok = rootInActiveWindow
        okuyucu.execute {
            val okundu = runCatching { GoogleTv.oku(this, kok) }.getOrDefault(false)
            ana.post {
                okunuyor = false
                gtvSonucu(durum, okundu)
                if (gtvPenceresi) gtvOku()
            }
        }
    }

    private fun gtvSonucu(durum: Boolean, okundu: Boolean) {
        if (!ekranAcik) return
        if (System.currentTimeMillis() < Salon.ayarlar(this).getLong("stok_izin_bitis", 0)) return
        // Ayarlardan varsayılan ana ekran Google TV seçildiyse karışma.
        if (Salon.ayarlar(this).getBoolean("varsayilan_gtv", false)) return
        // Satır henüz çizilmediyse en çok 900 ms bekle (içerik gelince okunup hemen geçilir).
        if (durum && !okundu) {
            ana.removeCallbacks(gecikmeliYonlendir)
            ana.postDelayed(gecikmeliYonlendir, 900)
            yonlendirmeBekliyor = true
            return
        }
        if (durum || (okundu && yonlendirmeBekliyor)) yonlendir()
    }

    private fun yonlendirmeli(): Boolean {
        val p = Salon.ayarlar(this)
        return ekranAcik && System.currentTimeMillis() >= p.getLong("stok_izin_bitis", 0) && !p.getBoolean("varsayilan_gtv", false)
    }

    private var perde: View? = null
    private val perdeSonu = Runnable { perdeKaldir() }

    /**
     * Perdede BerkOS'un sakin ana ekranının birebir görüntüsü (BerkOS kendisi çeker, yazılım bitmap'i: bu servisin
     * penceresi yazılımla çiziliyor, GPU bitmap'i BerkOS'u çökertiyordu). Bulanık duvar koyunca önce bulanık sonra
     * net geliyordu, titriyor gibiydi; aynı görüntü olunca perde kalkarken hiçbir şey değişmiyor.
     */
    @Volatile var perdeResmi: android.graphics.Bitmap? = null

    /** BerkOS öne gelene kadar Google TV'nin üstünde duran duvar (BerkOS çizilince kalkar; en çok 2 sn). */
    private fun perdeAc() {
        if (perde != null) return
        val v = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF0B0A09.toInt())
            perdeResmi?.let { setImageBitmap(it) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        if (runCatching { getSystemService(WindowManager::class.java).addView(v, lp) }.isFailure) return
        perde = v
        ana.postDelayed(perdeSonu, 2_000)
    }

    /** Altında aynı görüntü var: solmadan, bir anda kalkar (solarken iki görüntü üst üste titriyordu). */
    fun perdeKaldir() {
        ana.removeCallbacks(perdeSonu)
        val v = perde ?: return
        perde = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
    }

    /** BerkOS'u öne alır; sigorta ya da çift tetik yüzünden almadıysa false. */
    private fun yonlendir(): Boolean {
        ana.removeCallbacks(gecikmeliYonlendir)
        yonlendirmeBekliyor = false
        val simdi = SystemClock.elapsedRealtime()
        if (simdi < bekle) return false
        // Sigorta: BerkOS bir sebepten açılamayıp sürekli geri düşerse TV'yi kilitlemesin, kısa bir süre dursun.
        // Ev tuşuna arka arkaya basmak bunu tetiklemesin diye eşik yüksek, bekleme kısa.
        while (yonlendirmeler.isNotEmpty() && simdi - yonlendirmeler.first() > 10_000) yonlendirmeler.removeFirst()
        if (yonlendirmeler.size >= 8) {
            bekle = simdi + 20_000
            yonlendirmeler.clear()
            return false
        }
        yonlendirmeler.addLast(simdi)
        son = simdi
        anaEkraniAc(this)
        return true
    }

    /** Önceki uygulamanın önde kalma süresini bildir, yenisini başlat (null: ekran kapandı). */
    private fun ondeBitti(yeni: String?) {
        if (yeni == ondeki) return
        // Google TV modu: oradan bir uygulama açıldıysa izin biter, bir dahaki Ev tuşu yine BerkOS'u getirir.
        if (yeni != null && yeni != packageName && yeni !in Salon.STOK_ANA_EKRANLAR && yeni !in HARIC) {
            val p = Salon.ayarlar(this)
            if (p.getLong("stok_izin_bitis", 0) > System.currentTimeMillis()) p.edit().remove("stok_izin_bitis").apply()
        }
        val eski = ondeki
        val simdi = System.currentTimeMillis()
        if (eski != null && eski != packageName && eski !in Salon.STOK_ANA_EKRANLAR && eski !in HARIC) {
            val baslangic = ondeBaslangic
            val sure = (simdi - baslangic).coerceAtMost(4 * 60 * 60_000L)
            Ag.arkada { Izlenenler.onde(this, eski, baslangic, sure) }
        }
        ondeki = yeni
        ondeBaslangic = simdi
    }

    // ---------------------------------------------------------------- ezan vakti yazısı

    private var gosterilen: String? = null
    private var yazi: View? = null

    private fun ezanKontrol() {
        if (!Salon.ayarlar(this).getBoolean("ezan_uyari", false)) return
        val simdi = LocalDateTime.now()
        val v = EzanVakti.bugun(this)?.firstOrNull {
            it.ad != "Güneş" && !simdi.isBefore(it.zaman) && simdi.isBefore(it.zaman.plusMinutes(2))
        } ?: return
        val anahtar = "${v.ad}${v.zaman}"
        if (anahtar == gosterilen) return
        gosterilen = anahtar
        // BerkOS öndeyse zaten ana ekranda "vakti girdi" yazıyor.
        if (ondeki == packageName) return
        ezanYazisi(v)
    }

    /** Ayarlardan denemek için: sıradaki vakti şimdi göster. */
    fun ornekGoster() {
        val simdi = LocalDateTime.now()
        EzanVakti.bugun(this)?.firstOrNull { it.zaman.isAfter(simdi) && it.ad != "Güneş" }?.let { ezanYazisi(it) }
    }

    private fun ezanYazisi(v: Vakit) = koseYazisi(
        if (v.ad == "İmsak") "İmsak vakti" else "${v.ad} ezanı vakti",
        v.zaman.format(DateTimeFormatter.ofPattern("HH:mm")) + " · " + Salon.konum(this).ad.uppercase(Salon.TR),
    )

    /** Hangi uygulama açık olursa olsun sağ üst köşede [sure] duran küçük yazı (ezan, maç hatırlatma, Google TV modu). */
    fun koseYazisi(baslik: String, alt: String, sure: Long = 30_000) {
        val wm = getSystemService(WindowManager::class.java)
        yazi?.let { runCatching { wm.removeView(it) } }
        Yazi.yukle(this)
        val kutu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(16), dp(30), dp(16))
            background = GradientDrawable().apply {
                setColor(0xE6141210.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x40FFFFFF)
            }
        }
        kutu.addView(TextView(this).apply {
            text = baslik
            stil(Yazi.serif, 30f, Salon.PIRINC, golge = false)
        })
        kutu.addView(TextView(this).apply {
            text = alt
            stil(Yazi.sansKalin, 12f, Salon.SOLUK, 0.16f, golge = false)
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(48); y = dp(40)
        }
        if (runCatching { wm.addView(kutu, lp) }.isFailure) return
        yazi = kutu
        kutu.alpha = 0f
        kutu.translationY = -dp(16).toFloat()
        kutu.animate().alpha(1f).translationY(0f).setDuration(500).start()
        ana.postDelayed({
            kutu.animate().alpha(0f).setDuration(600).withEndAction {
                runCatching { wm.removeView(kutu) }
                if (yazi === kutu) yazi = null
            }.start()
        }, sure)
    }

    override fun onInterrupt() {}

    companion object {
        /** Çalışan yardımcı (ayarlardaki "ezan yazısını dene" için). */
        var calisan: EvTusuServisi? = null
            private set

        /** Bunlar bir şeyin üstüne kısa süre açılır (ses paneli, asistan); izlemeyi bölmez. */
        private val GECICI = setOf(
            "com.android.systemui", "android", "com.google.android.katniss",
            "com.google.android.tvrecommendations",
        )

        /** "İzlemeye devam et"e girmeyecekler: ayarlar, mağaza, ekran koruyucu. */
        private val HARIC = setOf(
            "com.android.tv.settings", "com.android.vending", "com.google.android.apps.tv.dreamx",
            "com.google.android.tv.remote.service",
        )

        fun anaEkraniAc(ctx: Context) {
            runCatching {
                // Animasyonsuz: perdenin altında BerkOS kayarak/solarak gelince titriyordu.
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle())
            }
        }
    }
}

/** TV açılışında Salon'u bir kez denemek için; asıl işi Ev tuşu yardımcısı görür. */
class AcilisAlici : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) EvTusuServisi.anaEkraniAc(ctx)
    }
}

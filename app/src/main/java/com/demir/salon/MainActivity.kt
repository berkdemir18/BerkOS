package com.demir.salon

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var kok: FrameLayout
    private lateinit var duvarA: ImageView
    private lateinit var duvarB: ImageView
    private lateinit var perde: View
    private lateinit var gecePerdesi: View
    private lateinit var sayfaPerdesi: View
    private lateinit var selam: TextView
    private lateinit var saat: TextView
    private lateinit var tarih: TextView
    private lateinit var tarihGun: TextView
    private lateinit var ezanSatiri: LinearLayout
    private lateinit var ezanKalan: TextView
    private lateinit var havaIkon: HavaIkonu
    private lateinit var sicaklik: TextView
    private lateinit var havaAciklama: TextView
    private lateinit var havaDetay: TextView
    private lateinit var tahminSatiri: LinearLayout
    private lateinit var plakaKutusu: View
    private lateinit var plakaAd: TextView
    private lateinit var plakaAlt: TextView
    private lateinit var odakAdi: TextView
    private lateinit var dockKaydirma: HorizontalScrollView
    private lateinit var dock: LinearLayout
    private lateinit var dockKutu: FrameLayout
    private lateinit var dugmeler: LinearLayout
    private lateinit var katman: FrameLayout
    private lateinit var vitrin: View
    private lateinit var solUst: ViewGroup
    private lateinit var sagUst: View
    /** Sağ üst köşe: arka plandaki uygulamaları kapatan düğme (yazısı odaktayken görünür). */
    private lateinit var temizleKutu: LinearLayout
    private lateinit var temizleDugme: ImageView
    /** Sağ üst köşe, temizlenin solu: Sergiyi (ekran koruyucuyu) hemen başlatır. */
    private lateinit var sergiDugme: ImageView
    private lateinit var sayfa: Sayfa
    private lateinit var bosluk: View
    private lateinit var raflar: LinearLayout
    private lateinit var ustCubuk: View
    private lateinit var altSolma: View
    private lateinit var cSaat: TextView
    private lateinit var cEzanSatiri: LinearLayout
    private lateinit var cEzanKalan: TextView
    private lateinit var cHavaIkon: HavaIkonu
    private lateinit var cSicaklik: TextView
    private lateinit var cHavaAciklama: TextView
    private lateinit var cHavaDetay: TextView
    private lateinit var baglantiYazisi: TextView

    /** Sakin hâl: tablo, saat, hava. Geniş hâl: aşağı inince sayfa kayar, satırlar açılır, üstte çubuk kalır. */
    private var genis = false
    private var uygulamalar = listOf<Uygulama>()
    private var vakitler = listOf<Vakit>()
    private var eserler = listOf<Eser>()
    private var rafImzasi = ""
    private var macImzasi = ""
    private var rafGetiriliyor = false
    private lateinit var dockCami: SiviCam
    private lateinit var damla: CamDamla
    private lateinit var isik: Ambilight
    /** Son çizilen satırlar (gizlenenler dahil): "Satırları düzenle" bunları listeler. */
    private var sonRaflar = listOf<Raf>()
    /** Satırlar yeniden çizilirken odak bir an boşa düşer; o arada sakin hâle dönülmesin. */
    private var cizimde = false
    private lateinit var aktifDuvar: ImageView
    private var sonDokunus = SystemClock.uptimeMillis()
    private val ana = Handler(Looper.getMainLooper())

    /** Açık katmanlar (tüm uygulamalar, menüler) ve açılmadan önce odakta olan görünüm. */
    private val katmanYigini = ArrayDeque<Pair<View, View?>>()

    /** Şeritte yeri değiştirilen kutu (taşıma modu): sağ/sol kaydırır, Tamam ya da Geri bırakır. */
    private var tasinan: Karo? = null

    private val saatAlici = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = dakikalik()
    }
    private val paketAlici = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { uygulamalariYukle(); radyoKur() }
    }

    /** İnternet gidip gelince köşedeki "bağlantı yok" yazısı. */
    private val agDinleyici = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { ana.post { baglanti(true) } }
        override fun onLost(n: Network) { ana.post { baglanti(cm()?.activeNetwork != null) } }
    }

    private val rafGorevi = Runnable { raflariKur() }
    private val camYeriGorevi = Runnable { Cam.yerleri() }

    private fun camYeriniTazele(gecikme: Long = 140) {
        ana.removeCallbacks(camYeriGorevi)
        ana.postDelayed(camYeriGorevi, gecikme)
    }
    private val camBoyasi = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    /** Ekran sakinken satırları birer birer önceden kur: aşağı inerken kurulum yüzünden kare kaçmasın. */
    private val onKurGorevi = object : Runnable {
        override fun run() {
            if (genis || SystemClock.uptimeMillis() - sonDokunus < 1_500) { ana.postDelayed(this, 1_000); return }
            val b = bloklar.values.take(ONDEN_KURULAN).firstOrNull { !it.kuruldu } ?: return
            kartlariKur(b)
            ana.postDelayed(this, 300)
        }
    }
    private val ipucuGorevi = Runnable { ipucuGoster() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ev tuşunda solarak gelmesin: perdenin altında yarı saydam BerkOS + Google TV titriyordu.
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        Yazi.yukle(this)
        setContentView(R.layout.activity_main)

        kok = findViewById(R.id.kok)
        duvarA = findViewById(R.id.duvarA)
        duvarB = findViewById(R.id.duvarB)
        perde = findViewById(R.id.perde)
        gecePerdesi = findViewById(R.id.gecePerdesi)
        sayfaPerdesi = findViewById(R.id.sayfaPerdesi)
        selam = findViewById(R.id.selam)
        saat = findViewById(R.id.saat)
        tarih = findViewById(R.id.tarih)
        tarihGun = findViewById(R.id.tarihGun)
        ezanSatiri = findViewById(R.id.ezanSatiri)
        ezanKalan = findViewById(R.id.ezanKalan)
        havaIkon = findViewById(R.id.havaIkon)
        sicaklik = findViewById(R.id.sicaklik)
        havaAciklama = findViewById(R.id.havaAciklama)
        havaDetay = findViewById(R.id.havaDetay)
        tahminSatiri = findViewById(R.id.tahminSatiri)
        plakaKutusu = findViewById(R.id.plakaKutusu)
        plakaAd = findViewById(R.id.plakaAd)
        plakaAlt = findViewById(R.id.plakaAlt)
        odakAdi = findViewById(R.id.odakAdi)
        dockKaydirma = findViewById(R.id.dockKaydirma)
        dock = findViewById(R.id.dock)
        dockKutu = findViewById(R.id.dockKutu)
        dugmeler = findViewById(R.id.dugmeler)
        katman = findViewById(R.id.katman)
        vitrin = findViewById(R.id.vitrin)
        solUst = findViewById(R.id.solUst)
        sagUst = findViewById(R.id.sagUst)
        sayfa = findViewById(R.id.sayfa)
        bosluk = findViewById(R.id.bosluk)
        raflar = findViewById(R.id.raflar)
        ustCubuk = findViewById(R.id.ustCubuk)
        altSolma = findViewById(R.id.altSolma)
        cSaat = findViewById(R.id.cSaat)
        cEzanSatiri = findViewById(R.id.cEzanSatiri)
        cEzanKalan = findViewById(R.id.cEzanKalan)
        cHavaIkon = findViewById(R.id.cHavaIkon)
        cSicaklik = findViewById(R.id.cSicaklik)
        cHavaAciklama = findViewById(R.id.cHavaAciklama)
        cHavaDetay = findViewById(R.id.cHavaDetay)
        aktifDuvar = duvarA
        temizleDugmesiKur()

        selam.stil(Yazi.serifItalik, 27f, Salon.MUREKKEP)
        saat.stil(Yazi.serif, 124f, Salon.MUREKKEP, -0.01f)
        tarih.stil(Yazi.serif, 44f, Salon.MUREKKEP)
        tarihGun.stil(Yazi.sansKalin, 12.5f, Salon.MUREKKEP, 0.24f)
        ezanKalan.stil(Yazi.serifItalik, 19f, Salon.MUREKKEP)
        sicaklik.stil(Yazi.serif, 40f, Salon.MUREKKEP)
        havaAciklama.stil(Yazi.sans, 15f, Salon.MUREKKEP)
        havaDetay.stil(Yazi.sans, 12f, Salon.SOLUK)
        plakaAd.stil(Yazi.serifItalik, 19f, Salon.MUREKKEP)
        plakaAlt.stil(Yazi.sansKalin, 10f, Salon.SOLUK, 0.18f)
        odakAdi.stil(Yazi.sansKalin, 11f, Salon.SOLUK, 0.16f)
        cSaat.stil(Yazi.serif, 50f, Salon.MUREKKEP, -0.01f)
        cEzanKalan.stil(Yazi.serifItalik, 14.5f, Salon.MUREKKEP)
        cSicaklik.stil(Yazi.serif, 42f, Salon.MUREKKEP)
        cHavaAciklama.stil(Yazi.sans, 13.5f, Salon.MUREKKEP)
        cHavaDetay.stil(Yazi.sans, 11.5f, Salon.SOLUK)
        // Gölgeli yazılar kendi GPU katmanında: gölgenin bulanıklığı her karede yeniden hesaplanmasın, sadece
        // yazı değişince (dakikada bir) çizilsin. Eskiden her karede GPU'da yüzlerce bulanıklık geçişi yapılıyordu.
        for (v in listOf<View>(saat, tarih, tarihGun, sicaklik, havaAciklama, havaDetay, havaIkon, odakAdi,
                cSaat, cSicaklik, cHavaAciklama, cHavaDetay, cHavaIkon, cEzanSatiri)) v.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Sıvı cam şerit. Arkası: duvardaki tablo + perdeler (gece perdesi dahil), Ken Burns'süz son hâli.
        Cam.arka = object : CamArkasi {
            override val genislik get() = kok.width
            override val yukseklik get() = kok.height
            override val karartma get() = gecePerdesi.alpha
            override fun ciz(c: Canvas) {
                val v = aktifDuvar
                val b = (v.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
                // Satırlar açıkken tablo kararır ama camın arkası sakin ekrandaki hâli: sadece gece karartması.
                camBoyasi.colorFilter = karartmaFiltresi(1f - gecePerdesi.alpha)
                c.save()
                c.translate(v.left.toFloat(), v.top.toFloat())
                c.concat(v.imageMatrix)
                c.drawBitmap(b, 0f, 0f, camBoyasi)
                c.restore()
            }
        }
        // Renk ayrışması sadece damlada (iOS'taki gibi); şeritte her kaydırmada 7 örnek pahalıya geliyordu.
        dockCami = SiviCam(dockKutu, dp(32).toFloat(), ayrisma = 0f)
        dockKutu.background = dockCami
        // Kayan uygulamalar camın dışına taşmasın: şerit kendi yuvarlak kenarından keser (cam köşeleri de buradan).
        dockKutu.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(32).toFloat())
        }
        dockKutu.clipToOutline = true
        val dockIsik = findViewById<FrameLayout>(R.id.dockIsik)
        isik = Ambilight(dockIsik)
        // Odak göstergesi: şeridin üstünde kutudan kutuya akan cam damla; içinde şeridin kendisi (cam + ışık + kutular) bükülür.
        damla = CamDamla(this) { c ->
            c.save()
            c.translate(-damla.left.toFloat(), -damla.top.toFloat())
            dockCami.arkaCiz(c)
            for (v in listOf<View>(dockIsik, dockKaydirma)) {
                // draw() görünümün kendi kaydırmasını uygulamıyor: şerit kaydıkça damlada kaymamış hâli görünüyordu.
                c.save(); c.translate((v.left - v.scrollX).toFloat(), (v.top - v.scrollY).toFloat()); v.draw(c); c.restore()
            }
            c.restore()
        }
        dockKutu.addView(damla, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(-dp(10), -dp(10), -dp(10), -dp(10))
        })
        dockKutu.elevation = dp(10).toFloat()
        dugmeler.visibility = View.GONE
        // Üst çubuk camsız: yazılar tablonun üstünde, arkada yukarıdan inen yumuşak bir koyuluk.
        ustCubuk.background = serit(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xD9000000.toInt(), 0x8C000000.toInt(), 0x00000000)), dp(118))
        altSolma.background = serit(GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0x99000000.toInt(), 0x00000000)), dp(56))
        // Sayfa ve gece karartması ayrı tam ekran katman değil, tablonun renk filtresi: her karede ekranın
        // tamamını bir kez daha karıştırmak bu GPU'da satırlara inerken kare kaçırtıyordu. İki görünüm sadece
        // karartmanın ne kadar olduğunu tutar (animasyonla), çizilmez.
        for (p in listOf(sayfaPerdesi, gecePerdesi)) p.visibility = View.GONE
        // Perde (tablonun üstündeki karartma) her karede çizilmez: duvar resmine bir kez işlenir (duvarGoster).
        perde.visibility = View.GONE
        // Sayfa kayınca cam yerini değiştirir: bulanık duvar aynı, sadece camın penceresi kayar.
        // Sayfa kayarken camlar her karede yeniden işlenmez (geçişte GPU'yu bu yoruyordu): kayma bitince bir kez.
        sayfa.setOnScrollChangeListener { _, _, _, _, _ -> camYeriniTazele(); satirGorunurlugu() }
        // Şerit kayarken ambilight kutunun arkasında kalsın (animasyonsuz).
        dockKaydirma.setOnScrollChangeListener { _, _, _, _, _ -> (currentFocus as? Karo)?.takeIf { dock.icinde(it) }?.let { isik.izle(it) } }
        sayfa.addOnLayoutChangeListener { _, _, ust, _, alt, _, eskiUst, _, eskiAlt -> if (alt - ust != eskiAlt - eskiUst) boslukAyarla() }
        dockKutu.addOnLayoutChangeListener { _, sol, ust, sag, alt, eSol, eUst, eSag, eAlt ->
            if (sol != eSol || ust != eUst || sag - sol != eSag - eSol || alt - ust != eAlt - eUst) camYeriniTazele(0)
        }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, yeni -> odakDegisti(yeni) }

        baglantiYazisi = TextView(this).apply {
            text = "İnternet bağlantısı yok  ·  son bilgiler gösteriliyor"
            stil(Yazi.sansKalin, 11.5f, Salon.MUREKKEP, 0.04f, golge = false)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            background = GradientDrawable().apply { setColor(0xCC1A1714.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x40FFFFFF) }
            visibility = View.GONE
        }
        kok.addView(baglantiYazisi, kok.indexOfChild(katman), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(18) })

        eserler = Koleksiyon.yukle(this)
        duvarGoster()
        uygulamalariYukle()

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        alicikaydet(paketAlici, f)
        vitrin.isFocusable = false
        dock.post { dock.getChildAt(0)?.requestFocus() }
    }

    override fun onStart() {
        super.onStart()
        Cam.basla()
        RadyoKoprusu.dinle(radyoDinleyici)
        radyoKur()
        radyoGuncelle()
        runCatching { cm()?.registerDefaultNetworkCallback(agDinleyici) }
        baglanti(cm()?.activeNetwork != null)
    }

    override fun onResume() {
        super.onResume()
        // duvar/ klasörüne adb ile yeni resim atılmış olabilir; liste sıradaki değişimde onları da görsün.
        Ag.arkada { val yeni = Koleksiyon.yukle(this); Ag.anada { eserler = yeni } }
        // Perdenin görüntüsü Ev'e dönüşteki hâl olsun (odak şeridin başında).
        kok.postDelayed({ if (hasWindowFocus()) anaEkranGoruntusu() }, 1_500)
        alicikaydet(saatAlici, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        sonDokunus = SystemClock.uptimeMillis()
        dakikalik()
        HavaDurumu.getir(this, ::havaGoster)
        Salon.konumBul(this) { HavaDurumu.getir(this, ::havaGoster) }
        // Namaz vakitleri şimdilik ekranda yok (kod duruyor); yerinde 4 büyüklerin maçları.
        Maclar.getir(this, ::maclariGoster)
        Koleksiyon.uzaklariIndir(this)
        // Dönüşte ekran hemen kullanılabilsin: satırları tazelemek biraz sonra, arka planda; değişen satır yeniden kurulur.
        rafKurBekle(if (raflar.childCount == 0) 0 else 1_500)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(saatAlici) }
        ana.removeCallbacks(ipucuGorevi)
    }

    override fun onStop() {
        super.onStop()
        // Başka uygulama, Sergi ya da ekran kapalı: ana ekran hiçbir şey çizmesin, kaydetmesin, indirmesin.
        Cam.dur()
        RadyoKoprusu.birak(radyoDinleyici)
        tasimaBitir()
        ana.removeCallbacks(rafGorevi)
        ana.removeCallbacks(onKurGorevi)
        runCatching { cm()?.unregisterNetworkCallback(agDinleyici) }
        yerlesmeyiBitir()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(paketAlici) }
        ana.removeCallbacksAndMessages(null)
    }

    /** Ev tuşuna basıldı: açık ne varsa kapat, sakin hâle dön. */
    /**
     * Ev tuşunda Google TV'nin üstüne atılan perde: onResume'da pencere henüz ekranda değil (kalkınca arada
     * Google TV görünüyordu). Odak gelince pencere önde ama sistemin geçişi ~350 ms daha sürüyor (altta Google TV
     * silik görünüyordu); o bitince kalksın.
     */
    override fun onWindowFocusChanged(odakta: Boolean) {
        super.onWindowFocusChanged(odakta)
        if (odakta) kok.postDelayed({ EvTusuServisi.calisan?.perdeKaldir() }, 400)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        tasimaBitir()
        while (katmanYigini.isNotEmpty()) katmanKapat(odakGeriVer = false)
        seritIlk = 0
        dockKaydirma.scrollTo(0, 0)
        seritGorunurlugu(0)
        dock.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ana ekrandan geri gidilecek bir yer yok: açık katmanı kapat; satırlardaysan dock'a, dock'taysan sakin hâle dön.
        when {
            katmanYigini.isNotEmpty() -> {
                duvarOnizleme?.let { ana.removeCallbacks(it) }
                duvarSecimGeri?.let { duvarSecimGeri = null; duvarSiraGoster(it) }
                katmanKapat()
            }
            currentFocus?.let { raflar.icinde(it) } == true -> dock.getChildAt(0)?.requestFocus()
            currentFocus?.let { dockKutu.icinde(it) } == true -> dock.getChildAt(0)?.requestFocus()
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        sonDokunus = SystemClock.uptimeMillis()
        tasinan?.let { if (tasimaTusu(it, e)) return true }
        val odak = currentFocus
        if (e.action == KeyEvent.ACTION_DOWN && katmanYigini.isEmpty()) {
            // Sağ üstteki temizle düğmesi: sakin ekrandan ya da üst sıradaki maç kartından yukarı; aşağı geri döner.
            if (odak === temizleDugme || odak === sergiDugme) {
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> (ustMacKarti() ?: dock.getChildAt(0))?.requestFocus()
                    KeyEvent.KEYCODE_DPAD_LEFT -> sergiDugme.requestFocus()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> temizleDugme.requestFocus()
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return super.dispatchKeyEvent(e)
                }
                return true
            }
            if (!genis && e.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                (odak === vitrin || (odak?.getTag(R.id.satir) == "mac" && (odak.parent as ViewGroup).indexOfChild(odak) == 0))) {
                temizleDugme.requestFocus()
                return true
            }
            // Sakin hâlde aşağı ya da Tamam: dock'un başına in.
            if (odak === vitrin && e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                dock.getChildAt(0)?.requestFocus()
                return true
            }
            if (odak != null && odak === buyukRadyo &&
                e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                radyoAyarla(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
            // Saatin altındaki (çalan) radyo: aşağı şerit, yukarı temizle düğmesi.
            if (odak != null && odak === buyukRadyo) {
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { dock.getChildAt(seritIlk)?.requestFocus(); return true }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) { temizleDugme.requestFocus(); return true }
            }
            if (odak != null && dockKutu.icinde(odak)) {
                // Şeritten yukarı: radyo çalıyorsa ve sol yarıdaysan saatin altındaki radyo; yoksa sağ sütunun en altı.
                // Aşağı: satırların ilk kartı.
                val buyuk = buyukRadyo
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP && buyuk != null && buyuk.visibility == View.VISIBLE) {
                    val konum = IntArray(2).also { odak.getLocationOnScreen(it) }
                    if (konum[0] + odak.width / 2 < resources.displayMetrics.widthPixels / 2) { buyuk.requestFocus(); return true }
                }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    (ezanSatiri.getChildAt(0) as? ViewGroup)?.takeIf { it.childCount > 0 }?.let { k ->
                        k.getChildAt(k.childCount - 1)?.requestFocus()
                    } ?: temizleDugme.requestFocus()
                    return true
                }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { ilkKart()?.requestFocus(); return true }
            }
            if (odak != null && raflar.icinde(odak) && e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP) &&
                dikeyGit(odak, e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) return true
            if (odak != null && odak.getTag(R.id.satir) == "mac" && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Maç kartlarından aşağı: altta kart varsa ona, en alttaysan şeridin başına.
                val kartlar = odak.parent as ViewGroup
                val sira = kartlar.indexOfChild(odak)
                if (sira < kartlar.childCount - 1) kartlar.getChildAt(sira + 1)?.requestFocus()
                else dock.getChildAt(seritIlk)?.requestFocus()
                return true
            }
            // Satırın sonunda sağa/sola basınca başka satıra atlamasın, olduğu yerde kalsın.
            if (odak != null && e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val satir = if (dockKutu.icinde(odak)) dock else satirKutusu(odak)
                if (satir != null) {
                    val yon = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                    if (FocusFinder.getInstance().findNextFocus(satir, odak, yon) == null) return true
                }
            }
        }
        if (e.keyCode == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) {
            odak?.performLongClick()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun alicikaydet(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, f)
    }

    private fun cm() = getSystemService(ConnectivityManager::class.java)

    private fun ViewGroup.icinde(v: View): Boolean {
        var p = v.parent
        while (p != null) { if (p === this) return true; p = p.parent }
        return false
    }

    /** Odaktaki kartın bulunduğu yatay satır (kartların dizildiği LinearLayout). */
    private fun satirKutusu(v: View): ViewGroup? {
        var p = v.parent
        while (p is View) {
            if (p.getTag(R.id.satir) == true) return p as ViewGroup
            p = p.parent
        }
        return null
    }

    // ---------------------------------------------------------------- saat, selam, gece

    private fun dakikalik() {
        val simdi = LocalDateTime.now()
        saat.text = simdi.format(DateTimeFormatter.ofPattern("HH:mm"))
        cSaat.text = saat.text
        tarih.text = simdi.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Salon.TR))
        tarihGun.text = simdi.format(DateTimeFormatter.ofPattern("EEEE", Salon.TR)).buyukTr()
        selam.text = selamla(simdi)
        val s = simdi.hour
        val gece = if (s >= 23 || s < 6) .32f else if (s >= 21) .16f else 0f
        // Gece perdesi camın arkasında da var: değişirse cam bir kez yeniden kaydedilir.
        if (gece != gecePerdesi.alpha) gecePerdesi.animate().alpha(gece).setDuration(2000)
            .setUpdateListener { duvarFiltresi() }.withEndAction { Cam.yenile() }.start()
        if (simdi.minute % 5 == 0) Maclar.getir(this, ::maclariGoster)
        // Perdedeki saat güncel kalsın.
        if (hasWindowFocus()) kok.postDelayed({ if (hasWindowFocus()) anaEkranGoruntusu() }, 400)
        if (System.currentTimeMillis() - Salon.ayarlar(this).getLong("duvar_zaman", 0) > DUVAR_SURESI_MS) duvarIleri()
        if (hasWindowFocus() && katmanYigini.isEmpty() && SystemClock.uptimeMillis() - sonDokunus > BOSTA_SERGI_MS) {
            sonDokunus = SystemClock.uptimeMillis()
            startActivity(Intent(this, SergiActivity::class.java))
        }
    }

    private fun selamla(t: LocalDateTime): String {
        if (t.dayOfWeek == DayOfWeek.FRIDAY && t.hour in 5..21) return "Hayırlı cumalar"
        return when (t.hour) {
            in 5..10 -> "Hayırlı sabahlar"
            in 11..16 -> "İyi günler"
            in 17..21 -> "Hayırlı akşamlar"
            else -> "Hayırlı geceler"
        }
    }

    private fun baglanti(var_: Boolean) {
        val onceYoktu = baglantiYazisi.visibility == View.VISIBLE
        baglantiYazisi.visibility = if (var_) View.GONE else View.VISIBLE
        if (var_ && onceYoktu) { rafImzasi = ""; rafKurBekle(500) }
    }

    // ---------------------------------------------------------------- hava

    private fun havaGoster(h: Hava) {
        sicaklik.text = "${h.sicaklik}°"
        cSicaklik.text = sicaklik.text
        for (ikon in listOf(havaIkon, cHavaIkon)) {
            ikon.tur = HavaDurumu.tur(h.kod)
            ikon.gece = !h.gunduz
        }
        havaAciklama.text = "${HavaDurumu.aciklama(h.kod)} · ${Salon.konum(this).ad}"
        cHavaAciklama.text = HavaDurumu.aciklama(h.kod)
        val bugun = LocalDate.now()
        val gunler = h.gunler.filter { !it.tarih.isBefore(bugun) }
        val detay = mutableListOf<String>()
        gunler.firstOrNull()?.let { detay += "En yüksek ${it.enYuksek}°"; detay += "En düşük ${it.enDusuk}°" }
        if (kotlin.math.abs(h.hissedilen - h.sicaklik) >= 2) detay += "Hissedilen ${h.hissedilen}°"
        havaDetay.text = detay.take(2).joinToString("  ·  ")
        havaDetay.visibility = if (havaDetay.text.isEmpty()) View.GONE else View.VISIBLE
        // Üst çubukta ayrıntı yazılmıyor; boş satır "Kapalı"yı yukarı itmesin.
        cHavaDetay.visibility = if (cHavaDetay.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        tahminSatiri.visibility = View.GONE
    }

    // ---------------------------------------------------------------- 4 büyüklerin maçları

    private fun maclariGoster(liste: List<Mac>) {
        if (isDestroyed) return
        // Trabzonspor yok; sıra hep Beşiktaş, Galatasaray, Fenerbahçe (önbellekte eskisi kalmış olabilir).
        val gosterilen = liste.filter { it.takim != "Trabzonspor" }
            .sortedBy { m -> MAC_SIRASI.indexOfFirst { it == m.ev || it == m.deplasman }.let { if (it < 0) 9 else it } }
            .distinctBy { it.id.ifBlank { it.ev + it.deplasman } }.take(3)
        // Aynı maçlar aynı durumdaysa kartlar yeniden kurulmasın (dönüşte boşuna iş, odak kaybı).
        val imza = gosterilen.joinToString("|") { "${it.id}${it.ev}${it.zaman}${it.canli}${it.bitti}${it.skor}" } + LocalDate.now()
        if (imza == macImzasi) return
        macImzasi = imza
        val odaktaki = (currentFocus?.takeIf { it.getTag(R.id.satir) == "mac" }?.parent as? ViewGroup)?.indexOfChild(currentFocus) ?: -1
        // Sakin ekran: hava durumunun altında tek sütun cam kartlar, hepsi aynı genişlikte.
        ezanSatiri.removeAllViews()
        val izgara = GridLayout(this).apply { columnCount = 1; useDefaultMargins = false; clipChildren = false; clipToPadding = false }
        for ((i, m) in gosterilen.withIndex()) {
            izgara.addView(macKarti(m), GridLayout.LayoutParams(GridLayout.spec(i), GridLayout.spec(0, GridLayout.FILL)).apply {
                setMargins(0, if (i > 0) dp(10) else 0, 0, 0)
            })
        }
        ezanSatiri.addView(izgara)
        if (odaktaki >= 0) izgara.getChildAt(odaktaki.coerceAtMost(izgara.childCount - 1))?.requestFocus()
        // Üst çubuk: iki arma + saat, sade.
        cEzanSatiri.removeAllViews()
        for (m in gosterilen) {
            val kutu = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            kutu.addView(arma(m.evLogo, 22)); kutu.addView(arma(m.depLogo, 22).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4) })
            kutu.addView(TextView(this).apply {
                text = if (m.canli) "CANLI ${m.skor}" else if (m.bitti) "MS ${m.skor}" else macZamani(m, kisa = true)
                stil(Yazi.sansKalin, 11f, if (m.canli) Salon.PIRINC else Salon.MUREKKEP, 0.04f)
                setPadding(dp(8), 0, 0, 0)
            })
            cEzanSatiri.addView(kutu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (cEzanSatiri.childCount > 0) marginStart = dp(16)
            })
        }
    }

    // ---------------------------------------------------------------- eski radyo

    /** Radyo çalarken saatin ve tarihin altında; kapalıyken ana ekranda yok (Radyo şeritten açılır). */
    private var buyukRadyo: EskiRadyo? = null
    private val radyoDinleyici: () -> Unit = { radyoGuncelle() }
    /** Radyo sustu: biraz bekleyip kaybolsun (istasyon değişirken bir an durunca gidip gelmesin). */
    private val radyoGizleGorevi = Runnable { radyoGoster(false) }

    /**
     * Eski usul ahşap radyo; hoparlörünün üstünde çalan radyonun logosu, adı, frekansı.
     * Sadece radyo çalarken görünür. Üstündeyken sağ/sol radyo değiştirir, Tamam durdurur/çalar, basılı
     * tutmak menü. Radyo ayrı bir uygulama (com.demir.radyo); burası onun medya oturumunu okur ([RadyoKoprusu]).
     */
    private fun radyoKur() {
        val yuklu = packageManager.getLaunchIntentForPackage(RadyoKoprusu.PAKET) != null ||
            packageManager.getLeanbackLaunchIntentForPackage(RadyoKoprusu.PAKET) != null
        if (!yuklu) {
            buyukRadyo?.let { r -> (r.parent as? ViewGroup)?.removeView(r) }
            buyukRadyo = null
            return
        }
        if (buyukRadyo == null) buyukRadyo = EskiRadyo(this).apply {
            yazilar = EskiRadyo.Yazilar(Yazi.serif, Yazi.serifItalik, Yazi.sans, Yazi.sansKalin)
            elevation = dp(12).toFloat()
            visibility = View.GONE
            setOnClickListener { if (RadyoKoprusu.gorunsun) RadyoKoprusu.calDurdur() else RadyoKoprusu.ac(this@MainActivity) }
            setOnLongClickListener {
                uzunBasildi()
                secenekler("Radyo", listOf(
                    "Tüm radyolar" to { RadyoKoprusu.ac(this@MainActivity) },
                    "Radyoyu kapat" to { RadyoKoprusu.kapat() },
                ))
                true
            }
            setOnFocusChangeListener { v, f ->
                v.pivotX = 0f; v.pivotY = v.height / 2f
                v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).translationZ(if (f) dp(10).toFloat() else 0f)
                    .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
            }
            solUst.addView(this, solUst.indexOfChild(saat) + 1,
                LinearLayout.LayoutParams(dp(BUYUK_RADYO_G), dp(BUYUK_RADYO_G * RADYO_ORAN)).apply { topMargin = dp(14) })
        }
        radyoGuncelle()
    }

    private fun radyoGuncelle() {
        val r = buyukRadyo ?: return
        if (RadyoKoprusu.gorunsun) {
            val durum = when {
                RadyoKoprusu.degisiyor -> "Ayarlanıyor…"
                RadyoKoprusu.yukleniyor -> "Bağlanıyor…"
                RadyoKoprusu.caliyor -> ""
                else -> "Duraklatıldı"
            }
            r.goster(RadyoKoprusu.ad, RadyoKoprusu.frekans, RadyoKoprusu.sarki, durum,
                acik = RadyoKoprusu.caliyor || RadyoKoprusu.yukleniyor || RadyoKoprusu.degisiyor, logo = RadyoKoprusu.logo)
        }
        val calarken = RadyoKoprusu.gorunsun && (RadyoKoprusu.caliyor || RadyoKoprusu.yukleniyor || RadyoKoprusu.degisiyor)
        ana.removeCallbacks(radyoGizleGorevi)
        if (calarken) radyoGoster(true)
        else if (r.visibility == View.VISIBLE) ana.postDelayed(radyoGizleGorevi, 2_500)
    }

    private fun radyoGoster(gorunsun: Boolean) {
        val r = buyukRadyo ?: return
        if ((r.visibility == View.VISIBLE) == gorunsun) return
        if (!gorunsun && r.isFocused) dock.getChildAt(seritIlk)?.requestFocus()
        r.visibility = if (gorunsun) View.VISIBLE else View.GONE
    }

    private fun radyoAyarla(yon: Int) {
        if (RadyoKoprusu.gorunsun) RadyoKoprusu.sonraki(yon) else radyoyuBaslat(yon)
    }

    /** Radyo kapalıyken: Radyo uygulamasının görünmez açıcısı son dinlenen radyoyu çalar (BerkOS önde kalır). */
    private fun radyoyuBaslat(yon: Int) {
        runCatching {
            startActivity(Intent("com.demir.radyo.CAL").setPackage(RadyoKoprusu.PAKET).putExtra("yon", yon)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { bildir("Radyo açılamadı") }
    }

    private fun macKarti(m: Mac): View {
        val kart = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(16), dp(9))
            minimumWidth = dp(250)
            isFocusable = true
            isClickable = true
            setTag(R.id.satir, "mac")
        }
        kart.background = SiviCam(kart, dp(16).toFloat(), mercekDp = 10f, enAzKoyu = .3f)
        // Odak: her yerde aynı dil — kalın açık çerçeve + belirgin büyüme + yazı parlar.
        val cerceve = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setStroke(dp(2.5f), Salon.MUREKKEP) }
        val yazilar = mutableListOf<TextView>()
        kart.setOnFocusChangeListener { _, f ->
            kart.foreground = if (f) cerceve else null
            for (t in yazilar) t.animate().alpha(if (f) 1f else .8f).setDuration(160).start()
            // Kart her yandan en çok 8dp büyür: geniş (iki sütunluk) kart da ekranın kenarına taşmasın.
            val olcek = if (f) (1f + dp(16) / kart.width.coerceAtLeast(1).toFloat()).coerceIn(1.03f, 1.08f) else 1f
            kart.animate().scaleX(olcek).scaleY(olcek).translationZ(if (f) dp(8).toFloat() else 0f)
                .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
        }
        kart.setOnClickListener { macDetayi(m) }
        val armalar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        armalar.addView(arma(m.evLogo, 18)); armalar.addView(arma(m.depLogo, 18).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(3) })
        kart.addView(armalar)
        val yazi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        yazi.addView(TextView(this).apply {
            text = "${m.ev} – ${m.deplasman}"
            stil(Yazi.sansKalin, 12.5f, Salon.MUREKKEP, golge = false)
            maxLines = 1
            alpha = .8f
            yazilar += this
        })
        yazi.addView(TextView(this).apply {
            text = if (m.canli) "CANLI · ${m.skor} · ${m.lig}" else if (m.bitti) "Maç sonucu · ${m.skor} · ${m.lig}" else "${macZamani(m, kisa = false)} · ${m.lig}"
            stil(Yazi.sans, 10.5f, if (m.canli) Salon.PIRINC else Salon.SOLUK, golge = false)
            maxLines = 1
            alpha = .8f
            yazilar += this
        })
        kart.addView(yazi)
        return kart
    }

    private var acikDetay: MacDetay? = null

    private fun macDetayi(m: Mac) {
        val d = MacDetay(this, m)
        acikDetay = d
        katmanAc(d.kok, null)
        d.kok.post { d.ilkOdak?.requestFocus() }
    }

    private fun arma(url: String, boyut: Int) = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(boyut), dp(boyut))
        scaleType = ImageView.ScaleType.FIT_CENTER
        Resim.yukle(this, url.ifBlank { null }, dp(boyut * 2))
    }

    /** "Bugün 19:00", "Yarın 21:30", "Cmt 19:00", ileriyse "8 Kas 21:00". */
    private fun macZamani(m: Mac, kisa: Boolean): String {
        val gun = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), m.zaman.toLocalDate())
        val saat = m.zaman.format(DateTimeFormatter.ofPattern("HH:mm"))
        val ad = when {
            gun == 0L -> "Bugün"
            gun == 1L -> "Yarın"
            gun < 7 -> m.zaman.dayOfWeek.getDisplayName(if (kisa) TextStyle.SHORT else TextStyle.FULL, Salon.TR)
            else -> m.zaman.format(DateTimeFormatter.ofPattern("d MMM", Salon.TR))
        }
        return "$ad $saat"
    }

    // ---------------------------------------------------------------- ezan vakitleri (şimdilik ekranda değil)

    private fun ezanGoster() {
        val simdi = LocalDateTime.now()
        val bugunku = vakitler.take(6)
        val siradaki = vakitler.firstOrNull { it.zaman.isAfter(simdi) }
        ezanDoldur(ezanSatiri, bugunku, siradaki, adSp = 10f, saatSp = 23f, ara = 24)
        ezanDoldur(cEzanSatiri, bugunku, siradaki, adSp = 9.5f, saatSp = 20f, ara = 18)
        val girilen = bugunku.lastOrNull {
            !it.zaman.isAfter(simdi) && it.ad != "Güneş" && Duration.between(it.zaman, simdi).toMinutes() < 15
        }
        ezanKalan.text = when {
            vakitler.isEmpty() -> ""
            girilen != null -> "${girilen.ad} vakti girdi"
            siradaki != null -> "${hedef(siradaki.ad)} ${sure(Duration.between(simdi, siradaki.zaman))} var"
            else -> ""
        }
        cEzanKalan.text = ezanKalan.text
        cEzanKalan.visibility = if (ezanKalan.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun ezanDoldur(satir: LinearLayout, bugunku: List<Vakit>, siradaki: Vakit?, adSp: Float, saatSp: Float, ara: Int) {
        satir.removeAllViews()
        for (v in bugunku) {
            val vurgu = v === siradaki
            val sutun = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sutun.addView(TextView(this).apply {
                text = v.ad.buyukTr()
                stil(Yazi.sansKalin, adSp, if (vurgu) Salon.PIRINC else Salon.SOLUK, 0.18f)
            })
            sutun.addView(TextView(this).apply {
                text = v.zaman.format(DateTimeFormatter.ofPattern("HH:mm"))
                stil(Yazi.serif, saatSp, if (vurgu) Salon.PIRINC else Salon.MUREKKEP)
                includeFontPadding = false
            })
            satir.addView(sutun, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (satir.childCount > 0) marginStart = dp(ara)
            })
        }
    }

    private fun hedef(ad: String) = when (ad) {
        "İmsak" -> "İmsak vaktine"
        "Güneş" -> "Güneşin doğmasına"
        else -> "$ad ezanına"
    }

    private fun sure(d: Duration): String {
        val dakika = (d.seconds + 59) / 60
        val s = dakika / 60
        val m = dakika % 60
        return when {
            s == 0L -> "$m dakika"
            m == 0L -> "$s saat"
            else -> "$s saat $m dakika"
        }
    }

    // ---------------------------------------------------------------- duvar resmi

    private fun duvarAdaylari() = eserler.filter { (it.tur == "tablo" || it.tur == "uzay") && it.oran >= 1.25f }

    private fun duvarIleri() {
        val p = Salon.ayarlar(this)
        p.edit().putInt("duvar_sira", p.getInt("duvar_sira", 0) + 1).apply()
        duvarGoster()
    }

    private fun duvarGoster() {
        val liste = duvarAdaylari()
        if (liste.isEmpty()) return
        val p = Salon.ayarlar(this)
        val e = liste[Math.floorMod(p.getInt("duvar_sira", 0), liste.size)]
        p.edit().putLong("duvar_zaman", System.currentTimeMillis()).apply()
        val genislik = resources.displayMetrics.widthPixels.coerceIn(1280, 1920)
        val ekranG = kok.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ekranY = kok.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val perdeKopyasi = getDrawable(R.drawable.perde)?.mutate()
        Ag.arkada {
            val ham = Koleksiyon.bitmap(this, e, genislik) ?: return@arkada
            val b = perdeli(ham, perdeKopyasi, ekranG, ekranY)
            Ag.anada {
                if (isDestroyed) return@anada
                val yeni = if (aktifDuvar === duvarA) duvarB else duvarA
                val eski = aktifDuvar
                yeni.setImageBitmap(b)
                // Geçiş bitince alttaki eski tablo bırakılır: her karede iki tam ekran resim çizilmesin, bellekte durmasın.
                val bitince = Runnable { if (aktifDuvar !== eski) eski.setImageDrawable(null) }
                if (yeni === duvarB) {
                    duvarB.animate().alpha(1f).setDuration(1600).withEndAction(bitince).start()
                } else {
                    duvarA.alpha = 1f
                    duvarB.animate().alpha(0f).setDuration(1600).withEndAction(bitince).start()
                }
                aktifDuvar = yeni
                yerles(yeni)
                // Cam yeni tablonun durgun (son) hâlini gösterir; perdenin kopyası açık/koyu hesabı için.
                kok.post { Cam.duvar(b, null, kok.width, kok.height) }
                // Tablonun künyesi ekranda yazmıyor.
                plakaKutusu.visibility = View.GONE
            }
        }
    }

    /** Seçim listesi açıkken önizlemeden önceki sıra; Geri basılırsa duvar ona döner. */
    private var duvarSecimGeri: Int? = null
    private var duvarOnizleme: Runnable? = null

    /** Listede gezerken arkadaki duvar o resme geçer (önizleme); OK seçer, Geri eskisine döndürür. */
    private fun duvarSec() {
        val liste = duvarAdaylari()
        if (liste.isEmpty()) return
        val p = Salon.ayarlar(this)
        val simdiki = Math.floorMod(p.getInt("duvar_sira", 0), liste.size)
        duvarSecimGeri = simdiki
        val maddeler = liste.mapIndexed { i, e ->
            ((if (i == simdiki) "✓  " else "     ") + e.ad) to { duvarSecimGeri = null; duvarSiraGoster(i) }
        }
        secenekler("Duvar resmi seç", maddeler, odakSira = simdiki, odakta = { i ->
            // Hızlı kaydırırken her resim tek tek açılmasın: kumanda durunca önizle.
            duvarOnizleme?.let { ana.removeCallbacks(it) }
            duvarOnizleme = Runnable { if (duvarSecimGeri != null) duvarSiraGoster(i) }.also { ana.postDelayed(it, 450) }
        })
    }

    private fun duvarSiraGoster(i: Int) {
        if (Salon.ayarlar(this).getInt("duvar_sira", 0) == i) return
        Salon.ayarlar(this).edit().putInt("duvar_sira", i).apply()
        duvarGoster()
    }

    /** Tablonun karartması: satırlar açıkken %40, gece ayrıca. */
    private fun duvarFiltresi() {
        val f = karartmaFiltresi((1f - sayfaPerdesi.alpha * SAYFA_KARARTMA) * (1f - gecePerdesi.alpha))
        duvarA.colorFilter = f
        duvarB.colorFilter = f
    }

    private fun karartmaFiltresi(k: Float): android.graphics.ColorFilter? {
        if (k >= .999f) return null
        val g = (k.coerceIn(0f, 1f) * 255).toInt()
        return android.graphics.LightingColorFilter(Color.rgb(g, g, g), 0)
    }

    /**
     * Tablonun üstündeki karartmayı (perde: hafif genel karartma + üstte, altta gölge) resmin kendisine işler.
     * Perde ayrı bir tam ekran katman olarak her karede çizildiğinde GPU'ya kare başına ~30 ms yük bindiriyordu.
     * Perde ekrana göre tanımlı; resim ekrana "ortala-kırp" ile oturduğu için ters dönüşümle resmin içine çizilir.
     */
    private fun perdeli(ham: android.graphics.Bitmap, perde: android.graphics.drawable.Drawable?, g: Int, y: Int): android.graphics.Bitmap {
        if (perde == null) return ham
        val b = if (ham.isMutable) ham else ham.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { ham.recycle() }
        val olcek = maxOf(g / b.width.toFloat(), y / b.height.toFloat())
        val m = android.graphics.Matrix().apply {
            setScale(olcek, olcek)
            postTranslate((g - b.width * olcek) / 2f, (y - b.height * olcek) / 2f)
        }
        val ters = android.graphics.Matrix()
        m.invert(ters)
        val c = Canvas(b)
        c.concat(ters)
        perde.setBounds(0, 0, g, y)
        perde.draw(c)
        return b
    }

    /** Degrade yerine küçük bir bitmap şerit: GPU'da tam genişlikte degrade (titreşim önleyiciyle) pahalı, doku ucuz. */
    private fun serit(d: android.graphics.drawable.Drawable, yukseklik: Int): android.graphics.drawable.Drawable {
        val h = (yukseklik / 4).coerceAtLeast(2)
        val b = android.graphics.Bitmap.createBitmap(1, h, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 1, h)
        d.draw(Canvas(b))
        return android.graphics.drawable.BitmapDrawable(resources, b).apply { isFilterBitmap = true }
    }

    /**
     * Yeni tablo gelince 9 saniyede hafifçe yerine oturur (yakından uzaklaşır) ve durur. Eskiden sürekli
     * yaklaşıp uzaklaşıyordu: boş ekranda saniyede 28 kare, bir çekirdeğin yarısı. Artık sakin ekran hiç çizilmez.
     */
    private var yerlesme: ObjectAnimator? = null

    private fun yerles(v: View) {
        yerlesmeyiBitir()
        v.post {
            v.pivotX = v.width * (0.3f + Random.nextFloat() * 0.4f)
            v.pivotY = v.height * (0.3f + Random.nextFloat() * 0.4f)
            if (!hasWindowFocus()) return@post
            // Belirme (alpha) animasyonuna dokunmasın diye ayrı animatör.
            yerlesme = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.035f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.035f, 1f)).apply {
                duration = 9_000
                interpolator = DecelerateInterpolator(1.4f)
                start()
            }
        }
    }

    private fun yerlesmeyiBitir() {
        yerlesme?.cancel()
        yerlesme = null
        for (d in listOf(duvarA, duvarB)) { d.scaleX = 1f; d.scaleY = 1f }
    }

    // ---------------------------------------------------------------- dock

    private fun uygulamalariYukle() {
        Ag.arkada {
            val liste = Uygulamalar.hepsi(this)
            Ag.anada {
                if (isDestroyed) return@anada
                uygulamalar = liste
                val odakSirasi = currentFocus?.let { dock.indexOfChild(it) } ?: -1
                dockKur(if (odakSirasi >= 0) odakSirasi else 0, odakla = katmanYigini.isEmpty() && (odakSirasi >= 0 || currentFocus == null || currentFocus === vitrin))
                rafImzasi = ""
                raflariKur()
            }
        }
    }

    private fun dockKur(odakSirasi: Int = 0, odakla: Boolean = true) {
        tasimaBitir(kaydet = false)
        dock.removeAllViews()
        dugmeler.removeAllViews()
        val g = dp(KARO_G)
        val y = dp(KARO_Y)
        val liste = Uygulamalar.dock(this, uygulamalar)
        for (u in liste) {
            val k = Karo.uygulama(this, u, g, y)
            k.setTag(R.id.paket, u.paket)
            k.setOnClickListener { Uygulamalar.ac(this, u) }
            k.setOnLongClickListener { uzunBasildi(); dockSecenekleri(u, k); true }
            dockaEkle(dock, k, u.ad)
        }
        dockaEkle(dock, Karo.simge(this, R.drawable.ic_izgara, y).apply { setOnClickListener { tumUygulamalar() } }, "Tüm uygulamalar")
        dockaEkle(dock, Karo.simge(this, R.drawable.ic_ayar, y).apply { setOnClickListener { ayarlar() } }, "Ayarlar")
        // Şeritte 7 uygulama görünür; fazlası ve en sondaki iki düğme sağa kaydıkça gelir.
        val lp = dockKaydirma.layoutParams
        val sigan = resources.displayMetrics.widthPixels - dp(48) - dockKutu.paddingLeft - dockKutu.paddingRight
        val yedi = SAKIN_UYGULAMA * g + (SAKIN_UYGULAMA - 1) * dp(12) + dockKaydirma.paddingLeft + dockKaydirma.paddingRight
        val hepsi = liste.size * g + 2 * y + (liste.size + 1) * dp(12) + dockKaydirma.paddingLeft + dockKaydirma.paddingRight
        lp.width = if (hepsi > minOf(yedi, sigan)) minOf(yedi, sigan) else ViewGroup.LayoutParams.WRAP_CONTENT
        dockKaydirma.layoutParams = lp
        dock.setPaddingRelative(0, 0, if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) 0 else g + dp(12), 0)
        seritIlk = 0
        dockKaydirma.scrollTo(0, 0)
        if (odakla) dock.getChildAt(odakSirasi.coerceAtMost(dock.childCount - 1))?.requestFocus()
        // Yerleşim bitince: odaktaki kutu (ör. kaldırılanın solundaki) ekranın dışında kalmasın, uçta kesik kutu olmasın.
        dock.post {
            boslukAyarla()
            val odak = currentFocus
            if (odak != null && dock.icinde(odak)) seritKaydir(odak) else seritGorunurlugu(seritIlk)
        }
    }

    private fun dockaEkle(hedef: LinearLayout, k: Karo, ad: String) {
        k.odaklaninca = { odak ->
            if (odak) {
                seritKaydir(k)
                // Satırlardan dönülüyorsa şerit önce yerine otursun; damla ve ışık kayma bitince belirsin.
                val bekle = if (genis) SECIS_MS else SECIS_MS - (SystemClock.uptimeMillis() - sonDaralma)
                if (bekle > 0) ana.postDelayed({ if (k.hasFocus()) { isik.goster(k, k.renk); damla.git(k) } }, bekle)
                else { isik.goster(k, k.renk); damla.git(k) }
                ipucuZamanla(k)
            } else {
                ipucuGizle()
                k.post { if (!dockKutu.hasFocus()) { isik.gizle(); damla.gizle() } }
            }
        }
        k.sade = true
        hedef.addView(k, LinearLayout.LayoutParams(k.layoutParams.width, k.layoutParams.height).apply {
            if (hedef.childCount > 0) marginStart = dp(12)
        })
    }

    /** Şeritte en soldaki görünen kutu; kaydırma kutu kutu olur, uçlarda yarım uygulama kalmaz. */
    private var seritIlk = 0

    private fun seritKaydir(k: View) {
        val i = dock.indexOfChild(k)
        if (i < 0) return
        val kayan = dockKaydirma.layoutParams.width != ViewGroup.LayoutParams.WRAP_CONTENT
        val pencere = dockKaydirma.width - dockKaydirma.paddingLeft - dockKaydirma.paddingRight
        if (pencere <= 0 || k.width == 0) { dockKaydirma.post { if (k.hasFocus()) seritKaydir(k) }; return }
        if (kayan) {
            if (i < seritIlk) seritIlk = i
            while (seritIlk < i && dock.getChildAt(i).right - dock.getChildAt(seritIlk).left > pencere) seritIlk++
            dockKaydirma.smoothScrollTo(dock.getChildAt(seritIlk).left, 0)
            seritGorunurlugu(seritIlk)
        }
    }

    /** [ilk]'ten başlayan pencereye tam sığmayan kutular söner: şeridin uçlarında yarım kesik uygulama görünmez. */
    private fun seritGorunurlugu(ilk: Int) {
        if (dockKaydirma.layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT || dock.childCount == 0) {
            for (j in 0 until dock.childCount) dock.getChildAt(j).alpha = 1f
            return
        }
        val pencere = dockKaydirma.width - dockKaydirma.paddingLeft - dockKaydirma.paddingRight
        if (pencere <= 0) return
        val sol = dock.getChildAt(ilk.coerceIn(0, dock.childCount - 1)).left
        for (j in 0 until dock.childCount) {
            val c = dock.getChildAt(j)
            val tam = c.left >= sol && c.right <= sol + pencere
            val hedef = if (tam) 1f else 0f
            if (c.alpha != hedef) c.animate().alpha(hedef).setDuration(160).start()
        }
    }

    /** En üstteki maç kartı (temizle düğmesinden aşağı inince). */
    private fun ustMacKarti(): View? =
        (ezanSatiri.getChildAt(0) as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0)

    private fun ilkKart(): View? {
        val b = bloklar.values.firstOrNull() ?: return null
        if (!b.kuruldu) kartlariKur(b)
        return b.kartlar.firstOrNull()
    }

    /** Sakin hâlde dock ekranın altında dursun: üstündeki boşluk ekranı dolduracak kadar. */
    private fun boslukAyarla() {
        if (genis || sayfa.height == 0) return
        val dockYuksekligi = odakAdi.height + dp(12) + dp(KARO_Y) + dp(32)
        val h = (sayfa.height - sayfa.paddingTop - dockYuksekligi - dp(28)).coerceAtLeast(0)
        if (bosluk.layoutParams.height != h) {
            bosluk.layoutParams = bosluk.layoutParams.apply { height = h }
        }
    }

    // ---------------------------------------------------------------- uzun bas ipucu, taşıma

    /** Şeritteki uygulamanın üstünde biraz durunca: uzun basınca menü açıldığını söyle (bir kez öğrenince bir daha çıkmaz). */
    private fun ipucuZamanla(k: Karo) {
        ana.removeCallbacks(ipucuGorevi)
        if (tasinan != null) return
        if (k.getTag(R.id.paket) == null || Salon.ayarlar(this).getBoolean("uzun_bas_bilindi", false)) return
        ana.postDelayed(ipucuGorevi, 2_500)
    }

    private fun ipucuGoster() {
        if (tasinan != null || currentFocus?.let { dock.icinde(it) } != true) return
        odakAdi.text = "SEÇENEKLER İÇİN TAMAM'I BASILI TUT"
        odakAdi.alpha = 0f
        odakAdi.animate().alpha(1f).setDuration(400).start()
    }

    private fun ipucuGizle() {
        ana.removeCallbacks(ipucuGorevi)
        if (tasinan == null && odakAdi.text.isNotEmpty()) odakAdi.text = ""
    }

    private fun uzunBasildi() {
        ipucuGizle()
        Salon.ayarlar(this).edit().putBoolean("uzun_bas_bilindi", true).apply()
    }

    /** Taşıma modu: kutu pirinç çerçeveyle işaretlenir, sağ/sol komşusuyla yer değiştirir, Tamam ya da Geri bırakır. */
    private fun tasimaBasla(k: Karo) {
        tasinan = k
        k.tasiniyor = true
        k.requestFocus()
        odakAdi.text = "◀  ▶  İLE KAYDIR  ·  TAMAM İLE BIRAK"
        odakAdi.alpha = 1f
    }

    private fun tasimaTusu(k: Karo, e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (e.action != KeyEvent.ACTION_DOWN) return true
                val i = dock.indexOfChild(k)
                val uygulamaSayisi = (0 until dock.childCount).count { dock.getChildAt(it).getTag(R.id.paket) != null }
                val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                if (i < 0 || j < 0 || j >= uygulamaSayisi) return true
                // Odaktaki kutu yerinden sökülmez (odak kaybolur); komşusu öbür yanına alınır.
                val komsu = dock.getChildAt(j)
                dock.removeViewAt(j)
                dock.addView(komsu, i)
                for (n in 0 until dock.childCount) {
                    (dock.getChildAt(n).layoutParams as ViewGroup.MarginLayoutParams).marginStart = if (n == 0) 0 else dp(12)
                }
                dock.requestLayout()
                dock.post { seritKaydir(k); isik.goster(k, k.renk) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_UP) tasimaBitir()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
        }
        return false
    }

    private fun tasimaBitir(kaydet: Boolean = true) {
        val k = tasinan ?: return
        tasinan = null
        k.tasiniyor = false
        odakAdi.text = ""
        if (kaydet) {
            val sira = (0 until dock.childCount).mapNotNull { dock.getChildAt(it).getTag(R.id.paket) as? String }
            Uygulamalar.dockKaydet(this, sira)
        }
    }

    // ---------------------------------------------------------------- sakin / geniş hâl

    private fun odakDegisti(v: View?) {
        if (v == null || cizimde || katmanYigini.isNotEmpty()) return
        if (!dockKutu.icinde(v)) { isik.gizle(); damla.gizle(hemen = raflar.icinde(v)) }
        when {
            v === vitrin || dockKutu.icinde(v) -> daralt()
            raflar.icinde(v) -> {
                genislet()
                var satir: View = v
                while (satir.parent !== raflar) satir = satir.parent as? View ?: return
                komsulariKur(satir)
                odakSatiri = satir
                satirGorunurlugu()
                kaydir(bosluk.height + (raflar.top - bosluk.bottom) + satir.top - dp(26))
            }
        }
    }

    private fun kaydir(y: Int) {
        sayfa.post { sayfa.smoothScrollTo(0, y.coerceAtLeast(0)) }
    }

    private fun genislet() {
        if (genis) return
        genis = true
        isik.gizle()
        // Satırlar açıkken sakin ekranın görünmeyen maç kartları odak almasın (yoksa odak kaybolup kazara maç sayfası açılıyordu).
        solUst.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (sagUst as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // Gruplar animasyon boyunca tek GPU katmanı olarak oynar: her karede saydamlık için ekran dışında yeniden çizilmesin.
        for (v in listOf(solUst, sagUst, temizleKutu)) v.animate().alpha(0f).translationY(-dp(26).toFloat()).setDuration(300).withLayer().start()
        temizleDugme.isFocusable = false
        sergiDugme.isFocusable = false
        plakaKutusu.animate().alpha(0f).setDuration(200).start()
        ustCubuk.translationY = -dp(14).toFloat()
        ustCubuk.animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(DecelerateInterpolator()).withLayer().start()
        sayfaPerdesi.animate().alpha(1f).setDuration(400).setUpdateListener { duvarFiltresi() }.start()
        altSolma.animate().alpha(1f).setDuration(400).start()
        yerlesmeyiBitir()
        raflariKur()
        satirGorunurlugu()
    }

    /** Satırlardan şeride dönüş anı (damla, sayfa kayması bitince belirsin diye). */
    private var sonDaralma = 0L

    private fun daralt() {
        if (!genis) return
        genis = false
        sonDaralma = SystemClock.uptimeMillis()
        solUst.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        (sagUst as ViewGroup).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        for (v in listOf(solUst, sagUst)) v.animate().alpha(1f).translationY(0f).setDuration(360).withLayer().withEndAction { camYeriniTazele(0) }.start()
        temizleKutu.animate().alpha(1f).translationY(0f).setDuration(360).withLayer().start()
        temizleDugme.isFocusable = true
        sergiDugme.isFocusable = true
        plakaKutusu.animate().alpha(1f).setDuration(400).start()
        ustCubuk.animate().alpha(0f).translationY(-dp(14).toFloat()).setDuration(260).withLayer().start()
        sayfaPerdesi.animate().alpha(0f).setDuration(400).setUpdateListener { duvarFiltresi() }.start()
        altSolma.animate().alpha(0f).setDuration(300).start()
        sayfa.smoothScrollTo(0, 0)
        // Bir dahaki inişte satırlar baştan görünsün.
        for (b in bloklar.values) b.kaydirma.scrollTo(0, 0)
        odakSatiri = null
        satirGorunurlugu()
        if (!dockKutu.hasFocus() && tasinan == null) odakAdi.text = ""
    }

    // ---------------------------------------------------------------- satırlar

    /**
     * Ekrandaki bir satır. Kartları ([kuruldu]) hemen değil, odak o satıra yaklaşınca kurulur: 12 satırın
     * ~180 kartını her dönüşte baştan kurmak ana iş parçacığını saniyelerce kilitliyordu. Veri değişmeyen
     * satıra dokunulmaz.
     */
    private inner class RafBlok(var raf: Raf) {
        val kok = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setTag(R.id.raf, raf.anahtar)
        }
        val satir = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setTag(R.id.satir, true)
        }
        var kartlar = listOf<Kart>()
        var kuruldu = false
        val g get() = dp(if (raf.poster) POSTER_G else VIDEO_G)
        val y get() = dp(if (raf.poster) POSTER_Y else VIDEO_Y)
        val kaydirma = HorizontalScrollView(this@MainActivity).apply {
            setOnScrollChangeListener { v, x, _, _, _ ->
                val sinir = x + v.width + g * 2
                for (k in kartlar) if ((k.parent as View).left < sinir) k.yukle()
                kartGorunurlugu(this@RafBlok)
            }
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(12), dp(40), dp(10))
            addView(satir)
        }

        /** Odaktaki kartın üstündeki cam damla (şeritteki gibi); içinde satırın kendisi bükülür. */
        val damla = CamDamla(this@MainActivity, payDp = 6f, yaricapDp = 20f) { c ->
            // Arkası: satırlar açıkken karartılmış tablonun bulanığı, üstünde satırın kartları (kaydırma dahil).
            Cam.arkaCiz(c, cerceve, SAYFA_KARARTMA)
            c.save()
            c.translate((kaydirma.left - kaydirma.scrollX).toFloat(), (kaydirma.top - kaydirma.scrollY).toFloat())
            kaydirma.draw(c)
            c.restore()
        }
        val cerceve = FrameLayout(this@MainActivity).apply { clipChildren = false; clipToPadding = false }

        init {
            kok.addView(rafBasligi(raf))
            cerceve.addView(kaydirma, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cerceve.addView(damla, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            kok.addView(cerceve)
            satir.minimumHeight = y + dp(46)
        }

        fun guncelle(yeni: Raf) {
            val baslikDegisti = yeni.baslik != raf.baslik || yeni.paket != raf.paket
            raf = yeni
            if (baslikDegisti) { kok.removeViewAt(0); kok.addView(rafBasligi(yeni), 0) }
            if (kuruldu) kartlariKur(this)
        }
    }

    private var bloklar = LinkedHashMap<String, RafBlok>()
    /** Damlası görünen satır. */
    private var damlaSatiri: RafBlok? = null
    private var bosYazi: View? = null

    private fun rafKurBekle(ms: Long) {
        ana.removeCallbacks(rafGorevi)
        ana.postDelayed(rafGorevi, ms)
    }

    /** Önce TV'deki önbellekle hemen çiz, sonra internetten tazele; değişen bir şey yoksa ellenmez. */
    private fun raflariKur() {
        if (rafGetiriliyor || uygulamalar.isEmpty()) return
        rafGetiriliyor = true
        val yuklu = uygulamalar.map { it.paket }.toSet()
        Ag.arkada {
            try {
                val hizli = Oneriler.raflar(this, yuklu, sadeceOnbellek = true)
                Ag.anada { rafCiz(hizli) }
                Oneriler.eslesmeleriHazirla(this)
                val tam = Oneriler.raflar(this, yuklu, sadeceOnbellek = false)
                Ag.anada { rafCiz(tam) }
            } finally {
                Ag.anada { rafGetiriliyor = false }
            }
        }
    }

    private fun rafCiz(hepsi: List<Raf>) {
        if (isDestroyed) return
        sonRaflar = hepsi
        val gizli = Oneriler.gizli(this)
        val liste = hepsi.filter { it.anahtar !in gizli }
        val imza = liste.joinToString("\n") { it.anahtar + it.imza }
        if (imza == rafImzasi) return
        rafImzasi = imza
        cizimde = true
        val yeni = LinkedHashMap<String, RafBlok>()
        for (raf in liste) {
            val b = bloklar[raf.anahtar]
            if (b == null) yeni[raf.anahtar] = RafBlok(raf)
            else {
                if (b.raf.imza != raf.imza || b.raf.baslik != raf.baslik) b.guncelle(raf)
                yeni[raf.anahtar] = b
            }
        }
        // Sırayı uygula: yerinde olan satıra dokunma, kalkan satırları çıkar.
        val odakVardi = currentFocus?.let { raflar.icinde(it) } == true
        for ((i, b) in yeni.values.withIndex()) {
            if (raflar.getChildAt(i) === b.kok) continue
            (b.kok.parent as? ViewGroup)?.removeView(b.kok)
            raflar.addView(b.kok, i, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(26)
            })
        }
        while (raflar.childCount > yeni.size) raflar.removeViewAt(yeni.size)
        bloklar = yeni
        if (liste.isEmpty()) {
            val t = bosYazi ?: TextView(this).apply {
                text = "İnternete bağlanınca burada izlemeye devam ettiklerin ve öneriler çıkacak."
                stil(Yazi.serifItalik, 19f, Salon.MUREKKEP)
                setPadding(dp(2), dp(4), 0, dp(30))
            }.also { bosYazi = it }
            raflar.addView(t)
        }
        // Şeritten aşağı inince hemen hazır olsun: ilk iki satır kurulu dursun.
        for (b in yeni.values.take(2)) if (!b.kuruldu) kartlariKur(b)
        cizimde = false
        if (odakVardi && currentFocus?.let { raflar.icinde(it) } != true) (ilkKart() ?: dock.getChildAt(0))?.requestFocus()
        ana.removeCallbacks(onKurGorevi)
        ana.postDelayed(onKurGorevi, 1_000)
        satirGorunurlugu()
    }

    /** Satırın kartlarını kur; odak bu satırdaysa aynı karta (yoksa aynı sıraya) geri döner. */
    private fun kartlariKur(b: RafBlok) {
        val odak = currentFocus as? Kart
        val odakBurada = odak != null && b.satir.icinde(odak)
        val odakKimlik = if (odakBurada) odak!!.oge.kimlik else null
        val odakSira = if (odakBurada) b.satir.indexOfChild(odak!!.parent as View) else -1
        val eskiCizimde = cizimde
        cizimde = true
        b.satir.removeAllViews()
        val raf = b.raf
        val g = b.g
        val y = b.y
        val kartlar = ArrayList<Kart>(raf.ogeler.size)
        for (o in raf.ogeler) {
            val (kutu, kart) = Kart.kutulu(this, o, g, y, yaziGoster = true)
            kart.sade = true
            kart.odaklaninca = { f ->
                if (f) {
                    // Satır değiştiyse sayfa kayarken damla çizilmesin (dikey geçişte GPU'yu kare başına ~90 ms
                    // yoruyordu): kayma bitince belirsin. Aynı satırda sağa-sola anında akar.
                    if (damlaSatiri === b) b.damla.git(kart)
                    else {
                        damlaSatiri = b
                        ana.postDelayed({ if (kart.hasFocus()) b.damla.git(kart) }, SECIS_MS)
                    }
                } else kart.post { if (!b.satir.hasFocus()) { b.damla.gizle(hemen = true); if (damlaSatiri === b) damlaSatiri = null } }
            }
            kart.setOnClickListener { o.ac(this) }
            kart.setOnLongClickListener { ogeSecenekleri(o); true }
            b.satir.addView(kutu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (b.satir.childCount > 0) marginStart = dp(if (raf.poster) 16 else 20)
            })
            kartlar += kart
        }
        b.kartlar = kartlar
        b.kuruldu = true
        b.satir.minimumHeight = 0
        kartlar.take(7).forEach { it.yukle() }
        b.kaydirma.post { kartGorunurlugu(b) }
        cizimde = eskiCizimde
        if (odakBurada) (kartlar.firstOrNull { it.oge.kimlik == odakKimlik } ?: kartlar.getOrNull(odakSira.coerceAtMost(kartlar.size - 1)))?.requestFocus()
    }

    /**
     * Odak bir satıra girince komşu satırlar kurulur — odak karesinde değil, hemen sonraki mesajlarda (her satır
     * ayrı), ki odak animasyonu takılmasın. Kumandaya çok hızlı basılırsa [altSatirHazir] o an kurar.
     */
    private fun komsulariKur(satir: View) {
        val liste = bloklar.values.toList()
        val i = liste.indexOfFirst { it.kok === satir }
        if (i < 0) return
        for (b in listOfNotNull(liste.getOrNull(i + 1), liste.getOrNull(i - 1), liste.getOrNull(i + 2))) {
            if (!b.kuruldu) raflar.post { if (!b.kuruldu) kartlariKur(b) }
        }
    }

    /** Odaktaki satırın kökü (satırlar açıkken). */
    private var odakSatiri: View? = null

    /**
     * Çizim ağacını küçük tut: ekrandan uzak satırlar çizilmez (kurulu durur). Her karede ağaçtaki bütün düğümler
     * dolaşılıyordu (~1250); satırlara inerken kareleri bu kaçırtıyordu. Açık kalanlar: ilk satır (şeritten
     * aşağı inilecek), ekranda görünenler ve odağın bir üstü / iki altı (odak oraya gidebilsin).
     */
    private fun satirGorunurlugu() {
        val liste = bloklar.values.toList()
        if (liste.isEmpty()) return
        val i = liste.indexOfFirst { it.kok === odakSatiri }.takeIf { genis } ?: -1
        val ust = sayfa.scrollY - raflar.top
        val alt = ust + sayfa.height
        for ((n, b) in liste.withIndex()) {
            val ekranda = genis && b.kok.bottom > ust && b.kok.top < alt
            val acik = n == 0 || ekranda || (i >= 0 && n in (i - 1)..(i + 2))
            val v = if (acik) View.VISIBLE else View.INVISIBLE
            if (b.kok.visibility != v) b.kok.visibility = v
        }
    }

    /** Satırın içinde de: görünen pencerenin iki kart ötesinden uzaktaki kartlar çizilmez. */
    private fun kartGorunurlugu(b: RafBlok) {
        val x = b.kaydirma.scrollX
        val pay = b.g * 2
        val sol = x - pay
        val sag = x + b.kaydirma.width + pay
        for (n in 0 until b.satir.childCount) {
            val c = b.satir.getChildAt(n)
            val v = if (c.right > sol && c.left < sag) View.VISIBLE else View.INVISIBLE
            if (c.visibility != v) c.visibility = v
        }
    }

    /**
     * Satırlar arası aşağı/yukarı: odağı FocusFinder'a bırakmadan biz taşırız. Art arda basınca hedef satır
     * henüz kurulmamış, dizilmemiş ya da gizli olabiliyordu; FocusFinder onu göremeyip basışları yutuyor,
     * sonra bir anda toparlanıyordu. Hedefte aynı ekran sütunundaki kart seçilir. İlk satırdan yukarı: şerit (varsayılan).
     */
    private fun dikeyGit(odak: View, asagi: Boolean): Boolean {
        val kutu = (odak as? Kart)?.parent as? View ?: return false
        var satirKok: View = kutu
        while (satirKok.parent !== raflar) satirKok = satirKok.parent as? View ?: return false
        val liste = bloklar.values.toList()
        val i = liste.indexOfFirst { it.kok === satirKok }
        if (i < 0) return false
        val j = if (asagi) i + 1 else i - 1
        if (j < 0) return false
        // En alt satırdaysan basış boşa gitsin, odak yerinde kalsın.
        val hedef = liste.getOrNull(j) ?: return true
        if (!hedef.kuruldu) kartlariKur(hedef)
        if (hedef.kartlar.isEmpty()) return false
        hedef.kok.visibility = View.VISIBLE
        val simdiki = liste[i]
        val sutun = ((kutu.left - simdiki.kaydirma.scrollX) / simdiki.g.coerceAtLeast(1)).coerceAtLeast(0)
        val k = hedef.kartlar[(hedef.kaydirma.scrollX / hedef.g.coerceAtLeast(1) + sutun).coerceIn(0, hedef.kartlar.size - 1)]
        (k.parent as? View)?.visibility = View.VISIBLE
        k.requestFocus()
        return true
    }

    private val rafIkonlari = HashMap<String, android.graphics.drawable.Drawable.ConstantState?>()

    private fun rafBasligi(raf: Raf): View {
        val satir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        raf.paket?.let { p ->
            rafIkonlari.getOrPut(p) { runCatching { packageManager.getApplicationIcon(p).constantState }.getOrNull() }?.newDrawable(resources)?.let { ikon ->
                satir.addView(ImageView(this).apply { setImageDrawable(ikon) },
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            }
        }
        satir.addView(TextView(this).apply {
            text = raf.baslik
            stil(Yazi.sansKalin, 15f, Salon.MUREKKEP, 0.02f, golge = false)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        return satir
    }

    private fun ogeSecenekleri(o: Oge) {
        val maddeler = mutableListOf<Pair<String, () -> Unit>>("Aç" to { o.ac(this) })
        for ((ad, is_) in o.secenekler) maddeler += ad to {
            is_(this)
            rafImzasi = ""
            raflariKur()
        }
        secenekler(o.baslik, maddeler)
    }

    private fun dockSecenekleri(u: Uygulama, k: Karo) {
        val paketler = Uygulamalar.dockPaketleri(this, uygulamalar).toMutableList()
        val i = paketler.indexOf(u.paket)
        val maddeler = mutableListOf<Pair<String, () -> Unit>>()
        if (paketler.size > 1) maddeler += "Taşı" to { tasimaBasla(k) }
        maddeler += "Ana ekrandan kaldır" to {
            paketler.remove(u.paket)
            Uygulamalar.dockKaydet(this, paketler)
            dockKur(maxOf(0, i - 1))
            bildir("${u.ad} ana ekrandan kaldırıldı")
        }
        maddeler += "Uygulama bilgisi" to { uygulamaBilgisi(u) }
        secenekler(u.ad, maddeler)
    }

    // ---------------------------------------------------------------- sergi seçimi

    /**
     * Sağ üstteki Sergi düğmesi: iki sekme. "Resimler" bildiğimiz sergi (tablolar, uzay); "Hareketli"
     * şömine, yağmur, kar gibi döngü videolar. Hareketliler otomatik sergiye girmez, sadece buradan açılır.
     */
    private fun sergiSec(sekme: Int = 0) {
        val kutu = FrameLayout(this).apply { isClickable = true }
        kutu.background = SiviCam(kutu, 0f, mercekDp = 0f, enAzKoyu = .66f)
        val ic = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(36), dp(56), 0)
            clipChildren = false; clipToPadding = false
        }
        ic.addView(TextView(this).apply { text = "Sergi"; stil(Yazi.serif, 44f) })
        val sekmeler = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(18)) }
        ic.addView(sekmeler)
        val icerik = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        ic.addView(icerik, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        kutu.addView(ic, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val yaziRengi = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(KOYU_YAZI, KOYU_YAZI, Salon.MUREKKEP))
        var ilkOdak: View? = null
        val adlar = listOf("Resimler", "Hareketli")
        val dugmeler = adlar.mapIndexed { i, ad ->
            TextView(this).apply {
                text = ad
                stil(Yazi.sansKalin, 15f, golge = false)
                setTextColor(yaziRengi)
                setPadding(dp(22), dp(10), dp(22), dp(10))
                isFocusable = true; isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Salon.MUREKKEP); cornerRadius = dp(20).toFloat() })
                    addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { setColor(Salon.PIRINC); cornerRadius = dp(20).toFloat() })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = dp(20).toFloat() })
                }
            }.also { sekmeler.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }) }
        }
        fun goster(i: Int) {
            dugmeler.forEachIndexed { j, d -> d.isSelected = j == i }
            icerik.removeAllViews()
            icerik.addView(if (i == 0) sergiResimler() else sergiHareketli(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        dugmeler.forEachIndexed { i, d ->
            d.setOnFocusChangeListener { _, f -> if (f && !d.isSelected) goster(i) }
            d.setOnClickListener { (icerik.getChildAt(0) as? ViewGroup)?.let { v -> v.focusSearch(View.FOCUS_DOWN) }; icerik.requestFocus(View.FOCUS_DOWN) }
        }
        goster(sekme)
        ilkOdak = dugmeler[sekme]
        katmanAc(kutu, ilkOdak)
    }

    private fun sergiResimler(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        v.addView(TextView(this).apply {
            text = Ek.SERGI_YAZISI
            stil(Yazi.sans, 15f, Salon.SOLUK)
            setPadding(dp(2), 0, 0, dp(18))
        })
        v.addView(TextView(this).apply {
            text = "Resimlerle sergiyi başlat"
            stil(Yazi.sansKalin, 16f, golge = false)
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(KOYU_YAZI, Salon.MUREKKEP)))
            setPadding(dp(26), dp(14), dp(26), dp(14))
            isFocusable = true; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Salon.MUREKKEP); cornerRadius = dp(24).toFloat() })
                addState(intArrayOf(), GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(24).toFloat() })
            }
            setOnClickListener { katmanKapat(odakGeriVer = false); startActivity(Intent(this@MainActivity, SergiActivity::class.java)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return v
    }

    /** Hareketli sahneler: kategori sırasıyla küçük resimli kartlar. */
    private fun sergiHareketli(): View {
        val izgara = GridLayout(this).apply { columnCount = 5; useDefaultMargins = false; clipChildren = false; clipToPadding = false }
        for (sh in Sahneler.hepsi(this)) {
            val kart = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                clipChildren = false
            }
            val resim = KartResmi(this, dp(12).toFloat()).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            Resim.yukle(resim, sh.resim, dp(170))
            val cerceve = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setStroke(dp(2.5f), Salon.MUREKKEP) }
            val resimKutu = FrameLayout(this).apply { addView(resim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            kart.addView(resimKutu, LinearLayout.LayoutParams(dp(158), dp(89)))
            val ad = TextView(this).apply {
                text = sh.ad; stil(Yazi.sansKalin, 12f, Salon.MUREKKEP, golge = false)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(7), 0, 0); alpha = .85f
            }
            val kat = TextView(this).apply { text = sh.kat.buyukTr(); stil(Yazi.sansKalin, 9f, Salon.SOLUK, 0.16f, golge = false); setPadding(dp(2), dp(2), 0, 0) }
            kart.addView(ad); kart.addView(kat)
            kart.setOnFocusChangeListener { _, f ->
                resimKutu.foreground = if (f) cerceve else null
                ad.alpha = if (f) 1f else .85f
                kart.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).translationZ(if (f) dp(10).toFloat() else 0f)
                    .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
            }
            kart.setOnClickListener { katmanKapat(odakGeriVer = false); HareketliSergiActivity.ac(this, sh) }
            izgara.addView(kart, GridLayout.LayoutParams().apply { width = dp(158); setMargins(dp(8), dp(6), dp(8), dp(14)) })
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false; clipChildren = false
            setPadding(0, dp(6), 0, dp(48))
            addView(izgara)
        }
    }

    // ---------------------------------------------------------------- tüm uygulamalar

    private fun tumUygulamalar() {
        val kutu = FrameLayout(this).apply { isClickable = true }
        val kutuIsik = Ambilight(kutu)
        kutu.background = SiviCam(kutu, 0f, mercekDp = 0f, enAzKoyu = .62f)
        val ic = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(40), dp(56), 0)
            clipChildren = false
            clipToPadding = false
        }
        ic.addView(TextView(this).apply { text = "Tüm uygulamalar"; stil(Yazi.serif, 44f) })
        ic.addView(TextView(this).apply {
            text = "${uygulamalar.size} uygulama  ·  Ana ekrana eklemek için uygulamanın üstünde Tamam tuşunu basılı tut"
            stil(Yazi.sans, 13f, Salon.SOLUK)
        })
        val izgara = GridLayout(this).apply {
            columnCount = 5
            clipChildren = false
            clipToPadding = false
            useDefaultMargins = false
        }
        val dockta = Uygulamalar.dockPaketleri(this, uygulamalar).toSet()
        for (u in uygulamalar) {
            val k = Karo.uygulama(this, u, dp(148), dp(83))
            k.odaklaninca = { if (it) kutuIsik.goster(k, k.renk) }
            k.setOnClickListener { katmanKapat(odakGeriVer = false); Uygulamalar.ac(this, u) }
            k.setOnLongClickListener { uzunBasildi(); uygulamaSecenekleri(u, u.paket in dockta); true }
            izgara.addView(k, GridLayout.LayoutParams().apply {
                width = dp(148); height = dp(83)
                setMargins(dp(10), dp(10), dp(10), dp(10))
            })
        }
        val kaydirma = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(0, dp(18), 0, dp(48))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(izgara, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            setOnScrollChangeListener { _, _, _, _, _ -> (currentFocus as? Karo)?.let { kutuIsik.izle(it) } }
        }
        ic.addView(kaydirma, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        kutu.addView(ic, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        katmanAc(kutu, izgara.getChildAt(0))
    }

    private fun uygulamaSecenekleri(u: Uygulama, dockta: Boolean) {
        val maddeler = mutableListOf<Pair<String, () -> Unit>>()
        if (dockta) {
            maddeler += "Ana ekrandan kaldır" to {
                Uygulamalar.dockKaydet(this, Uygulamalar.dockPaketleri(this, uygulamalar) - u.paket)
                dockKur(odakla = false)
                bildir("${u.ad} ana ekrandan kaldırıldı")
            }
        } else {
            maddeler += "Ana ekrana ekle" to {
                Uygulamalar.dockKaydet(this, Uygulamalar.dockPaketleri(this, uygulamalar) + u.paket)
                dockKur(odakla = false)
                bildir("${u.ad} ana ekrana eklendi · şeridin sonunda")
            }
        }
        maddeler += "Uygulama bilgisi" to { uygulamaBilgisi(u) }
        maddeler += "Kaldır" to {
            runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${u.paket}"))) }
        }
        secenekler(u.ad, maddeler)
    }

    private fun uygulamaBilgisi(u: Uygulama) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${u.paket}")))
        }
    }

    // ---------------------------------------------------------------- ayarlar

    private fun ayarlar() {
        val yardimci = evTusuYardimcisiAcik()
        val gtvVarsayilan = Salon.ayarlar(this).getBoolean("varsayilan_gtv", false)
        secenekler("BerkOS", listOf(
            "Duvar resmini değiştir" to { duvarIleri() },
            "Duvar resmi seç" to { duvarSec() },
            "Sergiyi şimdi başlat" to { startActivity(Intent(this, SergiActivity::class.java)) },
            (if (yardimci) "Ev tuşu yardımcısı: açık" else "Ev tuşu yardımcısını aç") to {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
            "Satırları düzenle" to { satirlariDuzenle() },
            "Şehir: ${Salon.konum(this).ad}" to { sehirSec() },
            "Varsayılan ana ekran: ${if (gtvVarsayilan) "Google TV" else "BerkOS"}" to { varsayilanSec() },
            "Google TV ana ekranını aç" to { googleTvAnaEkrani() },
            "TV ayarları" to { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } },
        ), dipnot = surumYazisi())
    }

    /** "BerkOS 1.1 · sürüm 26102614 · 26 Ekim 14:05'te kuruldu": hangi sürümün kurulu olduğu belli olsun. */
    private fun surumYazisi(): String = runCatching {
        val p = packageManager.getPackageInfo(packageName, 0)
        val kod = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        val zaman = Instant.ofEpochMilli(p.lastUpdateTime).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMMM HH:mm", Salon.TR))
        "BerkOS ${p.versionName}  ·  sürüm $kod  ·  $zaman kuruldu"
    }.getOrDefault("")

    /** Hava durumu (ve ezan vakitleri) hangi şehrin: 81 il merkezi. */
    private fun sehirSec() {
        val simdiki = Salon.konum(this).ad
        val iller = Sehirler.IL
        secenekler("Şehir", iller.map { il ->
            (if (il == simdiki) "✓  $il" else "     $il") to {
                Sehirler.konum(il)?.let { Salon.konumKaydet(this, it) }
                HavaDurumu.getir(this, ::havaGoster)
            }
        }, odakSira = iller.indexOf(simdiki).coerceAtLeast(0))
    }

    /** TV açılınca ve Ev tuşunda hangisi gelsin. */
    private fun varsayilanSec() {
        val p = Salon.ayarlar(this)
        secenekler("Varsayılan ana ekran", listOf(
            (if (!p.getBoolean("varsayilan_gtv", false)) "✓  BerkOS" else "     BerkOS") to {
                p.edit().putBoolean("varsayilan_gtv", false).apply()
            },
            (if (p.getBoolean("varsayilan_gtv", false)) "✓  Google TV" else "     Google TV") to {
                p.edit().putBoolean("varsayilan_gtv", true).apply()
            },
        ))
    }

    /** Aşağıdaki satırların sırası: bir satır seç, yukarı/aşağı taşı ya da gizle. Alt menüden Geri bu listeye döner. */
    private fun satirlariDuzenle(secili: String? = null) {
        val hepsi = sonRaflar
        if (hepsi.isEmpty()) return
        val sira = hepsi.map { it.anahtar }.toMutableList()
        val gizli = Oneriler.gizli(this).toMutableSet()
        val maddeler = hepsi.map { r ->
            val ad = r.baslik.substringBefore(" · ").substringBefore(" bu hafta").replace("'te", "").replace("'ta", "").replace("'da", "").replace("'de", "")
            ((if (r.anahtar in gizli) "○  " else "●  ") + ad) to { satirSecenekleri(r.anahtar, ad, sira, gizli) }
        }
        secenekler("Satırları düzenle", maddeler, odakSira = secili?.let { sira.indexOf(it) } ?: 0, altMenulu = true)
    }

    private fun satirSecenekleri(anahtar: String, ad: String, sira: MutableList<String>, gizli: MutableSet<String>) {
        val i = sira.indexOf(anahtar)
        fun uygula() {
            Oneriler.siralamaKaydet(this, sira)
            Oneriler.gizliKaydet(this, gizli)
            sonRaflar = sonRaflar.sortedBy { sira.indexOf(it.anahtar) }
            // Satırlar resimleriyle baştan kurulmaz: sadece yerleri değişir, gizlenen çıkar, gösterilen gelir.
            rafImzasi = ""
            rafCiz(sonRaflar)
            // Arkada açık kalan eski liste kapanır, güncel hâli aynı satır seçili açılır.
            katmanKapat(odakGeriVer = false)
            satirlariDuzenle(anahtar)
        }
        val m = mutableListOf<Pair<String, () -> Unit>>()
        if (i > 0) m += "Yukarı taşı" to { sira.add(i - 1, sira.removeAt(i)); uygula() }
        if (i < sira.size - 1) m += "Aşağı taşı" to { sira.add(i + 1, sira.removeAt(i)); uygula() }
        m += (if (anahtar in gizli) "Göster" else "Gizle") to { if (!gizli.remove(anahtar)) gizli += anahtar; uygula() }
        secenekler(ad, m)
    }

    private fun evTusuYardimcisiAcik(): Boolean {
        val acik = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return acik.contains("$packageName/")
    }

    /**
     * Arada bir Google'ın kendi ana ekranı lazım olursa: 10 dakika boyunca (ya da oradan bir uygulama açılana
     * kadar) yardımcı karışmaz. Köşede bunu söyleyen bir not çıkar; "BerkOS kayboldu" sanılmasın.
     */
    private fun googleTvAnaEkrani() {
        Salon.ayarlar(this).edit().putLong("stok_izin_bitis", System.currentTimeMillis() + 10 * 60_000).apply()
        for (p in Salon.STOK_ANA_EKRANLAR) {
            val niyet = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(p)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageManager.resolveActivity(niyet, 0) != null) {
                startActivity(niyet)
                EvTusuServisi.calisan?.koseYazisi("Google TV modu",
                    "BİR UYGULAMA AÇINCA YA DA 10 DK SONRA EV TUŞU YİNE SEYİR'İ AÇAR", 12_000)
                return
            }
        }
    }

    // ---------------------------------------------------------------- katmanlar, menüler, bildirim

    private fun katmanAc(v: View, odak: View?) {
        katmanYigini.addLast(v to currentFocus)
        katman.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        odakKilidi()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).start()
        (odak ?: v).requestFocus()
    }

    private fun katmanKapat(odakGeriVer: Boolean = true) {
        val (v, onceki) = katmanYigini.removeLastOrNull() ?: return
        katman.removeView(v)
        if (acikDetay?.kok === v) { acikDetay?.kapat(); acikDetay = null }
        odakKilidi()
        if (odakGeriVer) onceki?.takeIf { it.isAttachedToWindow }?.requestFocus()
            ?: (katmanYigini.lastOrNull()?.first ?: dock.getChildAt(0))?.requestFocus()
    }

    /** Kumanda okları sadece en üstteki katmanda gezsin; altta kalan dock'a ya da satırlara kaçmasın. */
    private fun odakKilidi() {
        val ust = katmanYigini.lastOrNull()?.first
        val serbest = if (ust == null) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        dockKutu.descendantFocusability = serbest
        raflar.descendantFocusability = serbest
        solUst.descendantFocusability = if (ust == null && !genis) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        temizleKutu.descendantFocusability = solUst.descendantFocusability
        (sagUst as ViewGroup).descendantFocusability = solUst.descendantFocusability
        vitrin.isFocusable = false
        for ((v, _) in katmanYigini) {
            (v as? ViewGroup)?.descendantFocusability =
                if (v === ust) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    /**
     * Ortada cam menü. Seçili madde açık renk dolgu + koyu yazı: 3 metreden "neredeyim" bir bakışta belli.
     * [altMenulu]: maddeler alt menü açar; bu menü arkada açık kalır, alt menüden Geri buraya döner.
     * [dipnot]: en altta seçilemeyen küçük yazı (sürüm bilgisi).
     * [odakta]: kumanda bir maddeye gelince sırasıyla çağrılır (duvar resmi önizlemesi).
     */
    private fun secenekler(
        baslik: String, maddeler: List<Pair<String, () -> Unit>>, odakSira: Int = 0,
        altMenulu: Boolean = false, dipnot: String = "", odakta: ((Int) -> Unit)? = null,
    ) {
        val arka = FrameLayout(this).apply {
            setBackgroundColor(0x73000000)
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(380)
            setPadding(dp(18), dp(22), dp(18), dp(16))
        }
        panel.background = SiviCam(panel, dp(26).toFloat(), mercekDp = 18f, enAzKoyu = .5f)
        panel.addView(TextView(this).apply {
            text = baslik
            stil(Yazi.serif, 30f, golge = false)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(520)
            setPadding(dp(14), 0, dp(14), dp(10))
        })
        var ilk: View? = null
        // Uzun listeler (satırları düzenle) ekrana sığmazsa menü kendi içinde kaysın.
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val kaydirma = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(liste)
        }
        val yaziRengi = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(KOYU_YAZI, Salon.MUREKKEP))
        for ((sira, madde_) in maddeler.withIndex()) {
            val (ad, is_) = madde_
            val madde = TextView(this).apply {
                text = ad
                stil(Yazi.sans, 16f, golge = false)
                setTextColor(yaziRengi)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isFocusable = true
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        setColor(Salon.MUREKKEP)
                        cornerRadius = dp(14).toFloat()
                    })
                }
                setOnFocusChangeListener { v, f ->
                    (v as TextView).typeface = if (f) Yazi.sansKalin else Yazi.sans
                    if (f) odakta?.invoke(sira)
                }
                setOnClickListener {
                    if (!altMenulu) katmanKapat()
                    is_()
                }
            }
            liste.addView(madde)
            if (ilk == null || liste.childCount - 1 == odakSira) ilk = madde
        }
        val enFazla = resources.displayMetrics.heightPixels - dp(150)
        panel.addView(kaydirma, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (maddeler.size > 9) enFazla else ViewGroup.LayoutParams.WRAP_CONTENT))
        if (dipnot.isNotBlank()) panel.addView(TextView(this).apply {
            text = dipnot
            stil(Yazi.sans, 11f, Salon.SOLUK, golge = false)
            setPadding(dp(14), dp(12), dp(14), 0)
        })
        arka.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        katmanAc(arka, ilk)
    }

    /** Ev tuşunda Google TV'nin üstüne atılan perde için sakin ana ekranın birebir görüntüsü. */
    private fun anaEkranGoruntusu() {
        if (genis || katmanYigini.isNotEmpty()) return
        val v = window.decorView
        if (v.width <= 0 || v.height <= 0) return
        val b = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(window, b, { sonuc ->
                if (sonuc == android.view.PixelCopy.SUCCESS) EvTusuServisi.calisan?.perdeResmi = b
            }, ana)
        }
    }

    // ---------------------------------------------------------------- arka planı temizle

    private fun temizleDugmesiKur() {
        val etiket = TextView(this).apply {
            stil(Yazi.sansKalin, 13f, Salon.MUREKKEP, 0.02f, golge = false)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { setColor(0xCC1A1714.toInt()); cornerRadius = dp(16).toFloat() }
            alpha = 0f
        }
        fun dugme(simge: Int, yazi: String, is_: () -> Unit) = ImageView(this).apply {
            setImageResource(simge)
            imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(KOYU_YAZI, Salon.MUREKKEP))
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Salon.MUREKKEP) })
                addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x591A1714); setStroke(dp(1), 0x4DFFFFFF) })
            }
            setOnFocusChangeListener { _, f ->
                if (f) etiket.text = yazi
                etiket.animate().alpha(if (f || sergiDugme.isFocused || temizleDugme.isFocused) 1f else 0f).setDuration(160).start()
            }
            setOnClickListener { is_() }
        }
        sergiDugme = dugme(R.drawable.ic_sergi, "Sergi") { sergiSec() }
        temizleDugme = dugme(R.drawable.ic_supurge, "Arka planı temizle") { arkaPlaniTemizle() }
        temizleKutu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(etiket, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(sergiDugme, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) })
            addView(temizleDugme, LinearLayout.LayoutParams(dp(38), dp(38)))
        }
        // Köşede, havanın üstünde. Açılan sayfaların (katman) altında kalsın: maç sayfasının üstünde görünmesin.
        kok.addView(temizleKutu, kok.indexOfChild(katman), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { topMargin = dp(10); marginEnd = dp(10) })
    }

    /** Son kullanılanlardaki uygulamaları (BerkOS ve sistem hariç) TV'nin kendi kabuğundan kapatır. */
    private fun arkaPlaniTemizle() {
        bildir("Arka plandaki uygulamalar kapatılıyor…")
        Ag.arkada {
            val sonuc = runCatching {
                val liste = Kabuk.calistir(this, "dumpsys activity recents")
                // Son kullanılanlar günler öncekileri de tutuyor: sadece şu an süreci çalışanlar sayılır.
                val calisan = Kabuk.calistir(this, "ps -A -o NAME").lines().map { it.trim().substringBefore(':') }.toSet()
                val paketler = Regex("""type=(?:standard|undefined) A=\d+:([\w.]+)""").findAll(liste)
                    .map { it.groupValues[1] }
                    .filter { it != packageName && it !in KAPATILMAZ && it !in Salon.STOK_ANA_EKRANLAR && it in calisan }
                    .distinct().toList()
                if (paketler.isNotEmpty()) Kabuk.calistir(this, paketler.joinToString("; ") { "am force-stop $it" })
                paketler.size
            }
            Ag.anada {
                sonuc.onSuccess { n -> bildir(if (n == 0) "Arka planda açık uygulama yok" else "$n uygulama kapatıldı") }
                    .onFailure {
                        bildir(if ((it as? Kabuk.Hata)?.message == "kapali") "Kapatma izni kapalı: TV yeniden başlamış, bilgisayardan kurulumu bir kez çalıştır"
                        else "Kapatılamadı")
                    }
            }
        }
    }

    private var bildirim: TextView? = null
    private val bildirimSonu = Runnable { bildirim?.animate()?.alpha(0f)?.setDuration(400)?.start() }

    /** Altta kısa bir onay yazısı ("Netflix ana ekrana eklendi"); 2,5 sn sonra söner. */
    private fun bildir(yazi: String) {
        val t = bildirim ?: TextView(this).apply {
            stil(Yazi.sansKalin, 13f, Salon.MUREKKEP, 0.02f, golge = false)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply { setColor(0xE61A1714.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0x4DFFFFFF) }
            elevation = dp(16).toFloat()
            kok.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })
        }.also { bildirim = it }
        t.text = yazi
        t.bringToFront()
        t.animate().cancel()
        t.alpha = 0f
        t.animate().alpha(1f).setDuration(220).start()
        ana.removeCallbacks(bildirimSonu)
        ana.postDelayed(bildirimSonu, 2_500)
    }

    companion object {
        private const val BUYUK_RADYO_G = 268
        /** Radyonun yüksekliği / genişliği (fotoğraftaki radyo). */
        private const val RADYO_ORAN = 0.71f
        // Sakin hâlde 7 uygulama + 2 düğme 960 dp'lik TV ekranına taşmadan sığsın diye.
        private const val KARO_G = 94
        private const val KARO_Y = 53
        private const val VIDEO_G = 232
        private const val VIDEO_Y = 130
        private const val POSTER_G = 124
        private const val POSTER_Y = 186
        private const val SAKIN_UYGULAMA = 7
        private const val DUVAR_SURESI_MS = 3 * 60 * 60_000L
        private const val BOSTA_SERGI_MS = 5 * 60_000L
        private const val KOYU_YAZI = 0xFF1A1714.toInt()

        /** Maç kartlarının sırası. */
        private val MAC_SIRASI = listOf("Beşiktaş", "Galatasaray", "Fenerbahçe")

        /** Arka planı temizlerken dokunulmayanlar: ayarlar, mağaza, Google servisleri, asistan, ekran koruyucu. */
        private val KAPATILMAZ = setOf(
            "android", "com.android.tv.settings", "com.android.systemui", "com.android.vending",
            "com.google.android.gms", "com.google.android.katniss", "com.google.android.tungsten.setupwraith",
            "com.google.android.apps.tv.dreamx",
        )
        private const val SAYFA_KARARTMA = .4f
        private const val SECIS_MS = 380L
        /** Boştayken önceden kurulan satır sayısı; altındakiler odak yaklaşınca kurulur (bellek şişmesin). */
        private const val ONDEN_KURULAN = 6
    }
}

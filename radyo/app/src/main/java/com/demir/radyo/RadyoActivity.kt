package com.demir.radyo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Radyo ekranı. Solda sabit sütun: kategoriler alt alta, en altta saat. Sağda eski usul ahşap radyo
 * (hoparlöründe çalan radyo, kadranında frekansı), yanında düğmeler; altında radyolar. Sağ taraf tek parça
 * kayar, seçili sıra hep ekranın ortasına gelir. Kumanda: Tamam çalar, basılı tutmak favoriye ekler;
 * kanal +/– ve ileri/geri tuşları radyo değiştirir. Geri ya da Ev tuşuyla çıkılınca radyo çalmaya devam eder.
 */
class RadyoActivity : Activity() {
    private lateinit var kok: FrameLayout
    private lateinit var isik: Isik
    private lateinit var ray: LinearLayout
    private lateinit var sayfa: OrtalayanKaydirma
    private lateinit var radyo: EskiRadyo
    private lateinit var dalga: Dalga
    private lateinit var durum: TextView
    private lateinit var ad: TextView
    private lateinit var sarkiYazi: TextView
    private lateinit var calDugme: ImageView
    private lateinit var favDugme: ImageView
    private lateinit var uykuDugme: ImageView
    /** Uyku açıksa kalan süre, durum yazısının yanında ("UYKU 23 DK"). */
    private lateinit var uykuYazi: TextView
    private lateinit var kategoriBaslik: TextView
    private lateinit var izgara: GridLayout
    private lateinit var bos: TextView
    private lateinit var saat: TextView
    private lateinit var tarih: TextView

    private val ana = Handler(Looper.getMainLooper())
    private var kategori = "populer"
    private var gosterilen = listOf<Istasyon>()
    private val kategoriSatirlari = HashMap<String, LinearLayout>()
    private var kadranLogosu: Logo.Sonuc? = null

    private val radyoDinleyici: () -> Unit = { guncelle() }
    private val saatAlici = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = saatYaz()
    }
    private val logoGorevi = Runnable { gorunenLogolar() }
    private var kategoriGorevi: Runnable? = null
    /** Açılışta Android ilk odağı listenin ilk satırına (Favorilerim) veriyor; o, kategoriyi değiştirmesin. */
    private var hazir = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Yazi.yukle(this)
        kok = FrameLayout(this)
        isik = Isik(this)
        kok.addView(isik, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val govde = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        govde.addView(rayKur(), LinearLayout.LayoutParams(dp(232), ViewGroup.LayoutParams.MATCH_PARENT))

        // Sağ taraf: üstte radyo ve başlık sabit; altında sadece radyolar kayar.
        val sag = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(22), dp(44), 0)
            // Kayan liste kendi alanında kalsın: radyonun üstüne çıkmasın.
            clipChildren = true; clipToPadding = false
        }
        sag.addView(sahneKur())
        kategoriBaslik = TextView(this).apply { stil(Yazi.serifItalik, 20f); setPadding(dp(2), dp(10), 0, 0) }
        sag.addView(kategoriBaslik)
        val liste = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            clipChildren = false; clipToPadding = false
        }
        izgara = GridLayout(this).apply {
            columnCount = SUTUN
            useDefaultMargins = false
            clipChildren = false; clipToPadding = false
        }
        liste.addView(izgara)
        bos = TextView(this).apply { stil(Yazi.serifItalik, 20f, Renk.SOLUK); setPadding(dp(4), dp(10), 0, 0); visibility = View.GONE }
        liste.addView(bos)
        sayfa = OrtalayanKaydirma(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            addView(liste)
            // Seçili sıra alttan tam görünsün (üstündeki sıra da tam kalır: hep iki tam sıra); aşağı inince birer sıra kayar.
            hedef = { r ->
                // Sıra adımı: kutu + yazısı + kenar payları. Kayınca üstteki sıra tam kenardan başlar, yarım yazı kalmaz.
                val sira = dp(KUTU_Y + 26 + 16)
                when {
                    r.top - dp(8) < scrollY -> r.top - dp(8)
                    r.bottom + dp(12) - height > scrollY -> (r.top - sira - dp(8)).coerceAtLeast(0)
                    else -> scrollY
                }
            }
            setOnScrollChangeListener { _, _, _, _, _ -> ana.removeCallbacks(logoGorevi); ana.postDelayed(logoGorevi, 60) }
        }
        sag.addView(sayfa, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        govde.addView(sag, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        kok.addView(govde, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(kok)

        kategori = Renk.ayarlar(this).getString("kategori", null)
            ?.takeIf { it != "favori" || Radyolar.favoriler(this).isNotEmpty() }
            ?: if (Radyolar.favoriler(this).isNotEmpty()) "favori" else "populer"
        kategoriSec(kategori, odakla = true)
        // Açılınca son dinlenen radyo kendiliğinden çalsın (zaten çalıyorsa dokunma).
        if (savedInstanceState == null && !Radyo.caliyor && !Radyo.yukleniyor) Radyo.sonIstasyon(this)?.let { Radyo.cal(this, it) }
    }

    override fun onStart() {
        super.onStart()
        Radyo.dinle(radyoDinleyici)
        guncelle()
        saatYaz()
        val f = IntentFilter().apply { addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(saatAlici, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(saatAlici, f)
    }

    override fun onStop() {
        super.onStop()
        Radyo.birak(radyoDinleyici)
        runCatching { unregisterReceiver(saatAlici) }
        dalga.oynuyor = false
    }

    // ---------------------------------------------------------------- sol sütun

    private fun rayKur(): View {
        ray = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(28), dp(18), dp(16))
            // Sütun hafifçe koyu, sağ kenarında ince bir çizgi: içerikten ayrılsın ama kutu gibi durmasın.
            background = LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x8C000000.toInt(), 0x59000000)),
                GradientDrawable().apply { setColor(0x1FFFFFFF) },
            )).apply { setLayerInset(1, dp(231), 0, 0, 0) }
        }
        val baslik = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        baslik.addView(ImageView(this).apply { setImageResource(R.drawable.ikon) }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        baslik.addView(TextView(this).apply { text = "Radyo"; stil(Yazi.serif, 32f); includeFontPadding = false })
        ray.addView(baslik)
        ray.addView(TextView(this).apply {
            text = "${Radyolar.hepsi(this@RadyoActivity).size} RADYO · TÜRKİYE"
            stil(Yazi.sansKalin, 9.5f, Renk.SOLUK, 0.18f)
            setPadding(dp(2), dp(6), 0, dp(12))
        })
        for (k in Radyolar.KATEGORILER) {
            val satir = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isFocusable = true; isClickable = true
            }
            val adYazi = TextView(this).apply {
                text = k.ad
                stil(Yazi.sans, 14.5f, golge = false)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val sayi = TextView(this).apply {
                text = Radyolar.kategori(this@RadyoActivity, k.kod).size.toString()
                stil(Yazi.sans, 11.5f, Renk.SOLUK, golge = false)
            }
            satir.addView(adYazi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            satir.addView(sayi)
            satir.tag = adYazi to sayi
            satir.setOnFocusChangeListener { _, f ->
                kategoriGorunumu(k.kod)
                if (f && hazir && k.kod != kategori) {
                    kategoriGorevi?.let { ana.removeCallbacks(it) }
                    kategoriGorevi = Runnable { kategoriSec(k.kod, odakla = false) }.also { ana.postDelayed(it, 220) }
                }
            }
            satir.setOnClickListener { calanYaDaIlk()?.requestFocus() }
            kategoriSatirlari[k.kod] = satir
            ray.addView(satir, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
            kategoriGorunumu(k.kod)
        }
        ray.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        saat = TextView(this).apply { stil(Yazi.serif, 28f); includeFontPadding = false; setPadding(dp(14), 0, 0, 0) }
        tarih = TextView(this).apply { stil(Yazi.sansKalin, 9.5f, Renk.SOLUK, 0.16f); setPadding(dp(15), dp(3), 0, 0) }
        ray.addView(saat); ray.addView(tarih)
        return ray
    }

    /** Seçili: açık dolgu, koyu yazı. Açık kategori (seçili değilken): pirinç yazı, solda pirinç çizgi. */
    private fun kategoriGorunumu(kod: String) {
        val satir = kategoriSatirlari[kod] ?: return
        val ikili = satir.tag as Pair<*, *>
        val adYazi = ikili.first as TextView
        val sayi = ikili.second as TextView
        val acik = kod == kategori
        val f = satir.isFocused
        satir.background = when {
            f -> GradientDrawable().apply { setColor(Renk.MUREKKEP); cornerRadius = dp(14).toFloat() }
            acik -> LayerDrawable(arrayOf(GradientDrawable().apply { setColor(0x1AE6C07E); cornerRadius = dp(14).toFloat() },
                GradientDrawable().apply { setColor(Renk.PIRINC); cornerRadius = dp(2).toFloat() })).apply {
                setLayerInset(1, 0, dp(10), 0, dp(10)); setLayerWidth(1, dp(3))
            }
            else -> null
        }
        adYazi.typeface = if (f || acik) Yazi.sansKalin else Yazi.sans
        adYazi.setTextColor(if (f) Renk.KOYU else if (acik) Renk.PIRINC else Renk.MUREKKEP)
        sayi.setTextColor(if (f) 0xB31A1714.toInt() else Renk.SOLUK)
    }

    // ---------------------------------------------------------------- sağ üst: radyo ve düğmeler

    private fun sahneKur(): View {
        val sahne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        radyo = EskiRadyo(this).apply {
            yazilar = EskiRadyo.Yazilar(Yazi.serif, Yazi.serifItalik, Yazi.sans, Yazi.sansKalin)
            elevation = dp(22).toFloat()
            setOnClickListener { Radyo.calDurdur(this@RadyoActivity) }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(170).start() }
        }
        sahne.addView(radyo, LinearLayout.LayoutParams(dp(270), dp(192)))

        val sag = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val durumSatiri = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dalga = Dalga(this)
        durumSatiri.addView(dalga, LinearLayout.LayoutParams(dp(16), dp(13)).apply { marginEnd = dp(9) })
        durum = TextView(this).apply { stil(Yazi.sansKalin, 11f, Renk.PIRINC, 0.2f) }
        durumSatiri.addView(durum)
        uykuYazi = TextView(this).apply { stil(Yazi.sansKalin, 11f, Renk.SOLUK, 0.2f); setPadding(dp(14), 0, 0, 0) }
        durumSatiri.addView(uykuYazi)
        sag.addView(durumSatiri)
        ad = TextView(this).apply {
            stil(Yazi.serif, 36f); includeFontPadding = false
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        sag.addView(ad)
        sarkiYazi = TextView(this).apply {
            stil(Yazi.serifItalik, 17f, Renk.MUREKKEP)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
            alpha = .85f
        }
        sag.addView(sarkiYazi)

        val dugmeler = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(14), 0, 0)
        }
        // Tek satır: önceki, çal, sonraki, favori, uyku (iki satır olunca altta radyo listesine yer kalmıyordu).
        dugmeler.addView(simgeDugme(R.drawable.ic_onceki, 44) { Radyo.sonraki(this, -1) })
        calDugme = simgeDugme(R.drawable.ic_cal, 54) { Radyo.calDurdur(this) }
        dugmeler.addView(calDugme)
        dugmeler.addView(simgeDugme(R.drawable.ic_sonraki, 44) { Radyo.sonraki(this, 1) })
        favDugme = simgeDugme(R.drawable.ic_kalp_bos, 44) { favoriDegistir(Radyo.istasyon ?: Radyo.sonIstasyon(this)) }
        dugmeler.addView(favDugme)
        uykuDugme = simgeDugme(R.drawable.ic_uyku, 44) {
            val dk = Radyo.uykuDegistir(this)
            bildir(if (dk == 0) "Uyku zamanlayıcısı kapandı" else "Radyo $dk dakika sonra yavaşça kısılıp kapanacak")
        }
        dugmeler.addView(uykuDugme)
        for (i in 1 until dugmeler.childCount) (dugmeler.getChildAt(i).layoutParams as LinearLayout.LayoutParams).marginStart = dp(if (i == 3) 22 else 10)
        sag.addView(dugmeler)
        sahne.addView(sag, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(30) })
        return sahne
    }

    /** Yuvarlak simge düğme: seçiliyken açık dolgu, koyu simge (uzaktan "neredeyim" belli olsun). */
    private fun simgeDugme(simge: Int, boyut: Int, is_: () -> Unit) = ImageView(this).apply {
        setImageResource(simge)
        imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Renk.KOYU, Renk.MUREKKEP))
        val pay = dp(boyut * .26f)
        setPadding(pay, pay, pay, pay)
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Renk.MUREKKEP) })
            addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF); setStroke(dp(1), 0x33FFFFFF) })
        }
        setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(150).start() }
        setOnClickListener { is_() }
        layoutParams = LinearLayout.LayoutParams(dp(boyut), dp(boyut))
    }

    private fun yaziDugme(is_: () -> Unit) = TextView(this).apply {
        stil(Yazi.sansKalin, 13f, golge = false)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Renk.KOYU, Renk.MUREKKEP)))
        compoundDrawableTintList = textColors
        compoundDrawablePadding = dp(7)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), 0, dp(17), 0)
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Renk.MUREKKEP); cornerRadius = dp(22).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x33FFFFFF) })
        }
        setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(150).start() }
        setOnClickListener { is_() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42))
    }

    // ---------------------------------------------------------------- kategori ve radyolar

    private fun kategoriSec(kod: String, odakla: Boolean) {
        kategori = kod
        Renk.ayarlar(this).edit().putString("kategori", kod).apply()
        for (k in kategoriSatirlari.keys) kategoriGorunumu(k)
        gosterilen = Radyolar.kategori(this, kod)
        val k = Radyolar.KATEGORILER.first { it.kod == kod }
        kategoriBaslik.text = when (kod) {
            "memleket" -> Ek.MEMLEKET_ALT
            else -> "${k.ad}  ·  ${gosterilen.size} radyo"
        }
        izgara.removeAllViews()
        val favoriler = Radyolar.favoriler(this).toSet()
        val calan = Radyo.istasyon?.id
        for (ist in gosterilen) {
            val v = IstasyonKutusu(this, dp(KUTU_G), dp(KUTU_Y)).apply {
                istasyon = ist
                favori = ist.id in favoriler
                caliyor = ist.id == calan
                setOnClickListener {
                    if (Radyo.istasyon?.id == ist.id && (Radyo.caliyor || Radyo.yukleniyor)) return@setOnClickListener
                    Radyo.cal(this@RadyoActivity, ist, gosterilen.map { it.id })
                }
                setOnLongClickListener { favoriDegistir(ist); true }
            }
            izgara.addView(v, GridLayout.LayoutParams().apply { setMargins(dp(7), dp(7), dp(7), dp(9)) })
        }
        sayfa.scrollTo(0, 0)
        bos.visibility = if (gosterilen.isEmpty()) View.VISIBLE else View.GONE
        bos.text = if (kod == "favori") "Henüz favori radyo yok.\nBir radyonun üstünde Tamam'ı basılı tut ya da ♥ düğmesine bas." else "Bu kategoride radyo yok."
        if (odakla) (calanYaDaIlk() ?: kategoriSatirlari[kod])?.let { h -> h.post { h.requestFocus(); hazir = true } }
        izgara.post { gorunenLogolar() }
    }

    private fun calanYaDaIlk(): View? =
        (0 until izgara.childCount).map { izgara.getChildAt(it) as IstasyonKutusu }.firstOrNull { it.istasyon?.id == Radyo.istasyon?.id }
            ?: izgara.getChildAt(0)

    private fun gorunenLogolar() {
        val ust = sayfa.scrollY - dp(40)
        val alt = sayfa.scrollY + sayfa.height + dp(KUTU_Y * 2)
        for (i in 0 until izgara.childCount) {
            val v = izgara.getChildAt(i) as IstasyonKutusu
            if (v.bottom > ust && v.top < alt) v.logoIste()
        }
    }

    private fun favoriDegistir(ist: Istasyon?) {
        ist ?: return
        val oldu = Radyolar.favoriDegistir(this, ist.id)
        bildir(if (oldu) "${ist.ad} favorilere eklendi" else "${ist.ad} favorilerden çıkarıldı")
        ((kategoriSatirlari["favori"]?.tag as? Pair<*, *>)?.second as? TextView)?.text = Radyolar.favoriler(this).size.toString()
        if (kategori == "favori") kategoriSec("favori", odakla = false)
        else for (i in 0 until izgara.childCount) (izgara.getChildAt(i) as IstasyonKutusu).let { if (it.istasyon?.id == ist.id) it.favori = oldu }
        guncelle()
    }

    // ---------------------------------------------------------------- çalan radyo

    private fun guncelle() {
        val calan = Radyo.istasyon
        val ist = calan ?: Radyo.sonIstasyon(this)
        val durumYazisi = when {
            Radyo.hata != null -> "YAYIN YOK"
            Radyo.yukleniyor -> "BAĞLANIYOR…"
            Radyo.caliyor -> "ŞİMDİ ÇALIYOR"
            Radyo.duraklatildi -> "DURAKLATILDI"
            ist != null -> "SON DİNLENEN"
            else -> "HOŞ GELDİN"
        }
        durum.text = durumYazisi
        dalga.oynuyor = Radyo.caliyor
        dalga.visibility = if (calan != null) View.VISIBLE else View.GONE
        ad.text = ist?.ad ?: "Bir radyo seç"
        sarkiYazi.text = when {
            Radyo.hata != null -> Radyo.hata
            Radyo.sarki.isNotEmpty() -> "♪  ${Radyo.sarki}"
            ist == null -> "Aşağıdan bir radyonun üstüne gelip Tamam'a bas"
            calan == null -> "Dinlemek için ▶ düğmesine bas"
            else -> listOfNotNull(Radyolar.frekans(ist.id)?.let { "Ankara FM ${"%.1f".format(java.util.Locale.ROOT, it)}" }, "Canlı yayın").joinToString("  ·  ")
        }

        val logoUrl = ist?.logo.orEmpty()
        kadranLogosu = if (logoUrl.isEmpty()) null else Logo.hazir(logoUrl)
        if (logoUrl.isNotEmpty() && kadranLogosu == null) Logo.iste(this, logoUrl) { s ->
            if (s != null && (Radyo.istasyon ?: Radyo.sonIstasyon(this))?.logo == logoUrl) guncelle()
        }
        isik.renk(IstasyonKutusu.isikRengi(kadranLogosu?.renk ?: ist?.renk ?: Renk.PIRINC))
        radyo.goster(ist?.ad.orEmpty(), Radyolar.frekans(ist?.id), Radyo.sarki,
            if (Radyo.caliyor || ist == null) "" else durumYazisi.lowercase(Renk.TR).replaceFirstChar { it.titlecase(Renk.TR) },
            acik = Radyo.caliyor || Radyo.yukleniyor, logo = kadranLogosu?.resim)

        calDugme.setImageResource(if (Radyo.caliyor || Radyo.yukleniyor) R.drawable.ic_durdur else R.drawable.ic_cal)
        val fav = ist != null && Radyolar.favoriMi(this, ist.id)
        favDugme.setImageResource(if (fav) R.drawable.ic_kalp else R.drawable.ic_kalp_bos)
        uykuYaz()
        for (i in 0 until izgara.childCount) (izgara.getChildAt(i) as IstasyonKutusu).let { it.caliyor = it.istasyon?.id == calan?.id }
    }

    private fun uykuYaz() {
        val kalan = Radyo.uykuBitis - System.currentTimeMillis()
        uykuYazi.text = if (Radyo.uykuBitis > 0 && kalan > 0) "·  UYKU ${(kalan + 59_999) / 60_000} DK" else ""
    }

    private fun saatYaz() {
        val simdi = LocalDateTime.now()
        saat.text = simdi.format(DateTimeFormatter.ofPattern("HH:mm"))
        tarih.text = simdi.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Renk.TR)).buyukTr()
        uykuYaz()
    }

    // ---------------------------------------------------------------- kumanda

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val medya = e.keyCode in setOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { Radyo.sonraki(this, 1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { Radyo.sonraki(this, -1); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { Radyo.calDurdur(this); return true }
                KeyEvent.KEYCODE_MENU -> { (currentFocus as? IstasyonKutusu)?.istasyon?.let { favoriDegistir(it); return true } }
            }
            val odak = currentFocus
            val raydaMi = odak?.parent === ray
            // Sağ taraftan sola çıkınca açık kategoriye (en yakın satıra değil).
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && odak != null && !raydaMi &&
                FocusFinder.getInstance().findNextFocus(kok, odak, View.FOCUS_LEFT)?.parent === ray) {
                kategoriSatirlari[kategori]?.requestFocus(); return true
            }
            // Kategorilerden sağa: çalan radyo bu listedeyse ona, yoksa ilk radyoya.
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && raydaMi) {
                kategoriGorevi?.let { ana.removeCallbacks(it); kategoriGorevi = null; it.run() }
                calanYaDaIlk()?.requestFocus(); return true
            }
            if (raydaMi && e.keyCode == KeyEvent.KEYCODE_DPAD_UP && odak === kategoriSatirlari[Radyolar.KATEGORILER.first().kod]) return true
            if (raydaMi && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && odak === kategoriSatirlari[Radyolar.KATEGORILER.last().kod]) return true
            // Izgaranın sağ kenarında bir alt sıraya atlamasın.
            if (odak is IstasyonKutusu && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val sonraki = FocusFinder.getInstance().findNextFocus(izgara, odak, View.FOCUS_RIGHT)
                if (sonraki == null || sonraki.top != odak.top) return true
            }
        }
        if (medya) return true
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- bildirim

    private var bildirim: TextView? = null
    private val bildirimSonu = Runnable { bildirim?.animate()?.alpha(0f)?.setDuration(400)?.start() }

    private fun bildir(yazi: String) {
        val t = bildirim ?: TextView(this).apply {
            stil(Yazi.sansKalin, 13f, Renk.MUREKKEP, 0.02f, golge = false)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply { setColor(0xE61A1714.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0x4DFFFFFF) }
            elevation = dp(16).toFloat()
            kok.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
        }.also { bildirim = it }
        t.text = yazi
        t.animate().cancel()
        t.alpha = 0f
        t.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        ana.removeCallbacks(bildirimSonu)
        ana.postDelayed(bildirimSonu, 2_800)
    }

    companion object {
        private const val SUTUN = 5
        private const val KUTU_G = 110
        private const val KUTU_Y = 78
    }
}

/** Seçili görünümü ekranın istenen yerine kaydıran sayfa ([hedef]: içerik koordinatında istenen kaydırma). */
class OrtalayanKaydirma(ctx: Context) : ScrollView(ctx) {
    var hedef: ((Rect) -> Int)? = null

    override fun computeScrollDeltaToGetChildRectOnScreen(r: Rect): Int {
        val h = hedef ?: return super.computeScrollDeltaToGetChildRectOnScreen(r)
        val en = ((getChildAt(0)?.height ?: 0) - height).coerceAtLeast(0)
        return h(r).coerceIn(0, en) - scrollY
    }
}

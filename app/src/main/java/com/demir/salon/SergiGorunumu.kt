package com.demir.salon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Bekleme ekranı: tablolar ve uzay fotoğrafları (sürümde varsa aile kareleri) sırayla gelir.
 * Ekrana tam oturan resim tam ekran gösterilir; oranı tutmayan tablo kesilmez, çerçeveye konup
 * kendi bulanık hâlinin önüne asılır. Köşede saat ve hava; yanma olmasın diye saat her dakika biraz kayar.
 *
 * Sergi açıldıysa kimse bakmıyor demektir: ekran neredeyse durağan. Tek hareket tablo geçişi (2,5 sn) ve
 * dakikada bir saatin kayması; arada tek kare bile çizilmez. Bulanık arka plan bir kere GPU'da hazırlanır.
 */
class SergiGorunumu(ctx: Context) : FrameLayout(ctx) {

    private inner class Pano : FrameLayout(context) {
        val arka = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val cerceve = FrameLayout(context)
        val on = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }

        init {
            addView(arka, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cerceve.addView(on, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(cerceve, LayoutParams(0, 0, Gravity.CENTER))
        }

        fun bosalt() {
            animate().cancel()
            arka.setImageDrawable(null)
            on.setImageDrawable(null)
        }
    }

    private val panolar = arrayOf(Pano(), Pano())
    private var aktif = 0
    private val saat = TextView(ctx)
    private val tarih = TextView(ctx)
    private val hava = TextView(ctx)
    /** Radyo çalıyorsa: hangi radyo, hangi şarkı (sabit yazı; Sergi durağan kalsın). */
    private val radyo = TextView(ctx)
    private val radyoDinleyici: () -> Unit = { radyoYaz() }
    private val saatKutusu = LinearLayout(ctx)
    private val plakaAd = TextView(ctx)
    private val plakaAlt = TextView(ctx)
    private val plakaKutusu = LinearLayout(ctx)
    /** Saatin arkasında yumuşak bir karanlık: açık renk gökyüzünde de saat ve hava okunsun. */
    private val saatGolgesi = View(ctx)
    /** 21:00'den sonra ekranı kısan perde. */
    private val gecePerdesi = View(ctx)
    private var liste = listOf<Eser>()
    private var sira = 0
    private var calisiyor = false

    private val sonrakiGorev = Runnable { sonraki() }
    private val saatGorevi = object : Runnable {
        override fun run() {
            saatGuncelle()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        panolar.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

        saatKutusu.orientation = LinearLayout.VERTICAL
        saatKutusu.gravity = Gravity.END
        // Degrade küçük bir bitmap olarak: tablo geçişinde her karede GPU'da degrade hesaplanmasın.
        val golge = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0x8C000000.toInt(), 0x4D000000, 0x00000000)
            gradientRadius = 64f
            setGradientCenter(.62f, .4f)
            setBounds(0, 0, 130, 75)
        }
        val golgeResmi = Bitmap.createBitmap(130, 75, Bitmap.Config.ARGB_8888).also { golge.draw(android.graphics.Canvas(it)) }
        saatGolgesi.background = android.graphics.drawable.BitmapDrawable(resources, golgeResmi).apply { isFilterBitmap = true }
        addView(saatGolgesi, LayoutParams(dp(520), dp(300), Gravity.TOP or Gravity.END))
        saatGolgesi.z = 9f
        gecePerdesi.setBackgroundColor(0xFF000000.toInt())
        gecePerdesi.alpha = 0f
        addView(gecePerdesi, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gecePerdesi.z = 5f

        saat.stil(Yazi.serif, 64f)
        saat.includeFontPadding = false
        tarih.stil(Yazi.sansKalin, 11f, Salon.MUREKKEP, 0.22f)
        hava.stil(Yazi.sans, 13f, Salon.MUREKKEP)
        hava.alpha = .88f
        // Kutu sabit genişlikte: yazılar sonradan değişince eski ölçüde kesik kalmasın.
        radyo.stil(Yazi.sans, 12.5f, Salon.PIRINC)
        radyo.ellipsize = android.text.TextUtils.TruncateAt.END
        radyo.visibility = View.GONE
        for (t in listOf(saat, tarih, hava, radyo)) {
            t.maxLines = 1
            t.gravity = Gravity.END
            saatKutusu.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        (hava.layoutParams as LinearLayout.LayoutParams).topMargin = dp(3)
        (radyo.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        addView(saatKutusu, LayoutParams(dp(320), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(36); marginEnd = dp(48)
        })

        plakaKutusu.orientation = LinearLayout.VERTICAL
        plakaAd.stil(Yazi.serifItalik, 26f)
        plakaAlt.stil(Yazi.sansKalin, 10.5f, Salon.SOLUK, 0.2f)
        plakaKutusu.addView(plakaAd)
        plakaKutusu.addView(plakaAlt)
        addView(plakaKutusu, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(40); marginStart = dp(48)
        })
        saatKutusu.z = 10f
        // Gölgeli yazılar bir kez çizilip saklansın (tablo geçişinde her karede bulanıklaştırılmasın).
        saatKutusu.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        plakaKutusu.z = 10f
        // Tablonun künyesi (ad, ressam, yıl) ekranda yazmaz.
        plakaKutusu.visibility = View.GONE
    }

    fun baslat() {
        if (calisiyor) return
        calisiyor = true
        liste = karistir(Koleksiyon.yukle(context))
        sira = Random.nextInt(liste.size.coerceAtLeast(1))
        saatGorevi.run()
        HavaDurumu.getir(context) { h ->
            hava.text = "${h.sicaklik}°  ·  ${HavaDurumu.aciklama(h.kod)}"
        }
        sonraki()
        RadyoKoprusu.dinle(radyoDinleyici)
        radyoYaz()
    }

    fun durdur() {
        calisiyor = false
        RadyoKoprusu.birak(radyoDinleyici)
        removeCallbacks(sonrakiGorev)
        removeCallbacks(saatGorevi)
        panolar.forEach { it.bosalt() }
    }

    /** Aile kareleri (varsa) araya serpiştirilsin: her 6 eserden biri. */
    private fun karistir(hepsi: List<Eser>): List<Eser> {
        val aile = hepsi.filter { it.tur == "aile" }.shuffled()
        val diger = hepsi.filter { it.tur != "aile" }.shuffled()
        if (aile.isEmpty()) return diger
        val sonuc = mutableListOf<Eser>()
        var a = 0
        diger.forEachIndexed { i, e ->
            sonuc += e
            if (i % 5 == 4) sonuc += aile[a++ % aile.size]
        }
        return sonuc
    }

    private fun radyoYaz() {
        val acik = RadyoKoprusu.gorunsun && !RadyoKoprusu.degisiyor
        radyo.text = if (!acik) "" else listOf(RadyoKoprusu.ad, RadyoKoprusu.sarki).filter { it.isNotBlank() }.joinToString("  ·  ", prefix = "♪  ")
        radyo.visibility = if (acik && RadyoKoprusu.caliyor) View.VISIBLE else View.GONE
    }

    private fun saatGuncelle() {
        val simdi = LocalDateTime.now()
        saat.text = simdi.format(DateTimeFormatter.ofPattern("HH:mm"))
        tarih.text = simdi.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Salon.TR)).buyukTr()
        val s = simdi.hour
        gecePerdesi.alpha = if (s >= 23 || s < 6) .45f else if (s >= 21) .2f else 0f
        // Yanma olmasın diye saat her dakika biraz kayar; rakam zaten değişirken, animasyonsuz (tek kare).
        saatKutusu.translationX = Random.nextInt(-dp(14), dp(14)).toFloat()
        saatKutusu.translationY = Random.nextInt(-dp(10), dp(10)).toFloat()
    }

    private fun sonraki() {
        if (!calisiyor || liste.isEmpty()) return
        val e = liste[sira++ % liste.size]
        val cerceveli = e.tur == "aile" || e.oran < 1.55f || e.oran > 2.0f
        val genislik = resources.displayMetrics.widthPixels.coerceIn(1280, 1920)
        Ag.arkada {
            val b = Koleksiyon.bitmap(context, e, genislik) ?: run { Ag.anada { sonraki() }; return@arkada }
            val bulanik = if (cerceveli) bulaniklastir(b) else null
            Ag.anada { if (calisiyor) goster(e, b, bulanik) }
        }
        // Gece daha seyrek değişsin.
        val s = LocalDateTime.now().hour
        postDelayed(sonrakiGorev, if (s >= 23 || s < 6) GECE_SURE_MS else SURE_MS)
    }

    private fun goster(e: Eser, b: Bitmap, bulanik: Bitmap?) {
        val eski = panolar[aktif]
        aktif = 1 - aktif
        val p = panolar[aktif]
        p.animate().cancel()

        if (bulanik != null) {
            p.arka.setImageBitmap(bulanik)
            p.arka.setColorFilter(0xFF5A5A5A.toInt(), PorterDuff.Mode.MULTIPLY)
            if (Build.VERSION.SDK_INT >= 31) {
                val r = dp(26).toFloat() * bulanik.width / width.coerceAtLeast(1)
                p.arka.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
            val enG = width * 0.80f
            val enY = height * 0.78f
            var olcek = min(enG / b.width, enY / b.height)
            val pay = if (e.tur == "aile") dp(10) else dp(7)
            // Yatay tablo saatin sütununa taşıyorsa çerçeve sola kayar, yine sığmazsa küçülür; tarih çerçevenin üstüne binmesin.
            val saatPayi = saatSutunu()
            val bos = (width - dp(48) - saatPayi).toFloat()
            val fg = b.width * olcek + pay * 2
            if ((width + fg) / 2f > width - saatPayi) {
                if (fg > bos) olcek *= (bos - pay * 2) / (b.width * olcek)
                p.cerceve.translationX = (dp(48) + bos / 2f) - width / 2f
            } else p.cerceve.translationX = 0f
            p.cerceve.layoutParams = LayoutParams((b.width * olcek).toInt() + pay * 2, (b.height * olcek).toInt() + pay * 2, Gravity.CENTER)
            p.cerceve.setPadding(pay, pay, pay, pay)
            p.cerceve.setBackgroundColor(if (e.tur == "aile") 0xFFF1ECE3.toInt() else 0xFF1B1612.toInt())
            p.cerceve.elevation = dp(28).toFloat()
            p.cerceve.visibility = View.VISIBLE
            p.on.setImageBitmap(b)
        } else {
            p.arka.setImageBitmap(b)
            p.arka.clearColorFilter()
            if (Build.VERSION.SDK_INT >= 31) p.arka.setRenderEffect(null)
            p.cerceve.visibility = View.GONE
            p.on.setImageDrawable(null)
        }

        p.z = 1f
        eski.z = 0f
        p.alpha = 0f
        p.animate().alpha(1f).setDuration(2500).withEndAction { if (calisiyor) eski.bosalt() }.start()

        plakaKutusu.animate().alpha(0f).setDuration(600).withEndAction {
            plakaAd.text = e.ad
            plakaAlt.text = "${e.sanatci} · ${e.yil}".buyukTr()
            plakaKutusu.animate().alpha(1f).setDuration(1500).start()
        }.start()
    }

    /** Saat sütununun sağdan kapladığı genişlik (en uzun yazı + kenar boşluğu + nefes payı). */
    private fun saatSutunu(): Int {
        val yazi = listOf(saat, tarih, hava).maxOf { it.paint.measureText(it.text.toString()) }
        return (yazi + dp(48) + dp(36)).toInt().coerceAtLeast(dp(200))
    }

    /**
     * Bulanık arka plan. API 31+: küçültülmüş kopya, GPU'da RenderEffect ile bulanıklaşır (bir kez; durağan
     * olduğu için sonuç saklanır). Eskilerde kademeli küçültüp büyütülür (tek seferde 36 piksele inince kare kare oluyordu).
     */
    private fun bulaniklastir(b: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= 31) return Bitmap.createScaledBitmap(b, 480, max(1, 480 * b.height / b.width), true)
        var k = b
        for (g in intArrayOf(480, 240, 120, 60)) k = Bitmap.createScaledBitmap(k, g, max(1, g * b.height / b.width), true)
        return Bitmap.createScaledBitmap(k, 480, max(1, 480 * b.height / b.width), true)
    }

    companion object {
        private const val SURE_MS = 40_000L
        private const val GECE_SURE_MS = 120_000L
    }
}

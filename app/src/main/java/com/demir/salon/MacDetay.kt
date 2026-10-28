package com.demir.salon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Maç kartına basınca açılan sayfa. Ana ekranla aynı dil: arkada tablo, üstünde liquid glass kartlar.
 * İlk bakışta skor/geri sayım, kanal, son 5 maç, puan durumu; aşağı inince anlar, istatistik, kadrolar.
 * Maç oynanırken dakikada bir kendiliğinden tazelenir.
 */
class MacDetay(private val ctx: Context, private val mac: Mac) {
    val kok = FrameLayout(ctx).apply { isClickable = true }
    private val kaydirma = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(dp(56), dp(34), dp(56), dp(60))
    }
    private val icerik = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val ana = Handler(Looper.getMainLooper())
    private var ozet: MacOzeti? = null
    private var kapandi = false
    /** Bu çizim veriyle ilk çizim mi (öyleyse sayfa en üstten başlar). */
    private var ilkVeri = true

    /** Açılınca odak alacak görünüm (ilk düğme). */
    var ilkOdak: View? = null
        private set

    private val tazele = object : Runnable {
        override fun run() {
            if (kapandi) return
            getir()
            val o = ozet
            // Oynanırken dakikada bir, başlamadan önce geri sayım için yarım dakikada bir.
            ana.postDelayed(this, if (o?.durum == "in") 60_000 else 30_000)
        }
    }

    init {
        // Arkada sadece bulanık tablo: ana ekrandaki kartlar, şerit görünmesin.
        kok.background = SiviCam(kok, 0f, mercekDp = 0f, enAzKoyu = .6f)
        kaydirma.addView(icerik)
        // Kartların camı kaydırınca yerini tazelesin (yeri değişmeyen cam yeniden kaydedilmez).
        kaydirma.setOnScrollChangeListener { _, _, _, _, _ -> Cam.yerleri() }
        kok.addView(kaydirma, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ciz()
        ana.postDelayed(tazele, 100)
    }

    fun kapat() { kapandi = true; ana.removeCallbacksAndMessages(null) }

    private fun getir() {
        if (mac.id.isBlank()) return
        Ag.arkada {
            val o = runCatching { MacOzeti.getir(mac.ligKodu, mac.id) }.getOrNull() ?: return@arkada
            Ag.anada { if (!kapandi) { ozet = o; ciz() } }
        }
    }

    // ---------------------------------------------------------------- çizim

    private fun ciz() {
        val odakta = kok.findFocus()
        val odakSira = (odakta?.parent as? ViewGroup)?.indexOfChild(odakta) ?: 0
        val y = if (ozet != null && ilkVeri) { ilkVeri = false; 0 } else kaydirma.scrollY
        icerik.removeAllViews()
        val o = ozet
        icerik.addView(ust(o))
        icerik.addView(bilgiSatiri(o), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14)
        })
        val dugmeler = dugmeler(o)
        icerik.addView(dugmeler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
        })
        if (o != null) {
            // Maç öncesi kadro açıklandıysa en çok merak edilen o: düğmelerin hemen altında.
            val kadroHazir = o.evKadro.isNotEmpty() && o.depKadro.isNotEmpty()
            if (o.durum == "pre" && kadroHazir) bolum(kadrolar(o), dp(22))
            val ikili = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            if (o.evForm.isNotEmpty() || o.depForm.isNotEmpty()) ikili.addView(sonBes(o), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (o.puan.isNotEmpty()) ikili.addView(puanDurumu(o), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (ikili.childCount > 0) marginStart = dp(16)
            })
            if (ikili.childCount > 0) bolum(ikili, dp(22))
            if (o.durum != "pre" && o.olaylar.isNotEmpty()) bolum(anlar(o))
            if (o.durum != "pre" && o.istatistik.isNotEmpty()) bolum(istatistik(o))
            if (o.durum != "pre" && kadroHazir) bolum(kadrolar(o))
            if (o.aralarinda.isNotEmpty()) bolum(aralarinda(o))
            if (o.durum == "post") siradakiMac()
        } else {
            bolum(yazi("Maç bilgileri getiriliyor…", Yazi.serifItalik, 18f, Salon.SOLUK).apply { gravity = Gravity.CENTER }, dp(30))
        }
        ilkOdak = dugmeler.getChildAt(odakSira.coerceAtMost(dugmeler.childCount - 1)) ?: dugmeler.getChildAt(0)
        if (odakta != null) ilkOdak?.requestFocus()
        kaydirma.post { kaydirma.scrollTo(0, y); kaydirma.post { kaydirma.scrollTo(0, y) } }
    }

    private fun bolum(v: View, ust: Int = dp(16)) {
        icerik.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ust })
    }

    private fun cam(v: View, yaricap: Int = 22): View {
        v.background = SiviCam(v, dp(yaricap).toFloat(), mercekDp = 16f, enAzKoyu = .35f)
        return v
    }

    private fun kart(baslik: String): LinearLayout {
        val k = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(18)) }
        cam(k)
        k.addView(yazi(baslik, Yazi.sansKalin, 13f, Salon.MUREKKEP, 0.04f).apply { setPadding(0, 0, 0, dp(10)) })
        return k
    }

    /** Üst kısım: iki arma, ortada skor ya da geri sayım. */
    private fun ust(o: MacOzeti?): View {
        val k = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), dp(22), dp(28), dp(22)) }
        cam(k, 28)
        fun takim(ad: String, logo: String) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(arma(logo, 88))
            addView(yazi(ad, Yazi.sansKalin, 16f, Salon.MUREKKEP).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }
        k.addView(takim(o?.ev?.ad ?: mac.ev, o?.ev?.logo ?: mac.evLogo), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val orta = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val durum = o?.durum ?: if (mac.canli) "in" else "pre"
        when (durum) {
            "pre" -> {
                val kalan = Duration.between(LocalDateTime.now(), o?.zaman ?: mac.zaman)
                orta.addView(yazi(geriSayim(kalan), Yazi.serif, 58f, Salon.MUREKKEP).apply { includeFontPadding = false; gravity = Gravity.CENTER })
                orta.addView(yazi(zamanMetni(o?.zaman ?: mac.zaman), Yazi.sansKalin, 14.5f, Salon.MUREKKEP).apply { setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER })
            }
            else -> {
                val skor = if (o != null) "${o.ev.skor}  –  ${o.dep.skor}" else mac.skor.replace("-", "  –  ")
                orta.addView(yazi(skor, Yazi.serif, 76f, Salon.MUREKKEP).apply { includeFontPadding = false; gravity = Gravity.CENTER })
                val alt = if (durum == "in") "CANLI · ${o?.dakika.orEmpty()}" else "MAÇ SONU"
                orta.addView(yazi(alt, Yazi.sansKalin, 13f, if (durum == "in") Salon.PIRINC else Salon.SOLUK, 0.18f).apply { setPadding(0, dp(6), 0, 0) })
            }
        }
        orta.addView(yazi(o?.lig?.ifBlank { null } ?: mac.lig, Yazi.sans, 13f, Salon.MUREKKEP).apply { setPadding(0, dp(4), 0, 0); gravity = Gravity.CENTER; alpha = 0.8f })
        for (i in 0 until orta.childCount) orta.getChildAt(i).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        k.addView(orta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        k.addView(takim(o?.dep?.ad ?: mac.deplasman, o?.dep?.logo ?: mac.depLogo), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return k
    }

    private fun bilgiSatiri(o: MacOzeti?): View {
        val parca = listOfNotNull(
            kanal(o?.lig ?: mac.lig)?.let { "Kanal: $it" },
            o?.stat?.ifBlank { null },
            o?.hakem?.ifBlank { null }?.let { "Hakem: $it" },
        )
        return yazi(parca.joinToString("   ·   "), Yazi.sans, 13.5f, Salon.MUREKKEP)
    }

    private fun dugmeler(o: MacOzeti?): LinearLayout {
        val s = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val durum = o?.durum ?: if (mac.canli) "in" else "pre"
        // beIN maçın kendisine link vermiyor, ana sayfası açılıyor; açılana kadar (~2 sn) düğme ne olduğunu söylesin.
        if (durum != "post") s.addView(dugme("Maçı aç") { d ->
            d.text = "beIN açılıyor…"
            d.postDelayed({ Oneriler.uygulamaAc(ctx, BEIN) }, 60)
            d.postDelayed({ d.text = "Maçı aç" }, 4_000)
        })
        if (durum == "pre") {
            val kurulu = Hatirlatici.var_(ctx, mac.id)
            s.addView(dugme(if (kurulu) "Hatırlatılacak ✓" else "Hatırlat") { _ ->
                if (Hatirlatici.var_(ctx, mac.id)) Hatirlatici.sil(ctx, mac.id) else Hatirlatici.kur(ctx, mac, kanal(mac.lig))
                ciz()
            })
        }
        if (durum == "post") s.addView(dugme("YouTube'da özeti izle") { _ -> Oneriler.ozetAc(ctx, "${o?.ev?.ad ?: mac.ev} ${o?.dep?.ad ?: mac.deplasman} maç özeti") })
        for (i in 1 until s.childCount) (s.getChildAt(i).layoutParams as LinearLayout.LayoutParams).marginStart = dp(14)
        return s
    }

    /** Seçili düğme açık renk dolgu + koyu yazı: hangisinin seçili olduğu uzaktan da belli olsun. */
    private fun dugme(ad: String, is_: (TextView) -> Unit): View {
        val d = TextView(ctx).apply {
            text = ad
            stil(Yazi.sansKalin, 14.5f, Salon.MUREKKEP, 0.02f, golge = false)
            setPadding(dp(26), dp(12), dp(26), dp(12))
            isFocusable = true; isClickable = true
            setOnClickListener { is_(this) }
        }
        val zemin = SiviCam(d, dp(24).toFloat(), mercekDp = 12f, enAzKoyu = .3f)
        val secili = GradientDrawable().apply { setColor(Salon.MUREKKEP); cornerRadius = dp(24).toFloat() }
        d.background = zemin
        d.setOnFocusChangeListener { _, f ->
            d.background = if (f) secili else zemin
            d.setTextColor(if (f) 0xFF1A1714.toInt() else Salon.MUREKKEP)
            d.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(150).start()
        }
        d.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return d
    }

    private fun sonBes(o: MacOzeti): View {
        val k = kart("Son 5 maç")
        k.addView(yazi("Soldan sağa, en yenisi sağda", Yazi.sans, 11f, Salon.SOLUK).apply { setPadding(0, 0, 0, dp(6)) }, 1)
        for ((t, f) in listOf(o.ev to o.evForm, o.dep to o.depForm)) {
            if (f.isEmpty()) continue
            val s = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            s.addView(arma(t.logo, 26))
            s.addView(yazi(t.ad, Yazi.sans, 13f, Salon.MUREKKEP).apply { setPadding(dp(10), 0, dp(10), 0); minWidth = dp(120) })
            for (x in f.take(5)) s.addView(nokta(x.sonuc), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(6) })
            k.addView(s)
        }
        return k
    }

    private fun nokta(c: Char) = TextView(ctx).apply {
        text = c.toString(); gravity = Gravity.CENTER
        stil(Yazi.sansKalin, 10.5f, 0xFFFFFFFF.toInt(), golge = false)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when (c) { 'G' -> 0xFF2E9E5B.toInt(); 'M' -> 0xFFC8433B.toInt(); else -> 0xFF7A746C.toInt() })
        }
    }

    private fun puanDurumu(o: MacOzeti): View {
        val k = kart("Puan durumu")
        k.addView(satir(listOf("#", "Takım", "O", "P"), Salon.SOLUK, kalin = true))
        for (s in o.puan) k.addView(satir(listOf("${s.sira}", s.takim, s.oynanan, s.puan), if (s.vurgu) Salon.PIRINC else Salon.MUREKKEP, kalin = s.vurgu))
        return k
    }

    private fun satir(h: List<String>, renk: Int, kalin: Boolean): View {
        val s = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
        val agirlik = listOf(0.6f, 3f, 0.7f, 0.7f)
        h.forEachIndexed { i, x ->
            s.addView(yazi(x, if (kalin) Yazi.sansKalin else Yazi.sans, 13f, renk).apply {
                gravity = if (i <= 1) Gravity.START else Gravity.END; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, agirlik[i]))
        }
        return s
    }

    /** Goller ve kartlar dakikalarıyla; ev sahibi solda, deplasman sağda. */
    private fun anlar(o: MacOzeti): View {
        val k = kart("Önemli anlar")
        for (x in o.olaylar) {
            val s = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            val isaret = when (x.tur) { "gol" -> "⚽"; "sari" -> "🟨"; else -> "🟥" }
            val metin = yazi("$isaret  ${x.oyuncu}", Yazi.sans, 14f, Salon.MUREKKEP).apply { gravity = if (x.ev) Gravity.END else Gravity.START }
            val dk = yazi(x.dakika, Yazi.sansKalin, 13f, Salon.PIRINC).apply { gravity = Gravity.CENTER }
            s.addView(if (x.ev) metin else View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            s.addView(dk, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
            s.addView(if (!x.ev) metin else View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            k.addView(s)
        }
        return k
    }

    /** Takım renklerinde karşılıklı çubuklar. */
    private fun istatistik(o: MacOzeti): View {
        val k = kart("İstatistikler")
        val evRenk = gorunur(o.ev.renk, 0xFFE8E0D4.toInt()); val depRenk = gorunur(o.dep.renk, Salon.PIRINC)
        for ((ad, a, b) in o.istatistik) {
            val yuzde = ad == "Topa sahip olma"
            fun f(v: Float) = if (yuzde) "%${v.toInt()}" else v.toInt().toString()
            val s = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            val ust = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            ust.addView(yazi(f(a), Yazi.sansKalin, 13f, Salon.MUREKKEP), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ust.addView(yazi(ad, Yazi.sans, 12.5f, Salon.SOLUK).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
            ust.addView(yazi(f(b), Yazi.sansKalin, 13f, Salon.MUREKKEP).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            s.addView(ust)
            s.addView(Cubuk(ctx, a, b, evRenk, depRenk), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(5) })
            k.addView(s)
        }
        return k
    }

    private fun kadrolar(o: MacOzeti): View {
        val k = kart("Kadrolar")
        val iki = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, kadro, sag) in listOf(Triple(o.ev, o.evKadro, false), Triple(o.dep, o.depKadro, true))) {
            val s = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            s.addView(yazi(t.ad, Yazi.sansKalin, 13.5f, Salon.PIRINC).apply { gravity = if (sag) Gravity.END else Gravity.START; setPadding(0, 0, 0, dp(6)) })
            for (p in kadro.filter { it.ilk11 }) s.addView(oyuncu(p, sag))
            val yedek = kadro.filter { !it.ilk11 }
            if (yedek.isNotEmpty()) {
                s.addView(yazi("YEDEKLER", Yazi.sansKalin, 10.5f, Salon.SOLUK, 0.16f).apply { gravity = if (sag) Gravity.END else Gravity.START; setPadding(0, dp(10), 0, dp(4)) })
                for (p in yedek.take(9)) s.addView(oyuncu(p, sag, soluk = true))
            }
            iki.addView(s, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (sag) marginStart = dp(24) })
        }
        k.addView(iki)
        if (o.degisiklikler.isNotEmpty()) {
            k.addView(yazi("OYUNCU DEĞİŞİKLİKLERİ", Yazi.sansKalin, 10.5f, Salon.SOLUK, 0.16f).apply { setPadding(0, dp(14), 0, dp(4)) })
            for (d in o.degisiklikler) k.addView(yazi("${d.dakika}  ↔  ${d.oyuncu} (${if (d.ev) o.ev.ad else o.dep.ad})", Yazi.sans, 12.5f, Salon.MUREKKEP))
        }
        return k
    }

    private fun oyuncu(p: Oyuncu, sag: Boolean, soluk: Boolean = false) =
        yazi(if (sag) "${p.ad}  ${p.forma}" else "${p.forma}  ${p.ad}", Yazi.sans, 13f, if (soluk) Salon.SOLUK else Salon.MUREKKEP).apply {
            gravity = if (sag) Gravity.END else Gravity.START; setPadding(0, dp(2), 0, dp(2)); maxLines = 1
        }

    private fun aralarinda(o: MacOzeti): View {
        val k = kart("Aralarındaki son maçlar")
        for (m in o.aralarinda) {
            val s = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
            val t = runCatching { LocalDate.parse(m.tarih).format(DateTimeFormatter.ofPattern("d MMM yyyy", Salon.TR)) }.getOrDefault(m.tarih)
            s.addView(yazi(t, Yazi.sans, 12.5f, Salon.SOLUK), LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
            s.addView(yazi(m.ev, Yazi.sans, 13.5f, Salon.MUREKKEP).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            s.addView(yazi(m.skor, Yazi.sansKalin, 13.5f, Salon.PIRINC).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
            s.addView(yazi(m.dep, Yazi.sans, 13.5f, Salon.MUREKKEP), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            k.addView(s)
        }
        return k
    }

    /** Maç bittiyse takımın bir sonraki maçı. */
    private fun siradakiMac() {
        val k = kart("${mac.takim} · sıradaki maç")
        val y = yazi("…", Yazi.sans, 14f, Salon.MUREKKEP)
        k.addView(y)
        bolum(k)
        Ag.arkada {
            val s = runCatching { Maclar.sonraki(mac.takim) }.getOrNull()
            Ag.anada { y.text = s?.let { "${it.ev} – ${it.deplasman}  ·  ${zamanMetni(it.zaman)}  ·  ${it.lig}" } ?: "Henüz açıklanmadı" }
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun yazi(s: String, tip: android.graphics.Typeface, sp: Float, renk: Int, aralik: Float = 0f) =
        TextView(ctx).apply { text = s; stil(tip, sp, renk, aralik, golge = false) }

    private fun arma(url: String, boyut: Int) = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(boyut), dp(boyut))
        scaleType = ImageView.ScaleType.FIT_CENTER
        Resim.yukle(this, url.ifBlank { null }, dp(boyut * 2))
    }

    private fun dp(v: Number) = ctx.dp(v)

    private fun geriSayim(d: Duration): String {
        if (d.isNegative) return "Başlıyor"
        val dk = d.toMinutes()
        return when {
            dk < 60 -> "$dk dk"
            dk < 24 * 60 -> "${dk / 60} sa ${dk % 60} dk"
            else -> "${dk / (24 * 60)} gün ${(dk % (24 * 60)) / 60} sa"
        }
    }

    /** Takım rengi koyu zeminde görünmüyorsa (siyah, lacivert) yedek renk. */
    private fun gorunur(c: Int, yedek: Int): Int {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
        return if (hsv[2] < 0.35f) yedek else c
    }

    companion object {
        const val BEIN = "com.digiturk.iq.mobil"

        /** Süper Lig Türkiye'de beIN SPORTS'ta; diğerlerinin kanalını bilmiyoruz, uydurmuyoruz. */
        fun kanal(lig: String): String? = if (lig == "Süper Lig") "beIN SPORTS" else null

        fun zamanMetni(z: LocalDateTime): String {
            val gun = ChronoUnit.DAYS.between(LocalDate.now(), z.toLocalDate())
            val saat = z.format(DateTimeFormatter.ofPattern("HH:mm"))
            val ad = when {
                gun == 0L -> "Bugün"
                gun == 1L -> "Yarın"
                gun in 2..6 -> z.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Salon.TR)
                else -> z.format(DateTimeFormatter.ofPattern("d MMMM", Salon.TR))
            }
            return "$ad $saat"
        }
    }
}

/** İki takımın değerini karşılıklı gösteren çubuk. */
class Cubuk(ctx: Context, private val a: Float, private val b: Float, private val solRenk: Int, private val sagRenk: Int) : View(ctx) {
    private val boya = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val toplam = (a + b).takeIf { it > 0 } ?: 1f
        val w = width.toFloat(); val h = height.toFloat(); val ara = h
        val sol = (w - ara) * (a / toplam)
        boya.color = 0x26FFFFFF
        r.set(0f, 0f, w, h); c.drawRoundRect(r, h / 2, h / 2, boya)
        boya.color = solRenk
        r.set(0f, 0f, sol, h); c.drawRoundRect(r, h / 2, h / 2, boya)
        boya.color = sagRenk
        r.set(sol + ara, 0f, w, h); c.drawRoundRect(r, h / 2, h / 2, boya)
    }
}

/** "Hatırlat": maçtan 15 dk önce köşede yazı (Ev tuşu yardımcısı gösterir). */
object Hatirlatici {
    private fun liste(ctx: Context) = runCatching { JSONArray(Salon.ayarlar(ctx).getString("hatirlat", "[]")) }.getOrDefault(JSONArray())
    private fun yaz(ctx: Context, a: JSONArray) = Salon.ayarlar(ctx).edit().putString("hatirlat", a.toString()).apply()

    fun var_(ctx: Context, id: String): Boolean { val a = liste(ctx); return (0 until a.length()).any { a.getJSONObject(it).optString("id") == id } }

    fun kur(ctx: Context, m: Mac, kanal: String?) {
        val a = liste(ctx)
        val ms = m.zaman.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        a.put(JSONObject().put("id", m.id).put("t", ms).put("b", "${m.ev} – ${m.deplasman}")
            .put("a", "15 dk sonra" + (kanal?.let { " · $it" } ?: "")))
        yaz(ctx, a)
    }

    fun sil(ctx: Context, id: String) {
        val a = liste(ctx); val y = JSONArray()
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optString("id") != id) y.put(it) }
        yaz(ctx, y)
    }

    /** Zamanı gelen hatırlatmayı (maçtan 15 dk önce) bir kez döndürür ve listeden çıkarır. */
    fun zamaniGelen(ctx: Context): Pair<String, String>? {
        val a = liste(ctx); val simdi = System.currentTimeMillis(); val y = JSONArray()
        var gelen: Pair<String, String>? = null
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val t = o.optLong("t")
            when {
                gelen == null && simdi >= t - 15 * 60_000 && simdi < t + 10 * 60_000 -> gelen = o.optString("b") to o.optString("a")
                simdi < t + 10 * 60_000 -> y.put(o)
            }
        }
        if (gelen != null || y.length() != a.length()) yaz(ctx, y)
        return gelen
    }
}

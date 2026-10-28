package com.demir.radyo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * Bir radyonun kutusu: üstte logonun kendi zemin renginde bir plaka (logo yoksa radyonun adı zarif bir
 * yazıyla), altında adı. Tek görünüm, kendisi çizer: 500 küsur radyo listede olsa da ekran ağırlaşmasın.
 * Logo ancak kutu ekrana gelince istenir ([logoIste]).
 */
class IstasyonKutusu(ctx: Context, private val plakaG: Int, private val plakaY: Int, private val adGoster: Boolean = true) : View(ctx) {
    var istasyon: Istasyon? = null
        set(v) {
            if (field?.id == v?.id) return
            field = v; logo = null; istendi = false; yaziPlakasi = null
            v?.logo?.let { Logo.hazir(it) }?.let { logo = it; istendi = true }
            invalidate()
        }
    /** Şu an çalıyor: köşede pirinç nokta. */
    var caliyor = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var favori = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Logo indi (büyük kutu: ekranın ışığı logonun rengine geçsin). */
    var logoGeldi: (() -> Unit)? = null

    private var logo: Logo.Sonuc? = null
    private var istendi = false
    private var yaziPlakasi: StaticLayout? = null

    private val yaricap = dp(14).toFloat()
    private val plaka = RectF()
    private val boya = Paint(Paint.ANTI_ALIAS_FLAG)
    private val resimBoya = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cerceve = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f).toFloat(); color = Renk.MUREKKEP }
    private val adBoya = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f).toFloat(); color = Renk.SOLUK; textAlign = Paint.Align.CENTER }
    private val m = Matrix()

    init {
        isFocusable = adGoster
        isClickable = adGoster
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, plakaG, plakaY, yaricap)
        }
        if (adGoster) setOnFocusChangeListener { _, f ->
            animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f)
                .translationZ(if (f) dp(12).toFloat() else 0f)
                .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
            invalidate()
        }
        pivotY = plakaY / 2f
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(plakaG, if (adGoster) plakaY + dp(26) else plakaY)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { pivotX = w / 2f }

    /** Kutu ekrandaysa logosunu indir. */
    fun logoIste() {
        val ist = istasyon ?: return
        if (istendi || ist.logo.isBlank()) return
        istendi = true
        Logo.iste(context, ist.logo) { s ->
            if (istasyon === ist && s != null) { logo = s; invalidate(); logoGeldi?.invoke() }
        }
    }

    /** Logonun ana rengi (hazırsa), yoksa radyonun kendi rengi. */
    val vurgu: Int get() = logo?.renk ?: istasyon?.renk ?: Renk.PIRINC

    override fun onDraw(c: Canvas) {
        val ist = istasyon ?: return
        plaka.set(0f, 0f, plakaG.toFloat(), plakaY.toFloat())
        val l = logo
        val zemin = when {
            l == null -> ist.renk
            l.zemin != null -> l.zemin
            l.koyu -> 0xFFEDE6DA.toInt()
            else -> 0xFF2A2622.toInt()
        }
        boya.shader = null
        boya.color = zemin
        c.drawRoundRect(plaka, yaricap, yaricap, boya)
        if (l != null) logoCiz(c, l.resim, if (l.zemin != null) .1f else .17f) else adCiz(c, ist)

        if (caliyor) {
            boya.color = Renk.PIRINC
            val r = dp(5).toFloat()
            c.drawCircle(plakaG - r * 2.4f, r * 2.4f, r, boya)
        }
        if (favori) {
            boya.color = 0xE6F3ECE0.toInt()
            val r = dp(3.5f).toFloat()
            c.drawCircle(r * 3.2f, r * 3.2f, r, boya)
        }
        if (isFocused) c.drawRoundRect(plaka.apply { inset(cerceve.strokeWidth / 2, cerceve.strokeWidth / 2) }, yaricap, yaricap, cerceve)

        if (adGoster) {
            adBoya.typeface = if (isFocused) Yazi.sansKalin else Yazi.sans
            adBoya.color = if (isFocused) Renk.MUREKKEP else Renk.SOLUK
            val ad = TextUtils.ellipsize(ist.ad, adBoya, plakaG.toFloat() - dp(4), TextUtils.TruncateAt.END)
            c.drawText(ad, 0, ad.length, plakaG / 2f, plakaY + dp(18f).toFloat(), adBoya)
        }
    }

    private fun logoCiz(c: Canvas, b: Bitmap, pay: Float) {
        val g = plakaG * (1 - pay * 2)
        val y = plakaY * (1 - pay * 2)
        val olcek = min(g / b.width, y / b.height)
        m.setScale(olcek, olcek)
        m.postTranslate((plakaG - b.width * olcek) / 2f, (plakaY - b.height * olcek) / 2f)
        c.save()
        c.clipRect(plaka)
        c.drawBitmap(b, m, resimBoya)
        c.restore()
    }

    /** Logosuz radyo: adı plakanın ortasında, en çok iki satır, sığacak kadar büyük. */
    private fun adCiz(c: Canvas, ist: Istasyon) {
        val yp = yaziPlakasi ?: run {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Yazi.serifItalik; color = Renk.MUREKKEP }
            val genislik = (plakaG * .84f).toInt()
            var boyut = plakaY * .34f
            var sonuc: StaticLayout
            while (true) {
                p.textSize = boyut
                sonuc = StaticLayout.Builder.obtain(ist.ad, 0, ist.ad.length, p, genislik)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, .9f).build()
                // Kelime ortadan bölünmesin ("Powertür / k Slow"): bölünüyorsa yazı küçülür.
                val bolundu = (0 until sonuc.lineCount - 1).any { i ->
                    val son = sonuc.getLineEnd(i)
                    son in 1 until ist.ad.length && ist.ad[son - 1].isLetterOrDigit() && ist.ad[son].isLetterOrDigit()
                }
                val tasiyor = bolundu || sonuc.lineCount > 2 || (0 until sonuc.lineCount).any { sonuc.getEllipsisCount(it) > 0 } ||
                    sonuc.height > plakaY * .86f
                if (!tasiyor || boyut < plakaY * .14f) break
                boyut *= .9f
            }
            sonuc.also { yaziPlakasi = it }
        }
        c.save()
        c.translate((plakaG - yp.width) / 2f, (plakaY - yp.height) / 2f)
        yp.draw(c)
        c.restore()
    }

    companion object {
        /** Renkli ışık için rengi biraz koyulaştırıp doygunlaştır (çok açık logolar ekranı ağartmasın). */
        fun isikRengi(r: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(r, hsv)
            hsv[1] = (hsv[1] * 1.1f).coerceIn(.25f, .8f)
            hsv[2] = hsv[2].coerceIn(.35f, .7f)
            return Color.HSVToColor(hsv)
        }
    }
}

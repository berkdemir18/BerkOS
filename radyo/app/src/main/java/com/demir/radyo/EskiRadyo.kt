package com.demir.radyo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Eski usul radyo: ahşap kasa, üstte sap; önde büyük metal hoparlör
 * ızgarası, altta solda ses, sağda ayar düğmesi, ortada FM/AM/SW kadranı ve kırmızı ibre, altında küçük
 * tuşlar. Çalan radyonun logosu, adı ve frekansı hoparlörün üstündeki levhada yazar.
 * Radyo değişince ibre kadranda kayar, ayar düğmesi onunla döner; sonra durağan kalır.
 *
 * Bu dosya Radyo ve BerkOS'ta aynı: birinde değişirse ötekine de kopyala (paket satırı hariç).
 */
class EskiRadyo(ctx: Context) : View(ctx) {
    class Yazilar(val serif: Typeface, val serifItalik: Typeface, val sans: Typeface, val sansKalin: Typeface)

    var yazilar: Yazilar? = null
    private var ad = ""
    private var frekans: Float? = null
    private var sarki = ""
    private var durum = ""
    private var acik = false
    private var logo: Bitmap? = null
    /** İbrenin kadrandaki yeri, 0..1 (88..108 MHz). */
    private var ibre = .5f
    private var ibreAnim: ValueAnimator? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val yol = Path()
    private val m = Matrix()

    init {
        isFocusable = true
        isClickable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** [frekans] null: FM'de yayını yok (internet radyosu), ibre adından gelen bir yerde durur. */
    fun goster(ad: String, frekans: Float?, sarki: String, durum: String, acik: Boolean, logo: Bitmap?) {
        val hedef = when {
            frekans != null -> ((frekans - 88f) / 20f).coerceIn(0f, 1f)
            ad.isNotEmpty() && durum != "KAPALI" -> (abs(ad.hashCode()) % 1000) / 1000f * .9f + .05f
            else -> ibre
        }
        val degisti = ad != this.ad
        this.ad = ad; this.frekans = frekans; this.sarki = sarki; this.durum = durum; this.acik = acik; this.logo = logo
        if (degisti && abs(hedef - ibre) > .001f && isAttachedToWindow && width > 0) {
            ibreAnim?.cancel()
            ibreAnim = ValueAnimator.ofFloat(ibre, hedef).apply {
                duration = (450 + abs(hedef - ibre) * 700).toLong()
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { ibre = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (degisti) ibre = hedef
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val g = width.toFloat(); val y = height.toFloat()
        if (g <= 0f) return
        val yaz = yazilar ?: return

        // Sap (üstte, deri) ve ayaklar
        val ustPay = y * .07f
        val altPay = y * .035f
        p.color = 0xFF2A1A12.toInt()
        r.set(g * .3f, 0f, g * .7f, ustPay * 1.6f)
        p.style = Paint.Style.STROKE; p.strokeWidth = ustPay * .55f; p.strokeCap = Paint.Cap.ROUND
        c.drawRoundRect(r.apply { inset(p.strokeWidth / 2, p.strokeWidth / 2) }, ustPay, ustPay, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        p.color = 0xFF1C120C.toInt()
        c.drawRoundRect(g * .1f, y - altPay * 1.6f, g * .22f, y, altPay * .5f, altPay * .5f, p)
        c.drawRoundRect(g * .78f, y - altPay * 1.6f, g * .9f, y, altPay * .5f, altPay * .5f, p)

        // Kasa: turuncu-kahve ahşap, kenarlara doğru koyulaşır
        val kx0 = 0f; val ky0 = ustPay; val kx1 = g; val ky1 = y - altPay
        val kh = ky1 - ky0
        val yc = kh * .14f
        r.set(kx0, ky0, kx1, ky1)
        p.shader = LinearGradient(kx0, 0f, kx1, 0f,
            intArrayOf(0xFF6E3412.toInt(), 0xFFB0602A.toInt(), 0xFFC4773D.toInt(), 0xFFAA5A26.toInt(), 0xFF6A3110.toInt()),
            floatArrayOf(0f, .18f, .5f, .82f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, yc, yc, p)
        p.shader = LinearGradient(0f, ky0, 0f, ky1, intArrayOf(0x33FFE6C0, 0x00000000, 0x40000000), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, yc, yc, p)
        p.shader = null
        damarlar(c, kx0, ky0, kx1, ky1, yc)

        // Ön yüz: koyu kahve çerçeve
        val fx0 = kx0 + kh * .075f; val fy0 = ky0 + kh * .075f; val fx1 = kx1 - kh * .075f; val fy1 = ky1 - kh * .075f
        r.set(fx0, fy0, fx1, fy1)
        p.color = 0xFF3A1C0B.toInt()
        c.drawRoundRect(r, yc * .7f, yc * .7f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = kh * .012f; p.color = 0x55E6C07E
        c.drawRoundRect(r, yc * .7f, yc * .7f, p); p.style = Paint.Style.FILL

        val fg = fx1 - fx0; val fh = fy1 - fy0
        hoparlor(c, yaz, fx0 + fg * .05f, fy0 + fh * .06f, fx1 - fg * .05f, fy0 + fh * .6f)
        altBant(c, yaz, fx0, fy0 + fh * .64f, fx1, fy1, fh)

        if (isFocused) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = kh * .022f
            p.color = 0xFFF3ECE0.toInt()
            r.set(kx0 + p.strokeWidth / 2, ky0 + p.strokeWidth / 2, kx1 - p.strokeWidth / 2, ky1 - p.strokeWidth / 2)
            c.drawRoundRect(r, yc, yc, p)
            p.style = Paint.Style.FILL
        }
    }

    /** Ağaç damarı: dikine, soluk dalgalı çizgiler (fotoğraftaki kaplama gibi; her seferinde aynı). */
    private fun damarlar(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, yc: Float) {
        c.save()
        r.set(x0, y0, x1, y1)
        yol.reset(); yol.addRoundRect(r, yc, yc, Path.Direction.CW)
        c.clipPath(yol)
        p.style = Paint.Style.STROKE
        val g = x1 - x0; val h = y1 - y0
        for (i in 0 until 22) {
            p.strokeWidth = h * (if (i % 3 == 0) .008f else .004f)
            p.color = if (i % 2 == 0) 0x24FFD9A0 else 0x2A3A1205
            yol.reset()
            val ox = x0 + g * (i + .5f) / 22f
            yol.moveTo(ox, y0)
            var yy = y0
            while (yy <= y1) { yol.lineTo(ox + sin(yy / h * 5f + i * 2.1f) * g * .012f, yy); yy += h / 30f }
            c.drawPath(yol, p)
        }
        p.style = Paint.Style.FILL
        c.restore()
    }

    /** Altın rengi metal ızgara; ortasında çalan radyonun levhası. */
    private fun hoparlor(c: Canvas, yaz: Yazilar, x0: Float, y0: Float, x1: Float, y1: Float) {
        val g = x1 - x0; val h = y1 - y0
        val k = h * .06f
        r.set(x0, y0, x1, y1)
        p.shader = LinearGradient(0f, y0, 0f, y1, 0xFFD9C29A.toInt(), 0xFFB89C6E.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, k, k, p)
        p.shader = null
        // Delikler
        p.color = 0xFF6E5634.toInt()
        val ad_ = h / 22f
        var yy = y0 + ad_ * .7f
        var sira = 0
        while (yy < y1 - ad_ * .3f) {
            var xx = x0 + ad_ * (if (sira % 2 == 0) .7f else 1.2f)
            while (xx < x1 - ad_ * .3f) { c.drawCircle(xx, yy, ad_ * .24f, p); xx += ad_ }
            yy += ad_ * .87f; sira++
        }
        // Üst kenardan içeri düşen gölge
        p.shader = LinearGradient(0f, y0, 0f, y0 + h * .25f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, k, k, p)
        p.shader = null

        // Levha: logo + ad + frekans/şarkı
        val lh = h * .62f
        val lx0 = x0 + g * .07f; val lx1 = x1 - g * .07f
        val ly0 = y0 + (h - lh) / 2f
        r.set(lx0, ly0, lx1, ly0 + lh)
        p.color = 0x55000000
        p.maskFilter = BlurMaskFilter(lh * .08f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(r.apply { offset(0f, lh * .04f) }, lh * .14f, lh * .14f, p)
        p.maskFilter = null
        r.set(lx0, ly0, lx1, ly0 + lh)
        p.color = if (acik) 0xF5F6EEDC.toInt() else 0xE6D9CDB5.toInt()
        c.drawRoundRect(r, lh * .14f, lh * .14f, p)

        val ic = lh * .14f
        var tx = lx0 + ic
        val lb = lh - ic * 2
        val l = logo
        if (l != null) {
            r.set(tx, ly0 + ic, tx + lb, ly0 + ic + lb)
            val olcek = min(lb / l.width, lb / l.height)
            m.setScale(olcek, olcek)
            m.postTranslate(tx + (lb - l.width * olcek) / 2f, ly0 + ic + (lb - l.height * olcek) / 2f)
            p.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(r, lb * .14f, lb * .14f, p)
            p.shader = BitmapShader(l, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            c.drawRoundRect(r, lb * .14f, lb * .14f, p)
            p.shader = null
            tx += lb + ic
        }
        val yaziG = lx1 - ic - tx
        val murekkep = 0xFF2B1A0E.toInt()
        val ust = when {
            durum == "KAPALI" -> "KAPALI"
            else -> listOfNotNull(frekans?.let { "FM ${"%.1f".format(java.util.Locale.ROOT, it)}" } ?: if (ad.isNotEmpty()) "İNTERNET" else null,
                durum.ifEmpty { null }).joinToString("  ·  ")
        }
        tp.textAlign = Paint.Align.LEFT
        tp.typeface = yaz.sansKalin; tp.textSize = lh * .15f; tp.letterSpacing = .12f; tp.color = 0xFF9A5E1E.toInt()
        val satir1 = ly0 + lh * (if (sarki.isEmpty()) .36f else .3f)
        c.drawText(TextUtils.ellipsize(ust, tp, yaziG, TextUtils.TruncateAt.END).toString(), tx, satir1, tp)
        tp.letterSpacing = 0f
        tp.typeface = yaz.serif; tp.textSize = lh * .34f; tp.color = murekkep
        val satir2 = satir1 + lh * .36f
        c.drawText(TextUtils.ellipsize(ad.ifEmpty { "Radyo" }, tp, yaziG, TextUtils.TruncateAt.END).toString(), tx, satir2, tp)
        if (sarki.isNotEmpty()) {
            tp.typeface = yaz.serifItalik; tp.textSize = lh * .17f; tp.color = 0xCC2B1A0E.toInt()
            c.drawText(TextUtils.ellipsize(sarki, tp, yaziG, TextUtils.TruncateAt.END).toString(), tx, satir2 + lh * .24f, tp)
        }
    }

    /** Alt bant: iki bronz düğme, ortada kadran, altında küçük tuşlar. */
    private fun altBant(c: Canvas, yaz: Yazilar, x0: Float, y0: Float, x1: Float, y1: Float, fh: Float) {
        val g = x1 - x0; val h = y1 - y0
        val dr = h * .36f
        dugme(c, x0 + g * .14f, y0 + h * .44f, dr, ses = true)
        dugme(c, x1 - g * .14f, y0 + h * .44f, dr, ses = false)
        tp.typeface = yaz.sansKalin; tp.textSize = h * .085f; tp.color = 0xAAE6C07E.toInt(); tp.textAlign = Paint.Align.CENTER; tp.letterSpacing = .15f
        c.drawText("SES", x0 + g * .14f, y1 - h * .04f, tp)
        c.drawText("AYAR", x1 - g * .14f, y1 - h * .04f, tp)
        tp.letterSpacing = 0f

        // Kadran
        val dx0 = x0 + g * .28f; val dx1 = x1 - g * .28f
        val dy0 = y0 + h * .06f; val dy1 = y0 + h * .62f
        r.set(dx0, dy0, dx1, dy1)
        p.color = 0xFF120D0A.toInt()
        c.drawRoundRect(r, h * .05f, h * .05f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = h * .015f; p.color = 0xFF8A6A3A.toInt()
        c.drawRoundRect(r, h * .05f, h * .05f, p); p.style = Paint.Style.FILL
        val dg = dx1 - dx0; val dh = dy1 - dy0
        val sx0 = dx0 + dg * .22f; val sx1 = dx1 - dg * .06f
        val altin = if (acik) 0xFFE9CF95.toInt() else 0xFF8D7A58.toInt()
        val satirlar = listOf("FM" to listOf("88", "92", "96", "100", "104", "108"), "AM" to listOf("53", "70", "90", "120", "160"), "SW" to listOf("5.9", "9.5", "12", "15", "18"))
        tp.typeface = yaz.sansKalin; tp.textSize = dh * .2f
        for ((i, s) in satirlar.withIndex()) {
            val sy = dy0 + dh * (.3f + i * .3f)
            tp.color = altin; tp.textAlign = Paint.Align.LEFT
            c.drawText(s.first, dx0 + dg * .025f, sy, tp)
            tp.textAlign = Paint.Align.CENTER
            for ((j, n) in s.second.withIndex()) c.drawText(n, sx0 + (sx1 - sx0) * j / (s.second.size - 1f), sy, tp)
        }
        // İbre
        val ix = sx0 + (sx1 - sx0) * ibre
        p.color = 0xFFE0432F.toInt(); p.strokeWidth = dg * .008f
        c.drawLine(ix, dy0 + dh * .08f, ix, dy1 - dh * .08f, p)
        // Açık lambası
        p.color = if (acik) 0xFFFFB347.toInt() else 0xFF3A2A1C.toInt()
        if (acik) { p.maskFilter = BlurMaskFilter(dh * .1f, BlurMaskFilter.Blur.NORMAL); c.drawCircle(dx1 - dg * .035f, dy0 + dh * .14f, dh * .07f, p); p.maskFilter = null }
        c.drawCircle(dx1 - dg * .035f, dy0 + dh * .14f, dh * .045f, p)

        // Tuşlar
        val tn = 7
        val ty0 = dy1 + h * .1f; val ty1 = y1 - h * .1f
        val tg = (dg - (tn - 1) * dg * .02f) / tn
        for (i in 0 until tn) {
            val tx = dx0 + i * (tg + dg * .02f)
            r.set(tx, ty0, tx + tg, ty1)
            p.shader = LinearGradient(0f, ty0, 0f, ty1, 0xFFB98B5C.toInt(), 0xFF6E4527.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, h * .03f, h * .03f, p)
            p.shader = null
        }
    }

    /** Bronz tırtıklı düğme. Ayar düğmesi ibreyle döner. */
    private fun dugme(c: Canvas, cx: Float, cy: Float, yc: Float, ses: Boolean) {
        p.color = 0x99000000.toInt()
        p.maskFilter = BlurMaskFilter(yc * .25f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx + yc * .05f, cy + yc * .12f, yc * 1.05f, p)
        p.maskFilter = null
        p.color = 0xFF4A2A18.toInt()
        for (i in 0 until 40) {
            val a = Math.toRadians(i * 9.0)
            c.drawCircle(cx + (cos(a) * yc).toFloat(), cy + (sin(a) * yc).toFloat(), yc * .07f, p)
        }
        p.shader = RadialGradient(cx - yc * .35f, cy - yc * .4f, yc * 1.5f,
            intArrayOf(0xFFE0B48C.toInt(), 0xFF9C6440.toInt(), 0xFF4E2C18.toInt()), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, yc, p)
        p.shader = RadialGradient(cx + yc * .15f, cy + yc * .2f, yc * .6f,
            intArrayOf(0xFF6A3E24.toInt(), 0xFF2E1A0E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, yc * .5f, p)
        p.shader = null
        val aci = Math.toRadians((if (ses) 40.0 else (-140 + ibre * 280).toDouble()) - 90)
        p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE; p.strokeWidth = yc * .1f
        p.color = 0xFFF0D2A8.toInt()
        c.drawLine(cx + (cos(aci) * yc * .62f).toFloat(), cy + (sin(aci) * yc * .62f).toFloat(),
            cx + (cos(aci) * yc * .9f).toFloat(), cy + (sin(aci) * yc * .9f).toFloat(), p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }
}

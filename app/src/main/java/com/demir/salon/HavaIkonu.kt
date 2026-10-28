package com.demir.salon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** İnce çizgili hava simgesi. Bulut güneşin önüne geçince güneşi gerçekten örter (silerek). */
class HavaIkonu @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var tur = HavaTuru.ACIK
        set(v) { field = v; invalidate() }
    var gece = false
        set(v) { field = v; invalidate() }

    private val cizgi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Salon.MUREKKEP
    }
    private val sil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val dolgu = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val katman = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        c.translate((width - s) / 2f, (height - s) / 2f)
        cizgi.strokeWidth = s / 17f
        when (tur) {
            HavaTuru.ACIK -> if (gece) ay(c, s * .5f, s * .5f, s * .3f) else gunes(c, s * .5f, s * .5f, s * .19f)
            HavaTuru.AZ_BULUTLU -> {
                if (gece) ay(c, s * .64f, s * .34f, s * .2f) else gunes(c, s * .64f, s * .34f, s * .13f)
                bulut(c, s * .44f, s * .62f, s * .66f)
            }
            HavaTuru.BULUTLU -> bulut(c, s * .5f, s * .56f, s * .84f)
            HavaTuru.SIS -> {
                bulut(c, s * .5f, s * .44f, s * .74f)
                c.drawLine(s * .2f, s * .8f, s * .8f, s * .8f, cizgi)
                c.drawLine(s * .3f, s * .92f, s * .7f, s * .92f, cizgi)
            }
            HavaTuru.YAGMUR -> {
                bulut(c, s * .5f, s * .42f, s * .78f)
                for (x in listOf(.34f, .5f, .66f)) c.drawLine(s * x, s * .76f, s * (x - .06f), s * .94f, cizgi)
            }
            HavaTuru.KAR -> {
                bulut(c, s * .5f, s * .42f, s * .78f)
                dolgu.color = Salon.MUREKKEP
                for ((x, y) in listOf(.34f to .8f, .5f to .9f, .66f to .8f)) c.drawCircle(s * x, s * y, s * .045f, dolgu)
            }
            HavaTuru.FIRTINA -> {
                bulut(c, s * .5f, s * .4f, s * .78f)
                dolgu.color = Salon.PIRINC
                val p = Path().apply {
                    moveTo(s * .54f, s * .66f); lineTo(s * .4f, s * .84f); lineTo(s * .5f, s * .84f)
                    lineTo(s * .44f, s * .99f); lineTo(s * .62f, s * .78f); lineTo(s * .52f, s * .78f); close()
                }
                c.drawPath(p, dolgu)
            }
        }
        c.restoreToCount(katman)
    }

    private fun gunes(c: Canvas, x: Float, y: Float, r: Float) {
        cizgi.color = Salon.PIRINC
        c.drawCircle(x, y, r, cizgi)
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i
            c.drawLine(x + (r * 1.6f * cos(a)).toFloat(), y + (r * 1.6f * sin(a)).toFloat(),
                x + (r * 2.2f * cos(a)).toFloat(), y + (r * 2.2f * sin(a)).toFloat(), cizgi)
        }
        cizgi.color = Salon.MUREKKEP
    }

    private fun ay(c: Canvas, x: Float, y: Float, r: Float) {
        val dis = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        val oyuk = Path().apply { addCircle(x + r * .5f, y - r * .35f, r * .82f, Path.Direction.CW) }
        dis.op(oyuk, Path.Op.DIFFERENCE)
        cizgi.color = Salon.PIRINC
        c.drawPath(dis, cizgi)
        cizgi.color = Salon.MUREKKEP
    }

    private fun bulut(c: Canvas, x: Float, y: Float, w: Float) {
        val p = Path().apply { addRoundRect(x - w * .44f, y, x + w * .44f, y + w * .26f, w * .13f, w * .13f, Path.Direction.CW) }
        p.op(Path().apply { addCircle(x - w * .18f, y + w * .06f, w * .2f, Path.Direction.CW) }, Path.Op.UNION)
        p.op(Path().apply { addCircle(x + w * .1f, y - w * .02f, w * .26f, Path.Direction.CW) }, Path.Op.UNION)
        c.drawPath(p, sil)
        c.drawPath(p, cizgi)
    }
}

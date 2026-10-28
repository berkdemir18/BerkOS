package com.demir.radyo

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/**
 * Ekranın arkası: koyu sıcak zemin, üstünde çalan radyonun logosunun renginde iki yumuşak ışık.
 * Radyo değişince renk 1 saniyede süzülerek geçer; geçiş bitince ekran durağan, hiç çizilmez.
 */
class Isik(ctx: Context) : View(ctx) {
    private var renk = 0xFF3A2E22.toInt()
    private val boya = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gecis: ValueAnimator? = null

    init { setBackgroundColor(0xFF0D0B0A.toInt()) }

    fun renk(yeni: Int) {
        if (yeni == renk) return
        gecis?.cancel()
        gecis = ValueAnimator.ofObject(ArgbEvaluator(), renk, yeni).apply {
            duration = 1100
            addUpdateListener { renk = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val g = width.toFloat(); val y = height.toFloat()
        if (g <= 0) return
        val r = renk and 0x00FFFFFF
        boya.shader = RadialGradient(g * .3f, y * .2f, max(g, y) * .62f,
            intArrayOf(0x8C000000.toInt() or r, 0x33000000 or r, 0x00000000 or r), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, g, y, boya)
        boya.shader = RadialGradient(g * .95f, y * 1.05f, max(g, y) * .5f,
            intArrayOf(0x40000000 or r, 0x00000000 or r), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, g, y, boya)
    }
}

/**
 * "Şimdi çalıyor" yanındaki dört çubuk. Çalarken oynar (saniyede ~24 kare, sadece bu küçük alan),
 * dururken yatık durur. Ekran görünmüyorken hiç çizilmez.
 */
class Dalga(ctx: Context) : View(ctx) {
    var oynuyor = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val boya = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Renk.PIRINC }
    private val evreler = floatArrayOf(0f, 1.7f, .8f, 2.6f)
    private val hizlar = floatArrayOf(5.1f, 7.3f, 6.2f, 4.4f)

    override fun onDraw(c: Canvas) {
        val n = 4
        val ara = width / (n * 2f - 1)
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            val oran = if (oynuyor) .3f + .7f * (.5f + .5f * sin(t * hizlar[i] + evreler[i])) * (.6f + .4f * sin(t * 1.3f + i)) else .22f
            val h = height * oran.coerceIn(.18f, 1f)
            val x = i * ara * 2
            c.drawRoundRect(x, height - h, x + ara, height.toFloat(), ara / 2, ara / 2, boya)
        }
        if (oynuyor && isShown) postInvalidateDelayed(42)
    }
}

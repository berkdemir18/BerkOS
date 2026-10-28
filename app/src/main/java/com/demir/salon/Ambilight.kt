package com.demir.salon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Ambilight: uygulama kutusunun üstüne gelince arkasında, uygulamanın kendi renginde yumuşak bir ışık yanar;
 * kumandayla gezdikçe ışık kutuyla birlikte kayar ve rengi yavaşça değişir. [kap] ışığın çizileceği,
 * kutuların arkasında duran katman (ana ekranda sayfanın arkası, tüm uygulamalarda ızgaranın arkası).
 */
class Ambilight(private val kap: FrameLayout) {
    private val isik = View(kap.context).apply { alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    /**
     * Işığın kendisi küçük bir beyaz doku (ortası dolu, kenara doğru sönen), rengi filtreyle verilir. Her karede
     * tam boy degrade çizmek bu GPU'da kare kaçırtıyordu.
     */
    private val zemin = android.graphics.drawable.BitmapDrawable(kap.resources, doku()).apply { isFilterBitmap = true }
    private var renk = Color.TRANSPARENT
    private val yer = IntArray(2)
    private val kapYer = IntArray(2)
    private val renkGecisi = android.animation.ValueAnimator().apply {
        duration = 320
        setIntValues(0, 0)
        setEvaluator(android.animation.ArgbEvaluator())
        addUpdateListener { boya(it.animatedValue as Int) }
    }
    /** Kutu ya da katman henüz ölçülmediyse: ilk yerleşimde bir kez yakılır (sürekli yeniden denenmez). */
    private var bekleyen: View? = null

    init {
        isik.background = zemin
        kap.addView(isik, 0, FrameLayout.LayoutParams(kap.dp(GENISLIK), kap.dp(YUKSEKLIK)))
        kap.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            bekleyen?.let { v -> bekleyen = null; if (v.hasFocus()) goster(v, renk) }
        }
    }

    /** [v] kutusunun arkasında [yeni] renkte ışık yak. */
    fun goster(v: View, yeni: Int) {
        if (v.width == 0 || kap.width == 0) { bekleyen = v; renk = yeni; return }
        bekleyen = null
        val eski = renk
        renk = yeni
        if (eski != yeni || isik.alpha < .05f) {
            renkGecisi.cancel()
            renkGecisi.setIntValues(if (eski == Color.TRANSPARENT) yeni else eski, yeni)
            renkGecisi.start()
        }
        val (x, y) = konum(v)
        if (isik.alpha < .05f) { isik.translationX = x; isik.translationY = y }
        isik.animate().translationX(x).translationY(y).alpha(1f).setDuration(320)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Şerit kayarken ışık kutunun arkasında kalsın: animasyonsuz, nesne üretmeden. */
    fun izle(v: View) {
        if (v.width == 0 || kap.width == 0 || isik.alpha < .05f) return
        v.getLocationInWindow(yer); kap.getLocationInWindow(kapYer)
        isik.animate().cancel()
        isik.alpha = 1f
        isik.translationX = yer[0] - kapYer[0] + v.width / 2f - kap.dp(GENISLIK) / 2f
        isik.translationY = yer[1] - kapYer[1] + v.height / 2f - kap.dp(YUKSEKLIK) / 2f
    }

    private fun konum(v: View): Pair<Float, Float> {
        v.getLocationInWindow(yer); kap.getLocationInWindow(kapYer)
        return (yer[0] - kapYer[0] + v.width / 2f - kap.dp(GENISLIK) / 2f) to (yer[1] - kapYer[1] + v.height / 2f - kap.dp(YUKSEKLIK) / 2f)
    }

    fun gizle() {
        bekleyen = null
        if (isik.alpha > 0f) isik.animate().alpha(0f).setDuration(260).start()
    }

    private fun boya(c: Int) {
        zemin.colorFilter = android.graphics.PorterDuffColorFilter(c or 0xFF000000.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        isik.scaleY = 0.7f
    }

    companion object {
        private const val GENISLIK = 300
        private const val YUKSEKLIK = 300

        /** 64x64 beyaz ışık: ortada %75, yarı yolda %24, kenarda sıfır (eski degradenin aynısı). */
        private fun doku(): Bitmap {
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                shape = GradientDrawable.RECTANGLE
                colors = intArrayOf(Color.argb(190, 255, 255, 255), Color.argb(60, 255, 255, 255), Color.argb(0, 255, 255, 255))
                gradientRadius = 64 / 2.1f
                setBounds(0, 0, 64, 64)
                draw(Canvas(b))
            }
            return b
        }

        /**
         * Uygulamanın baskın rengi: afişteki doygun piksellerin en çok geçen tonu; afiş beyaz/siyahsa
         * ortalama renk. Işık güzel görünsün diye parlaklığı yükseltilir.
         */
        fun renk(d: Drawable?): Int {
            val kopya = d?.constantState?.newDrawable()?.mutate() ?: return 0xFF8A7A66.toInt()
            val g = 48; val y = 27
            val b = Bitmap.createBitmap(g, y, Bitmap.Config.ARGB_8888)
            kopya.setBounds(0, 0, g, y)
            kopya.draw(Canvas(b))
            val kova = FloatArray(36); val hsv = FloatArray(3)
            val toplamS = FloatArray(36); val sayi = IntArray(36)
            var r = 0L; var gg = 0L; var m = 0L; var n = 0
            for (i in 0 until g) for (j in 0 until y) {
                val p = b.getPixel(i, j)
                if (Color.alpha(p) < 128) continue
                r += Color.red(p); gg += Color.green(p); m += Color.blue(p); n++
                Color.colorToHSV(p, hsv)
                if (hsv[1] < .35f || hsv[2] < .25f) continue
                val k = (hsv[0] / 10f).toInt().coerceIn(0, 35)
                kova[k] += hsv[1] * hsv[2]; toplamS[k] += hsv[1]; sayi[k]++
            }
            b.recycle()
            val en = kova.indices.maxByOrNull { kova[it] } ?: 0
            // Doygun pikseller afişin en az %6'sıysa onların tonu; değilse ortalama.
            if (sayi[en] >= g * y * 0.06f) {
                return Color.HSVToColor(floatArrayOf(en * 10f + 5f, (toplamS[en] / sayi[en]).coerceIn(.55f, 1f), .95f))
            }
            if (n == 0) return 0xFF8A7A66.toInt()
            Color.colorToHSV(Color.rgb((r / n).toInt(), (gg / n).toInt(), (m / n).toInt()), hsv)
            hsv[2] = hsv[2].coerceIn(.55f, .9f)
            return Color.HSVToColor(hsv)
        }
    }
}

/** Işık katmanı: şeridin boyunu kendi içindeki ışığa göre büyütmesin, sadece şeridin boyunu alsın. */
class IsikKatmani(ctx: android.content.Context, a: android.util.AttributeSet?) : FrameLayout(ctx, a) {
    override fun onMeasure(w: Int, h: Int) {
        if (View.MeasureSpec.getMode(w) != View.MeasureSpec.EXACTLY || View.MeasureSpec.getMode(h) != View.MeasureSpec.EXACTLY) {
            setMeasuredDimension(0, 0)
            return
        }
        super.onMeasure(w, h)
    }
}

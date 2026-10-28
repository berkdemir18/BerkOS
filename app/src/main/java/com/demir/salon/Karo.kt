package com.demir.salon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** Dock'taki ve listedeki her uygulama kutusu. Kumandayla üstüne gelince büyür, çerçevesi parlar. */
class Karo(ctx: Context, genislik: Int, yukseklik: Int, private val golgeli: Boolean = true) : FrameLayout(ctx) {
    var odaklaninca: ((Boolean) -> Unit)? = null

    /** Ambilight rengi (uygulamanın afişinden). */
    var renk: Int = 0xFFE8E0D4.toInt()

    private val cerceve = GradientDrawable().apply {
        cornerRadius = dp(13).toFloat()
        setStroke(dp(2), Salon.MUREKKEP)
    }

    /** Şeritte yer değiştirme modu: pirinç çerçeve, sağ/sol ile kayar. */
    private val tasimaCercevesi = GradientDrawable().apply {
        cornerRadius = dp(13).toFloat()
        setStroke(dp(3), Salon.PIRINC)
    }
    var tasiniyor = false
        set(d) { field = d; foreground = if (d) tasimaCercevesi else if (hasFocus() && !sade) cerceve else null }

    /** Şeritte: odak çerçeveyle değil, üstüne akan cam damlayla ([CamDamla]) gösterilir; kutu sadece hafif büyür. */
    var sade = false

    init {
        layoutParams = ViewGroup.MarginLayoutParams(genislik, yukseklik)
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(13).toFloat())
        }
        setOnFocusChangeListener { _, odak ->
            // Yarı saydam kutularda (Ayarlar, Tüm uygulamalar) gölge kutunun içinden koyu bir kare olarak görünüyordu: onlarda gölge yok.
            val olcek = if (!odak) 1f else if (sade) 1.06f else 1.12f
            animate().scaleX(olcek).scaleY(olcek)
                .translationZ(if (odak && golgeli && !sade) dp(14).toFloat() else 0f)
                .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
            foreground = if (tasiniyor) tasimaCercevesi else if (odak && !sade) cerceve else null
            if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = renk; outlineAmbientShadowColor = renk }
            odaklaninca?.invoke(odak)
        }
    }

    companion object {
        /** Uygulamanın TV afişi varsa onu, yoksa simgesini kendi renginden bir zemin üstüne koyar. */
        fun uygulama(ctx: Context, u: Uygulama, genislik: Int, yukseklik: Int): Karo {
            val k = Karo(ctx, genislik, yukseklik)
            val afis = Uygulamalar.banner(ctx, u)
            k.renk = Ambilight.renk(afis ?: Uygulamalar.ikon(ctx, u))
            if (afis != null) {
                k.addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(afis)
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                k.setBackgroundColor(0xFF1C1916.toInt())
            } else {
                val ikon = Uygulamalar.ikon(ctx, u)
                val renk = zeminRengi(ikon)
                k.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(acik(renk), renk))
                val ic = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                ic.addView(ImageView(ctx).apply { setImageDrawable(ikon) },
                    LinearLayout.LayoutParams(k.dp(30), k.dp(30)))
                ic.addView(TextView(ctx).apply {
                    text = u.ad
                    stil(Yazi.sans, 10f, golge = false)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(k.dp(8), k.dp(6), k.dp(8), 0)
                })
                k.addView(ic, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            return k
        }

        /** Dock'un sonundaki "Tüm uygulamalar" ve "Ayarlar" gibi düğmeler. */
        fun simge(ctx: Context, simge: Int, boyut: Int): Karo {
            val k = Karo(ctx, boyut, boyut, golgeli = false)
            k.outlineProvider = null
            k.background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = k.dp(13).toFloat() }
            k.addView(ImageView(ctx).apply { setImageResource(simge) },
                LayoutParams(k.dp(24), k.dp(24), Gravity.CENTER))
            return k
        }

        private fun zeminRengi(d: Drawable): Int {
            val kopya = d.constantState?.newDrawable()?.mutate() ?: return 0xFF2E2A26.toInt()
            val b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
            kopya.setBounds(0, 0, 12, 12)
            kopya.draw(Canvas(b))
            var r = 0L; var g = 0L; var m = 0L; var n = 0
            for (x in 0 until 12) for (y in 0 until 12) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                r += Color.red(p); g += Color.green(p); m += Color.blue(p); n++
            }
            if (n == 0) return 0xFF2E2A26.toInt()
            val hsv = FloatArray(3)
            Color.colorToHSV(Color.rgb((r / n).toInt(), (g / n).toInt(), (m / n).toInt()), hsv)
            hsv[1] = min(hsv[1], .5f)
            hsv[2] = .26f
            return Color.HSVToColor(hsv)
        }

        private fun acik(renk: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(renk, hsv)
            hsv[2] = .38f
            return Color.HSVToColor(hsv)
        }
    }
}

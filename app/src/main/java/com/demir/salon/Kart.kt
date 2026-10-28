package com.demir.salon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Resimleri arka planda indirir, diskte ve bellekte tutar. */
object Resim {
    private val havuz = Executors.newFixedThreadPool(4)
    private val bellek = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(k: String, b: Bitmap) = b.byteCount
    }

    /** [bitti]: resim konunca (kart altındaki yer tutucu yazıyı gizlemek için). */
    fun yukle(iv: ImageView, kaynak: String?, genislik: Int, bitti: (() -> Unit)? = null) {
        iv.tag = kaynak
        if (kaynak == null) { iv.setImageDrawable(null); return }
        bellek.get(kaynak)?.let { iv.setImageBitmap(it); bitti?.invoke(); return }
        iv.setImageDrawable(null)
        val ctx = iv.context.applicationContext
        havuz.execute {
            val b = runCatching { getir(ctx, kaynak, genislik) }.getOrNull() ?: return@execute
            bellek.put(kaynak, b)
            Ag.anada {
                if (iv.tag == kaynak) {
                    iv.alpha = 0f
                    iv.setImageBitmap(b)
                    iv.animate().alpha(1f).setDuration(220).withEndAction { bitti?.invoke() }.start()
                }
            }
        }
    }

    private fun getir(ctx: Context, kaynak: String, genislik: Int): Bitmap? {
        val dosya = if (kaynak.startsWith("http")) {
            val klasor = File(ctx.cacheDir, "resim").apply { mkdirs() }
            val f = File(klasor, Integer.toHexString(kaynak.hashCode()) + ".img")
            if (!f.exists()) Ag.dosyaya(kaynak, f)
            f
        } else File(kaynak)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(dosya.path, o)
        var orn = 1
        while (o.outWidth / (orn * 2) >= genislik) orn *= 2
        // Doğrudan GPU belleğine çöz: kart ekrana ilk geldiğinde o karede resim yüklenip takılma yapmasın.
        return BitmapFactory.decodeFile(dosya.path, BitmapFactory.Options().apply {
            inSampleSize = orn
            if (android.os.Build.VERSION.SDK_INT >= 26) inPreferredConfig = Bitmap.Config.HARDWARE
        })
    }
}

/**
 * Kartın resmi, köşeleri yuvarlak: resim bir doku gölgelendiricisiyle (BitmapShader) yuvarlak dikdörtgen olarak
 * çizilir, kırpma gerekmez. Gölgelendirici sadece resim ya da boyut değişince kurulur, her karede değil.
 * Resim olmayan çizimlerde (bazı uygulama afişleri) eski yoldan yuvarlak kırpılır.
 */
class KartResmi(ctx: Context, private val yaricap: Float) : ImageView(ctx) {
    private val boya = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = android.graphics.Matrix()
    private val kutu = RectF()
    private val yol = android.graphics.Path()
    private var sonResim: Bitmap? = null
    private var sonG = 0
    private var sonY = 0

    init { scaleType = ScaleType.CENTER_CROP }

    override fun onDraw(c: Canvas) {
        val d = drawable ?: return
        val b = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (b == null) {
            if (sonResim != null || sonG != width || sonY != height) {
                sonResim = null; sonG = width; sonY = height
                yol.reset(); kutu.set(0f, 0f, width.toFloat(), height.toFloat())
                yol.addRoundRect(kutu, yaricap, yaricap, android.graphics.Path.Direction.CW)
            }
            c.save(); c.clipPath(yol); super.onDraw(c); c.restore()
            return
        }
        if (b !== sonResim || sonG != width || sonY != height) {
            sonResim = b; sonG = width; sonY = height
            val olcek = maxOf(width / b.width.toFloat(), height / b.height.toFloat())
            m.setScale(olcek, olcek)
            m.postTranslate((width - b.width * olcek) / 2f, (height - b.height * olcek) / 2f)
            boya.shader = android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            kutu.set(0f, 0f, width.toFloat(), height.toFloat())
        }
        c.drawRoundRect(kutu, yaricap, yaricap, boya)
    }
}

/**
 * Satırdaki kart: video kapağı (yatay) ya da film/dizi afişi (dikey). Cam gibi parlak kenarı var,
 * kumandayla üstüne gelince büyüyüp öne çıkar; izlemeye devam et kartlarının altında ilerleme çizgisi olur.
 */
class Kart(ctx: Context, val oge: Oge, val g: Int, val y: Int) : FrameLayout(ctx) {
    var odaklaninca: ((Boolean) -> Unit)? = null
    /** Kartın altındaki yazılar (kutulu'da bağlanır). */
    internal var odakYazisi: ((Boolean) -> Unit)? = null
    /** Satırda: odak çerçeveyle değil, üstüne akan cam damlayla gösterilir; kart sadece hafif büyür. */
    var sade = false
    internal val odakOlcek get() = if (sade) 1.05f else ODAK_OLCEK
    private var resimKutusu: ImageView? = null
    private var yerTutucu: TextView? = null
    private var yuklendi = false

    /** Resmi indir/çöz (bir kez). Satırda görünür olunca çağrılır; ekran dışındakiler bekler. */
    fun yukle() {
        if (yuklendi) return
        yuklendi = true
        // Resim gelince alttaki başlık yazısı gizlenir: görünmediği hâlde her karede çizilmesin.
        resimKutusu?.let { Resim.yukle(it, oge.resim, g) { yerTutucu?.visibility = View.INVISIBLE } }
    }
    private val yaricap = dp(14).toFloat()
    private var odakta = false
    private val kenar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val isik = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ilerlemeBoya = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isFocusable = true
        isClickable = true
        // Köşeler kırpılarak değil, resim yuvarlak çizilerek ([KartResmi]) yuvarlanır: kart başına GPU kırpma
        // maskesi satırlara inerken kare kaçırtıyordu. Dış çizgi sadece gölge için.
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, yaricap)
        }
        background = android.graphics.drawable.GradientDrawable().apply { setColor(0x661A1714); cornerRadius = yaricap }
        // Resim gelene kadar kartta başlık yazsın, boş kutu durmasın.
        yerTutucu = TextView(ctx)
        addView(yerTutucu!!.apply {
            text = oge.baslik
            stil(Yazi.serifItalik, if (y > g) 15f else 17f, Salon.MUREKKEP, golge = false)
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val iv = KartResmi(ctx, yaricap)
        addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        resimKutusu = iv
        val afis = oge.afisPaketi?.let { afis(ctx, it) }
        if (oge.resim == null && afis != null) { iv.setImageDrawable(afis); yuklendi = true; yerTutucu?.visibility = View.INVISIBLE }
        setOnFocusChangeListener { _, odak ->
            if (odak) yukle()
            odakta = odak
            animate().scaleX(if (odak) odakOlcek else 1f).scaleY(if (odak) odakOlcek else 1f)
                .translationZ(if (odak && !sade) dp(18).toFloat() else 0f)
                .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            invalidate()
            odakYazisi?.invoke(odak)
            odaklaninca?.invoke(odak)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        isik.shader = LinearGradient(0f, 0f, w * .6f, h * .7f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        kenar.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xCCFFFFFF.toInt(), 0x22FFFFFF, 0x80FFFFFF.toInt()), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    }

    /** Resmin üstüne: ilerleme çizgisi, odaktaysa camdan yansıyan ışık ve parlak kenar. */
    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (oge.ilerleme >= 0f) {
            val pay = dp(10).toFloat(); val kal = dp(4).toFloat()
            ilerlemeBoya.color = 0x66000000
            r.set(pay, h - pay - kal, w - pay, h - pay)
            c.drawRoundRect(r, kal, kal, ilerlemeBoya)
            ilerlemeBoya.color = 0xFFFFFFFF.toInt()
            r.right = pay + (w - 2 * pay) * oge.ilerleme
            c.drawRoundRect(r, kal, kal, ilerlemeBoya)
        }
        val parla = odakta && !sade
        if (parla) { r.set(0f, 0f, w, h); c.drawRoundRect(r, yaricap, yaricap, isik) }
        kenar.strokeWidth = dp(if (parla) 2.5f else 1f).toFloat().coerceAtLeast(1.2f)
        kenar.alpha = if (parla) 255 else 110
        val y = kenar.strokeWidth / 2
        c.drawRoundRect(y, y, w - y, h - y, yaricap - y, yaricap - y, kenar)
    }

    companion object {
        /** Uygulama afişleri: her kartta paket yöneticisine sormak dönüşte ana iş parçacığını kilitliyordu. */
        private val afislar = HashMap<String, Drawable.ConstantState?>()

        fun afis(ctx: Context, paket: String): Drawable? {
            val s = afislar.getOrPut(paket) {
                runCatching { (ctx.packageManager.getApplicationBanner(paket) ?: ctx.packageManager.getApplicationIcon(paket)).constantState }.getOrNull()
            }
            return s?.newDrawable(ctx.resources)
        }

        /** Kart + altında başlık ve küçük açıklama. Odaktaki kartın yazısı parlar, büyüyen kartın altında kalmasın diye aşağı kayar. */
        fun kutulu(ctx: Context, oge: Oge, g: Int, y: Int, yaziGoster: Boolean): Pair<View, Kart> {
            val kutu = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            val k = Kart(ctx, oge, g, y)
            kutu.addView(k, LinearLayout.LayoutParams(g, y))
            if (yaziGoster) {
                val yazilar = mutableListOf<TextView>()
                kutu.addView(TextView(ctx).apply {
                    text = oge.baslik
                    stil(Yazi.sans, 12.5f, Salon.MUREKKEP, golge = false)
                    alpha = SOLUK_YAZI
                    yazilar += this
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(ctx.dp(2), ctx.dp(12), ctx.dp(2), 0)
                }, LinearLayout.LayoutParams(g, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (oge.alt.isNotBlank()) kutu.addView(TextView(ctx).apply {
                    text = oge.alt
                    stil(Yazi.sans, 11f, Salon.SOLUK, golge = false)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), 0)
                    alpha = SOLUK_YAZI
                    yazilar += this
                }, LinearLayout.LayoutParams(g, ViewGroup.LayoutParams.WRAP_CONTENT))
                k.odakYazisi = { odak ->
                    val kayma = y * (k.odakOlcek - 1f) / 2f + if (k.sade) k.dp(6) else 0
                    for ((i, t) in yazilar.withIndex()) {
                        t.animate().alpha(if (odak) 1f else SOLUK_YAZI).translationY(if (odak) kayma else 0f).setDuration(180).start()
                        if (i == 0) t.typeface = if (odak) Yazi.sansKalin else Yazi.sans
                        if (i == 1) t.setTextColor(if (odak) Salon.MUREKKEP else Salon.SOLUK)
                    }
                }
            }
            return kutu to k
        }

        private const val ODAK_OLCEK = 1.1f
        private const val SOLUK_YAZI = .72f
    }
}

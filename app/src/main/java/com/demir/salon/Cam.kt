package com.demir.salon

import android.annotation.TargetApi
import android.content.res.Resources
import android.graphics.BlendMode
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

/** Camın arkasında ne var: duvardaki tablo + üstündeki perdeler, pencere koordinatlarında. MainActivity verir. */
interface CamArkasi {
    val genislik: Int
    val yukseklik: Int
    /** Tabloyu ve perdeleri [c]'ye pencere koordinatlarıyla çizer. Sadece duvar değişince çağrılır. */
    fun ciz(c: Canvas)
    /** Şu an tablonun üstüne ayrıca binen karartma (gece perdesi), 0..1. */
    val karartma: Float
}

/**
 * Sıvı cam (iOS 26 Liquid Glass). Android'de "arkadaki görüntüyü bulanıklaştır" diye bir şey yok ama camın
 * arkası hep aynı: duvardaki tablo. Tablo duvar değişince bir kere GPU'da RenderEffect ile hafifçe bulanıklaştırılıp
 * hazır bir GPU resmine ([bulanik]) alınır; bütün camlar bunu paylaşır. (Bulanıklığı her camda canlı yaptırınca
 * HWUI her cam için baştan hesaplıyordu: iki cam aynı karede yenilenince GPU 30-50 ms gidiyordu.) Her cam resmin
 * kendi altına düşen kısmını kendi düğümüne alır, AGSL gölgelendiricisi kenarda mercek gibi büker, renkleri
 * ayrıştırır ve arkası açıksa koyulaştırır, koyuysa açar.
 *
 * Pahalı iş (kayıt + bulanıklık) sadece duvar değişince ya da cam yer değiştirince yapılır; HWUI sonucu saklar,
 * odak animasyonu, şerit kayması camı yeniden hesaplatmaz. Ekran kapalı, Sergi açık ya da başka uygulama öndeyken
 * ([dur]) hiçbir şey kaydedilmez; istenen yenileme [basla]'ya kadar bekler.
 */
object Cam {
    /** Bulanık duvar ekranın yarı çözünürlüğünde: zaten bulanık, GPU belleği dörtte bir. */
    private const val OLCEK = 0.5f
    /** Arka plan bulanıklığı. Az olsun: cam süt gibi değil, arkadaki tablo seçilsin. */
    private const val BULANIKLIK_DP = 8f

    private const val IZ_G = 48
    private const val IZ_Y = 27

    var arka: CamArkasi? = null
    private var bulanik: Bitmap? = null
    private val camlar = WeakHashMap<SiviCam, Boolean>()
    private var acik = false
    private var bekliyor = false
    private var kayitli = false

    /** Tablonun (perdelerle) ekrandaki açık/koyu haritası; camın rengini seçmek için. */
    private var izgara: FloatArray? = null

    val destekli get() = Build.VERSION.SDK_INT >= 33

    internal fun kaydet(c: SiviCam) { camlar[c] = true }

    /**
     * Ana ekran göründü: bekleyen yenileme varsa şimdi yap. Ekran arka plandayken Android GPU kaynaklarını
     * boşaltır ve bizim düğümlerin içeriğini de siler; öyleyse cam bir kez baştan kaydedilir.
     */
    fun basla() {
        acik = true
        if (bekliyor || !gecerli()) yenile()
    }

    /** Bulanık duvar kayıtlı ve içeriği silinmemiş mi. */
    internal fun gecerli() = kayitli && (!destekli || bulanik != null)

    /** Ana ekran görünmüyor (başka uygulama, Sergi, ekran kapalı): cam hiç çizilmesin, kaydedilmesin. */
    fun dur() { acik = false }

    /**
     * Duvar değişti. [tablo] ekrandaki resim, [perde] üstündeki karartma katmanının kopyası. Açık/koyu haritası
     * arka planda hesaplanır, sonra camlar bir kez yeniden kaydedilir.
     */
    fun duvar(tablo: Bitmap, perde: Drawable?, g: Int, y: Int) {
        if (g <= 0 || y <= 0) return
        Ag.arkada {
            val iz = izgaraHesapla(tablo, perde, g, y)
            Ag.anada { izgara = iz; yenile() }
        }
    }

    /** Duvarı ve bütün camları baştan kaydet (duvar, perde değişti). */
    fun yenile() {
        if (!acik) { bekliyor = true; return }
        bekliyor = false
        if (destekli) duvarKaydet()
        for (c in camlar.keys.toList()) c?.kaydet(zorla = true)
    }

    /** Sadece camların yeri değişti (sayfa kaydı): bulanık duvar aynı, her cam kendi penceresini kaydırır. */
    fun yerleri() {
        if (!acik) { bekliyor = true; return }
        for (c in camlar.keys.toList()) c?.kaydet()
    }

    @TargetApi(33)
    internal fun resim(): Bitmap? = bulanik?.takeIf { kayitli }

    private val arkaKonum = IntArray(2)
    private val arkaBoya = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * [v]'nin arkasındaki bulanık duvarı [v]'nin koordinatlarında çiz (damlanın arka planı). [karartma] 0..1:
     * satırlar açıkken tablo kararmış olduğu için damlanın arkası da o kadar koyu olsun.
     */
    fun arkaCiz(c: Canvas, v: View, karartma: Float = 0f) {
        val b = resim() ?: return
        v.getLocationInWindow(arkaKonum)
        val k = ((1f - karartma).coerceIn(0f, 1f) * 255).toInt()
        arkaBoya.colorFilter = if (k >= 255) null else android.graphics.LightingColorFilter(Color.rgb(k, k, k), 0)
        c.save()
        c.translate(-arkaKonum[0].toFloat(), -arkaKonum[1].toFloat())
        c.scale(1f / OLCEK, 1f / OLCEK)
        c.drawBitmap(b, 0f, 0f, arkaBoya)
        c.restore()
    }

    /** Tabloyu (yarım çözünürlükte) ekran dışında GPU'da bulanıklaştırıp bir GPU resmine al. Duvar değişince bir kez. */
    @TargetApi(33)
    private fun duvarKaydet() {
        val a = arka ?: return
        if (a.genislik <= 0 || a.yukseklik <= 0) return
        val g = (a.genislik * OLCEK).toInt()
        val y = (a.yukseklik * OLCEK).toInt()
        val d = RenderNode("cam-duvar")
        val r = BULANIKLIK_DP * Resources.getSystem().displayMetrics.density * OLCEK
        d.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        d.setPosition(0, 0, g, y)
        val c = d.beginRecording(g, y)
        try {
            c.scale(OLCEK, OLCEK)
            a.ciz(c)
        } finally {
            d.endRecording()
        }
        val kok = RenderNode("cam-kok").apply { setPosition(0, 0, g, y) }
        kok.beginRecording(g, y).also { it.drawRenderNode(d) }
        kok.endRecording()
        val okuyucu = android.media.ImageReader.newInstance(g, y, PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val cizici = android.graphics.HardwareRenderer()
        try {
            cizici.setSurface(okuyucu.surface)
            cizici.setContentRoot(kok)
            cizici.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val goruntu = okuyucu.acquireNextImage() ?: return
            val tampon = goruntu.hardwareBuffer ?: run { goruntu.close(); return }
            val yeni = Bitmap.wrapHardwareBuffer(tampon, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            tampon.close()
            goruntu.close()
            if (yeni == null) return
            bulanik = yeni
            kayitli = true
        } catch (e: Exception) {
            android.util.Log.w("Salon", "bulanık duvar hazırlanamadı", e)
        } finally {
            cizici.destroy()
            okuyucu.close()
        }
    }

    /** Penceredeki [x],[y],[g],[y2] dikdörtgeninin arkasındaki ortalama parlaklık, 0 (siyah) .. 1 (beyaz). */
    internal fun parlaklik(x: Int, y: Int, g: Int, yk: Int): Float {
        val iz = izgara ?: return .3f
        val a = arka ?: return .3f
        if (a.genislik <= 0 || a.yukseklik <= 0) return .3f
        val x0 = (x * IZ_G / a.genislik).coerceIn(0, IZ_G - 1)
        val x1 = ((x + g) * IZ_G / a.genislik).coerceIn(x0, IZ_G - 1)
        val y0 = (y * IZ_Y / a.yukseklik).coerceIn(0, IZ_Y - 1)
        val y1 = ((y + yk) * IZ_Y / a.yukseklik).coerceIn(y0, IZ_Y - 1)
        var t = 0f; var n = 0
        for (j in y0..y1) for (i in x0..x1) { t += iz[j * IZ_G + i]; n++ }
        return (t / max(1, n)) * (1f - a.karartma)
    }

    /** Tabloyu perdesiyle birlikte 48x27'lik bir kopyaya çizip her hücrenin parlaklığını çıkar (arka planda). */
    private fun izgaraHesapla(tablo: Bitmap, perde: Drawable?, g: Int, y: Int): FloatArray {
        val kucuk = Bitmap.createBitmap(IZ_G, IZ_Y, Bitmap.Config.ARGB_8888)
        val c = Canvas(kucuk)
        val olcek = max(IZ_G / tablo.width.toFloat(), IZ_Y / tablo.height.toFloat())
        val m = Matrix().apply {
            setScale(olcek, olcek)
            postTranslate((IZ_G - tablo.width * olcek) / 2f, (IZ_Y - tablo.height * olcek) / 2f)
        }
        c.drawBitmap(tablo, m, Paint(Paint.FILTER_BITMAP_FLAG))
        perde?.let {
            c.save()
            c.scale(IZ_G / g.toFloat(), IZ_Y / y.toFloat())
            it.setBounds(0, 0, g, y)
            it.draw(c)
            c.restore()
        }
        val p = IntArray(IZ_G * IZ_Y)
        kucuk.getPixels(p, 0, IZ_G, 0, 0, IZ_G, IZ_Y)
        kucuk.recycle()
        return FloatArray(p.size) { i ->
            val r = Color.red(p[i]) / 255f; val gg = Color.green(p[i]) / 255f; val b = Color.blue(p[i]) / 255f
            .2126f * r + .7152f * gg + .0722f * b
        }
    }
}

/*
 * Mercek, renk ayrışması ve kenar parlaması gölgelendiricileri Kyant'ın AndroidLiquidGlass kütüphanesinden
 * (github.com/Kyant0/AndroidLiquidGlass, Apache 2.0) uyarlandı; Compose yerine View/RenderNode için yazıldı.
 */
private const val SDF = """
float sdKutu(float2 p, float2 yarim, float r) {
    float2 k = abs(p) - (yarim - float2(r));
    return length(max(k, 0.0)) - r + min(max(k.x, k.y), 0.0);
}

float2 egimKutu(float2 p, float2 yarim, float r) {
    float2 k = abs(p) - (yarim - float2(r));
    if (k.x >= 0.0 || k.y >= 0.0) return sign(p) * normalize(max(k, float2(0.00001)));
    float gx = step(k.y, k.x);
    return sign(p) * float2(gx, 1.0 - gx);
}
"""

/**
 * [icerik] camın arkası. Kenardan [yukseklik] kadar içeride mercek başlar; kenara yaklaştıkça örnekleme noktası
 * dairesel bir eğriyle [miktar] kadar içe kayar (arka plan kenarda büyüyüp bükülür). [ayrisma] > 0 ise ışık
 * köşelerde kırmızıdan mora 7 renge ayrışır. Sonra canlılık ([doygunluk]), ton ([renk], a: ne kadar) ve
 * istenirse üstten içe düşen hafif gölge ([icGolge]).
 */
private const val MERCEK = """
uniform shader icerik;
uniform float2 boyut;
uniform float yaricap;
uniform float yukseklik;
uniform float miktar;
uniform float derinlik;
uniform float ayrisma;
uniform float doygunluk;
uniform float4 renk;
uniform float icGolge;
""" + SDF + """
half4 main(float2 xy) {
    float2 yarim = boyut * 0.5;
    float2 p = xy - yarim;
    float sd = sdKutu(p, yarim, yaricap);
    half3 c;
    if (-sd >= yukseklik) {
        c = icerik.eval(xy).rgb;
    } else {
        sd = min(sd, 0.0);
        float x = 1.0 - (-sd) / yukseklik;
        float d = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * -miktar;
        float gr = min(yaricap * 1.5, min(yarim.x, yarim.y));
        float2 g = normalize(egimKutu(p, yarim, gr) + derinlik * normalize(p + float2(0.0001)));
        float2 k = xy + d * g;
        if (ayrisma <= 0.0) {
            c = icerik.eval(k).rgb;
        } else {
            float2 a = d * g * (ayrisma * (p.x * p.y) / (yarim.x * yarim.y));
            half3 t = half3(0.0);
            t.r += icerik.eval(k + a).r / 3.5;
            half3 o = icerik.eval(k + a * (2.0 / 3.0)).rgb; t.r += o.r / 3.5; t.g += o.g / 7.0;
            half3 s = icerik.eval(k + a * (1.0 / 3.0)).rgb; t.r += s.r / 3.5; t.g += s.g / 3.5;
            t.g += icerik.eval(k).g / 3.5;
            half3 m = icerik.eval(k - a * (1.0 / 3.0)).rgb; t.g += m.g / 3.5; t.b += m.b / 3.0;
            t.b += icerik.eval(k - a * (2.0 / 3.0)).b / 3.0;
            half3 u = icerik.eval(k - a).rgb; t.r += u.r / 7.0; t.b += u.b / 3.0;
            c = t;
        }
    }
    half l = dot(c, half3(0.213, 0.715, 0.072));
    c = mix(half3(l), c, half(doygunluk));
    c = mix(c, half3(renk.rgb), half(renk.a));
    if (icGolge > 0.0) {
        float gy = yaricap * 0.5;
        float sg = sdKutu(p - float2(0.0, gy), yarim, yaricap);
        c *= half(1.0 - 0.18 * icGolge * smoothstep(-gy, gy * 0.5, sg));
    }
    // Şeklin dışı saydam, kenar yumuşak (damla kendi yuvarlak şeklini taşır; şerit zaten kırpılı).
    half a = half(clamp(0.5 - sdKutu(p, yarim, yaricap), 0.0, 1.0));
    return half4(c * a, a);
}
"""

/** Kenar parlaması: ışık [aci] yönünden vurur; o yöne bakan ve tam karşısındaki kenarlar parlar, yanlar söner. */
private const val PARLAMA = """
uniform float2 boyut;
uniform float yaricap;
uniform float aci;
uniform float dusus;
uniform float taban;
uniform float4 renk;
""" + SDF + """
half4 main(float2 xy) {
    float2 yarim = boyut * 0.5;
    float2 p = xy - yarim;
    float gr = min(yaricap * 1.5, min(yarim.x, yarim.y));
    float2 g = egimKutu(p, yarim, gr);
    float s = taban + (1.0 - taban) * pow(abs(dot(g, float2(cos(aci), sin(aci)))), dusus);
    float a = renk.a * s;
    return half4(half3(renk.rgb * a), half(a));
}
"""

/** Parlama boyası: gölgelendirici bir kez kurulur, boyut değişince sadece değerleri güncellenir. */
@TargetApi(33)
internal class Parlama(yogunluk: Float, kalinlik: Float, private val guc: Float, private val taban: Float) {
    val boya = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, kalinlik * yogunluk)
        blendMode = BlendMode.PLUS
    }
    private val sh: RuntimeShader? = if (Cam.destekli) RuntimeShader(PARLAMA) else null
    val cizgi = RectF()

    fun boyut(g: Float, y: Float, yaricap: Float) {
        val k = boya.strokeWidth / 2
        cizgi.set(k, k, g - k, y - k)
        val s = sh
        if (s == null) { boya.color = Color.argb((guc * 160).toInt(), 255, 255, 255); return }
        s.setFloatUniform("boyut", g, y)
        s.setFloatUniform("yaricap", yaricap)
        s.setFloatUniform("aci", (Math.PI / 4).toFloat())
        s.setFloatUniform("dusus", 1f)
        s.setFloatUniform("taban", taban)
        s.setFloatUniform("renk", 1f, 1f, 1f, guc)
        boya.shader = s
    }
}

/**
 * Bir görünümün arka planı olarak sıvı cam (şerit, menüler, maç kartları, maç sayfası). Arkası paylaşılan bulanık
 * duvar; mercek, canlılık ve ton bu camın düğümünde, köşeler gölgelendiricide yuvarlanır. [mercekDp]: kenardaki
 * bükülmenin kalınlığı (küçük kartlarda az, tam ekranda 0). [enAzKoyu]: üstünde yazı olan camlarda arka plan ne
 * kadar koyu olursa olsun en az bu kadar karartılır.
 */
@TargetApi(33)
class SiviCam(
    private val v: View,
    private val yaricap: Float,
    private val mercekDp: Float = 24f,
    private val enAzKoyu: Float = 0f,
    private val ayrisma: Float = .35f,
) : Drawable() {
    private val yogunluk = v.resources.displayMetrics.density
    private val dugum: RenderNode? = if (Cam.destekli) RenderNode("cam") else null
    private val golgelendirici: RuntimeShader? = if (Cam.destekli) RuntimeShader(MERCEK) else null
    private val konum = IntArray(2)
    private var kayitG = -1
    private var kayitY = -1
    private var sonParlaklik = -1f
    private var hazir = false
    private var sonX = Int.MIN_VALUE
    private var sonY = Int.MIN_VALUE

    private val yedek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C1A1714.toInt() }
    private val resimBoyasi = Paint(Paint.FILTER_BITMAP_FLAG)
    private val parlama = Parlama(yogunluk, 1f, .55f, .12f)

    init { Cam.kaydet(this) }

    override fun onBoundsChange(b: Rect) {
        parlama.boyut(b.width().toFloat(), b.height().toFloat(), yaricap)
        kaydet(zorla = true)
    }

    /**
     * Camın arkasını (paylaşılan bulanık duvarın bu camın altına düşen kısmı) kendi düğümüne al. Yeri değişmediyse
     * ya da ekranın dışındaysa (satırlara inince şerit) hiçbir şey yapmaz; [zorla]: duvar ya da boyut değişti.
     */
    fun kaydet(zorla: Boolean = false) {
        if (!Cam.destekli) { invalidateSelf(); return }
        val b = bounds
        val d = dugum ?: return
        val sh = golgelendirici ?: return
        val duvar = Cam.resim() ?: return
        if (b.isEmpty || !v.isAttachedToWindow) return
        v.getLocationInWindow(konum)
        val x = konum[0] + b.left
        val y = konum[1] + b.top
        val g = b.width(); val yk = b.height()
        val ekran = Cam.arka
        if (ekran != null && (y > ekran.yukseklik || y + yk < 0 || x > ekran.genislik || x + g < 0)) { sonX = Int.MIN_VALUE; return }
        if (!zorla && hazir && x == sonX && y == sonY) return
        sonX = x; sonY = y
        var etkiDegisti = false
        if (g != kayitG || yk != kayitY) {
            kayitG = g; kayitY = yk
            d.setPosition(0, 0, g, yk)
            val m = mercekDp.coerceAtMost(minOf(g, yk) / (2f * yogunluk))
            sh.setFloatUniform("boyut", g.toFloat(), yk.toFloat())
            sh.setFloatUniform("yaricap", yaricap)
            sh.setFloatUniform("yukseklik", maxOf(m, .01f) * yogunluk)
            sh.setFloatUniform("miktar", m * yogunluk)
            sh.setFloatUniform("derinlik", 0f)
            sh.setFloatUniform("ayrisma", if (m > 0f) ayrisma else 0f)
            sh.setFloatUniform("doygunluk", CANLILIK)
            sh.setFloatUniform("icGolge", 0f)
            etkiDegisti = true
        }
        val c = d.beginRecording(g, yk)
        try {
            c.translate(-x.toFloat(), -y.toFloat())
            c.scale(2f, 2f)
            c.drawBitmap(duvar, 0f, 0f, resimBoyasi)
        } finally {
            d.endRecording()
        }
        // Arkası açıksa cam koyulaşsın, koyuysa biraz açılsın; üstteki beyaz yazı her zaman okunsun.
        val l = Cam.parlaklik(x, y, g, yk)
        if (etkiDegisti || abs(l - sonParlaklik) > .02f) {
            sonParlaklik = l
            if (l > .28f || enAzKoyu > 0f) sh.setFloatUniform("renk", 0f, 0f, 0f,
                maxOf(if (l > .28f) ((l - .28f) * 1.05f + .06f).coerceAtMost(.58f) else 0f, enAzKoyu))
            else sh.setFloatUniform("renk", 1f, 1f, 1f, ((.28f - l) * .45f).coerceAtMost(.1f))
            d.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "icerik"))
        }
        hazir = true
        invalidateSelf()
    }

    /**
     * Camın arkasını (bulanık duvar, mercek ve ton olmadan) görünümün koordinatlarında çiz: damla bunu kendi
     * merceğiyle büker. Camın mercekli düğümü iki yerde çizilince HWUI merceği her karede iki kez hesaplıyordu.
     */
    fun arkaCiz(c: Canvas) = Cam.arkaCiz(c, v)

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val n = dugum
        if (n != null && hazir && !n.hasDisplayList()) {
            // GPU kaynakları boşaltılmış (ekran arka plandaydı): bu karede düz cam, hemen ardından yeniden kayıt.
            hazir = false
            v.post { if (Cam.gecerli()) kaydet(zorla = true) else Cam.yenile() }
        }
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        if (n != null && hazir && c.isHardwareAccelerated) c.drawRenderNode(n)
        else c.drawRoundRect(0f, 0f, b.width().toFloat(), b.height().toFloat(), yaricap, yaricap, yedek)
        val r = yaricap - parlama.boya.strokeWidth / 2
        c.drawRoundRect(parlama.cizgi, r, r, parlama.boya)
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Camın arkasındaki renkler daha canlı (iOS "vibrancy"). */
        private const val CANLILIK = 1.5f
    }
}

/**
 * Odaktaki kutunun üstünde duran cam damla (iOS 26 sekme çubuğundaki seçili sekme göstergesi gibi). Altındaki
 * şeridi (camı ve kutuları) mercekle büker, köşelerde renk ayrıştırır, kenarı parlar, gölgesi düşer. Odak
 * değişince yaylı bir hareketle yeni kutuya akar, hızlandıkça yatayda esner. Durunca hiç çizilmez.
 *
 * Şeridin kutusunu ([MainActivity]'de dockKutu) tamamen kaplar; [icerik] o kutunun içini (cam + ışık + kutular)
 * aynı koordinatlarda çizer.
 */
@TargetApi(33)
class CamDamla(
    ctx: android.content.Context,
    payDp: Float = 7f,
    private val yaricapDp: Float = 20f,
    private val icerik: (Canvas) -> Unit,
) : View(ctx) {
    private val yogunluk = resources.displayMetrics.density
    private val dugum: RenderNode? = if (Cam.destekli) RenderNode("damla") else null
    private val golgelendirici: RuntimeShader? = if (Cam.destekli) RuntimeShader(MERCEK) else null
    private val parlama = Parlama(yogunluk, 1.5f, .95f, .3f)
    private val pay = payDp * yogunluk
    private val yer = IntArray(2)
    private val benimYer = IntArray(2)
    private val yedek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }

    private var hedef: View? = null
    private var x = 0f; private var hiz = 0f
    private var g = 0f; private var gHiz = 0f
    private var y = 0f; private var yk = 0f
    private var gorunurluk = 0f
    private var gorunsun = false
    private var sonZaman = 0L
    private var etkiG = -1; private var etkiY = -1
    private var olcekX = 1f; private var olcekY = 1f

    private val kare = Runnable { adim() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        elevation = 6 * yogunluk
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                val cx = x + g / 2; val cy = y + yk / 2
                val yg = g * olcekX / 2; val yy = yk * olcekY / 2
                o.setRoundRect((cx - yg).toInt(), (cy - yy).toInt(), (cx + yg).toInt(), (cy + yy).toInt(), yaricap())
                o.alpha = gorunurluk * .6f
            }
        }
    }

    private fun yaricap() = yaricapDp * yogunluk

    /** Şeridin boyunu etkilemesin: sadece şeridin verdiği kesin ölçüyü alır (yoksa şerit ekran genişliğine yayılıyordu). */
    override fun onMeasure(w: Int, h: Int) {
        if (MeasureSpec.getMode(w) != MeasureSpec.EXACTLY || MeasureSpec.getMode(h) != MeasureSpec.EXACTLY) setMeasuredDimension(0, 0)
        else super.onMeasure(w, h)
    }

    /** Damla [v] kutusuna aksın (ilk görünüşte oraya doğar). */
    fun git(v: View) {
        hedef = v
        if (!gorunsun || gorunurluk < .05f) {
            hedefiOku(v) ?: return
            x = hx; g = hg; hiz = 0f; gHiz = 0f
            // Süzülerek değil, anında belirir: yarı saydam mercekli düğüm her karede baştan çiziliyordu (~90 ms GPU).
            gorunurluk = 1f
        }
        gorunsun = true
        baslat()
    }

    /** [hemen]: satırlara inildi, şerit kayıp gidiyor; sönmesini beklemeden kaybolsun (geçişte boşuna çizilmesin). */
    fun gizle(hemen: Boolean = false) {
        gorunsun = false
        if (hemen) {
            removeCallbacks(kare)
            gorunurluk = 0f; hiz = 0f; gHiz = 0f; olcekX = 1f; olcekY = 1f
            invalidateOutline(); invalidate()
            return
        }
        baslat()
    }

    private var hx = 0f; private var hg = 0f

    /** Hedef kutunun şu anki yeri (kayarken her karede): ölçeksiz boyutuna göre, ortası aynı. */
    private fun hedefiOku(v: View): Unit? {
        if (v.width == 0 || width == 0 || !v.isAttachedToWindow) return null
        v.getLocationInWindow(yer); getLocationInWindow(benimYer)
        val cx = yer[0] - benimYer[0] + v.width * v.scaleX / 2
        val cy = yer[1] - benimYer[1] + v.height * v.scaleY / 2
        hg = v.width + 2 * pay
        hx = cx - hg / 2
        yk = v.height + 2 * pay
        y = cy - yk / 2
        return Unit
    }

    private fun baslat() {
        removeCallbacks(kare)
        sonZaman = System.nanoTime()
        postOnAnimation(kare)
    }

    /** Yay: hedefe doğru çekilir, sönümlenir; hıza göre esner. Durunca kare istemeyi bırakır. */
    private fun adim() {
        val simdi = System.nanoTime()
        val dt = ((simdi - sonZaman) / 1e9f).coerceIn(0.001f, 0.05f)
        sonZaman = simdi
        // Gizlenirken (satırlara inildi) kutuyu kovalamasın: sayfa kayarken boşuna kare çizdirmesin.
        if (gorunsun) hedef?.let { hedefiOku(it) }
        val k = 420f; val c = 2f * kotlin.math.sqrt(k) * .78f
        hiz += (-(x - hx) * k - hiz * c) * dt; x += hiz * dt
        gHiz += (-(g - hg) * k - gHiz * c) * dt; g += gHiz * dt
        val n = (abs(hiz) / (hg.coerceAtLeast(1f) * 9f)).coerceIn(0f, 1f)
        olcekX = 1f + .16f * n
        olcekY = 1f - .07f * n
        val hedefGorunurluk = if (gorunsun) 1f else 0f
        gorunurluk += (hedefGorunurluk - gorunurluk) * (dt * 14f).coerceAtMost(1f)
        if (abs(gorunurluk - hedefGorunurluk) < .01f) gorunurluk = hedefGorunurluk
        invalidateOutline()
        invalidate()
        val durdu = abs(x - hx) < .5f && abs(hiz) < 5f && abs(g - hg) < .5f && abs(gHiz) < 5f && gorunurluk == hedefGorunurluk
        if (durdu) { x = hx; g = hg; hiz = 0f; gHiz = 0f; olcekX = 1f; olcekY = 1f; invalidateOutline(); invalidate() }
        else postOnAnimation(kare)
    }

    override fun onDraw(c: Canvas) {
        if (gorunurluk <= 0f || g <= 0f) return
        val gi = g.toInt().coerceAtLeast(1); val yi = yk.toInt().coerceAtLeast(1)
        c.save()
        c.scale(olcekX, olcekY, x + g / 2, y + yk / 2)
        c.translate(x, y)
        val n = dugum
        val sh = golgelendirici
        if (n != null && sh != null && c.isHardwareAccelerated) {
            if (gi != etkiG || yi != etkiY) {
                etkiG = gi; etkiY = yi
                n.setPosition(0, 0, gi, yi)
                sh.setFloatUniform("boyut", gi.toFloat(), yi.toFloat())
                sh.setFloatUniform("yaricap", yaricap())
                sh.setFloatUniform("yukseklik", 11 * yogunluk)
                sh.setFloatUniform("miktar", 6 * yogunluk)
                sh.setFloatUniform("derinlik", 1f)
                sh.setFloatUniform("ayrisma", 1f)
                sh.setFloatUniform("doygunluk", 1.15f)
                sh.setFloatUniform("renk", 1f, 1f, 1f, .08f)
                sh.setFloatUniform("icGolge", 1f)
                n.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "icerik"))
                parlama.boyut(gi.toFloat(), yi.toFloat(), yaricap())
            }
            // Damlanın içi: altında kalan şerit, damla koordinatlarında. Sadece damla çizildiğinde (hareket ederken) kaydedilir.
            val rc = n.beginRecording(gi, yi)
            try {
                rc.translate(-x, -y)
                icerik(rc)
            } finally {
                n.endRecording()
            }
            n.setAlpha(gorunurluk)
            c.drawRenderNode(n)
        } else {
            c.drawRoundRect(0f, 0f, g, yk, yaricap(), yaricap(), yedek)
        }
        parlama.boya.alpha = (gorunurluk * 255).toInt()
        val r = yaricap() - parlama.boya.strokeWidth / 2
        c.drawRoundRect(parlama.cizgi, r, r, parlama.boya)
        c.restore()
    }
}

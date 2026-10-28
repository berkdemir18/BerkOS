package com.demir.salon

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Satırdaki tek bir kart: video, dizi, film. [resim] adres ya da TV'deki dosya yolu. */
class Oge(
    val baslik: String,
    val alt: String,
    val resim: String?,
    val ac: (Context) -> Unit,
    val ilerleme: Float = -1f,
    val kimlik: String = baslik,
    /** Uzun basınca: (seçenek adı, ne yapar) */
    val secenekler: List<Pair<String, (Context) -> Unit>> = emptyList(),
    /** Resim yoksa bu uygulamanın TV afişi gösterilir. */
    val afisPaketi: String? = null,
    /** Aynı şey mi: YouTube'da video kimliği, dizi/filmde JustWatch kimliği, kendi kaydımızda kayıt anahtarı. */
    val tekil: String? = null,
)

/** [anahtar]: satırın kalıcı adı ("devam", "youtube", platform kodu); sıralama ve gizleme bununla. */
class Raf(val baslik: String, val ogeler: List<Oge>, val poster: Boolean, val paket: String? = null, var anahtar: String = "") {
    val imza get() = baslik + ogeler.joinToString { it.kimlik + it.ilerleme.toInt() }
}

/** [ulke]: kataloğun alınacağı ülke (Apple TV+ Türkiye kataloğunda yok, ABD'ninkinden Türkçe adlarla gelir). */
class Platform(val kod: String, val paket: String, val ad: String, val ek: String, val ulke: String = "TR")

/**
 * Ana ekranın alt satırları. Hiçbiri anahtar istemez:
 *  - İzlemeye devam et: [Izlenenler] (TV'de ne oynadıysa).
 *  - YouTube: izlenen kanalların son videoları, kanalların açık RSS akışından; 3 saatte bir yenilenir.
 *  - Netflix / Prime / Disney+ / HBO Max / Exxen: JustWatch'ın Türkiye kataloğu. O uygulamada izlenen
 *    dizilerin türlerine göre sıralanır, izlenen yoksa o hafta popüler olanlar. Günde bir değişir.
 */
object Oneriler {
    val PLATFORMLAR = listOf(
        Platform("nfx", "com.netflix.ninja", "Netflix", "Netflix'te"),
        Platform("prv", "com.amazon.amazonvideo.livingroom", "Prime Video", "Prime Video'da"),
        Platform("dnp", "com.disney.disneyplus", "Disney+", "Disney+'ta"),
        Platform("mxx", "com.wbd.stream", "HBO Max", "HBO Max'te"),
        Platform("exn", "com.exxen.android", "Exxen", "Exxen'de"),
        // beIN Connect'in dizi/film kataloğu TOD (uygulamada "TOD Studios" yazıyor).
        Platform("tdt", "com.digiturk.iq.mobil", "beIN Connect", "beIN Connect'te"),
        Platform("ttp", "com.turkcell.ott", "TV+", "TV+'ta"),
        Platform("pht", "com.dogusdigital.puhutv", "puhutv", "puhutv'de"),
        Platform("atp", "com.apple.atve.androidtv.appletv", "Apple TV+", "Apple TV+'ta", ulke = "US"),
    )
    private const val YT_SURE_MS = 3 * 60 * 60_000L
    private const val PLATFORM_SURE_MS = 20 * 60 * 60_000L

    /** Bütün satırlar, kullanıcının dizdiği sırayla (gizlenenler dahil; çizerken [gizli] olanlar atlanır). */
    fun raflar(ctx: Context, yuklu: Set<String>, sadeceOnbellek: Boolean): List<Raf> {
        val r = mutableListOf<Raf>()
        devamEt(ctx, sadeceOnbellek).takeIf { it.ogeler.isNotEmpty() }?.let { r += it.also { s -> s.anahtar = "devam" } }
        if (Izlenen.YOUTUBE in yuklu) runCatching { youtube(ctx, sadeceOnbellek) }.getOrNull()?.takeIf { it.ogeler.isNotEmpty() }?.let { r += it.also { s -> s.anahtar = "youtube" } }
        r += Ek.raflar(ctx, yuklu, sadeceOnbellek)
        for (p in PLATFORMLAR) {
            if (p.paket !in yuklu) continue
            runCatching { platform(ctx, p, sadeceOnbellek) }.getOrNull()?.takeIf { it.ogeler.isNotEmpty() }?.let { r += it.also { s -> s.anahtar = p.kod } }
        }
        val sira = siralama(ctx).toMutableList()
        Ek.siraDuzelt(sira)
        return tekrarlariAyikla(ctx, r.sortedBy { s -> sira.indexOf(s.anahtar).let { if (it < 0) 1000 + r.indexOf(s) else it } })
    }

    /**
     * Bir satırda gösterilen şey sonraki satırlarda bir daha çıkmasın (izlemeye devam et'teki kayıt
     * o uygulamanın kendi satırında, HBO Max'teki dizi TV+'ta). Çok azı kalan afiş satırı (aynı kataloğun kopyası) hiç çıkmaz.
     */
    private fun tekrarlariAyikla(ctx: Context, raflar: List<Raf>): List<Raf> {
        val gizli = gizli(ctx)
        val gorulen = HashSet<String>()
        val sonuc = mutableListOf<Raf>()
        for (raf in raflar) {
            if (raf.anahtar in gizli) { sonuc += raf; continue }
            val kalan = raf.ogeler.filter { o -> o.tekil == null || o.tekil !in gorulen }
            raf.ogeler.mapNotNullTo(gorulen) { it.tekil }
            if (kalan.size == raf.ogeler.size) { sonuc += raf; continue }
            if (kalan.isEmpty() || (raf.poster && kalan.size < 6)) continue
            sonuc += Raf(raf.baslik, kalan, raf.poster, raf.paket, raf.anahtar)
        }
        return sonuc
    }

    fun siralama(ctx: Context): List<String> =
        Salon.ayarlar(ctx).getString("raf_sira", "")!!.split(',').filter { it.isNotBlank() }

    fun siralamaKaydet(ctx: Context, anahtarlar: List<String>) =
        Salon.ayarlar(ctx).edit().putString("raf_sira", anahtarlar.joinToString(",")).apply()

    fun gizli(ctx: Context): Set<String> = Salon.ayarlar(ctx).getStringSet("raf_gizli", emptySet())!!

    fun gizliKaydet(ctx: Context, s: Set<String>) = Salon.ayarlar(ctx).edit().putStringSet("raf_gizli", s).apply()

    // ---------------------------------------------------------------- izlemeye devam et

    private fun devamEt(ctx: Context, sadeceOnbellek: Boolean): Raf {
        val (gtv, gtvZaman) = GoogleTv.kartlar(ctx)
        val kendi = Izlenenler.devamEt(ctx).take(20)
        val gtvPaketleri = gtv.mapNotNull { paketBul(ctx, it.uygulama) }.toSet()
        // Google TV'nin kartıyla aynı şeyse bizim kaydımız geçer: onda kaldığı dakika var.
        val eslesen = HashMap<Int, Izlenen>()
        val kalan = mutableListOf<Izlenen>()
        for (x in kendi) {
            if (x.genel && x.paket in gtvPaketleri) continue
            // YouTube başlığı değiştirebiliyor ("ŞİDDETLİ YAĞMURDA..." / "SAĞANAKTA KAMP..."): önce video kimliğiyle eşle.
            val i = gtv.indexOfFirst { k ->
                paketBul(ctx, k.uygulama) == x.paket &&
                    (ayni(k.baslik, x.baslik) || ayni(k.baslik, x.alt) || (x.videoId != null && gtvVideo(ctx, k) == x.videoId))
            }
            if (i >= 0 && i !in eslesen) eslesen[i] = x else kalan += x
        }
        val ogeler = mutableListOf<Oge>()
        // Google TV satırı okunduktan sonra izlenenler başa.
        kalan.filter { it.son > gtvZaman }.forEach { ogeler += kendiOge(ctx, it) }
        gtv.forEachIndexed { i, k -> ogeler += eslesen[i]?.let { kendiOge(ctx, it) } ?: gtvOge(ctx, k, sadeceOnbellek) ?: return@forEachIndexed }
        kalan.filter { it.son <= gtvZaman }.forEach { ogeler += kendiOge(ctx, it) }
        return Raf("İzlemeye devam et", ogeler.distinctBy { it.tekil ?: it.kimlik }.take(16), poster = false)
    }

    /** Google TV kartındaki YouTube videosunun daha önce bulunmuş kimliği. */
    private fun gtvVideo(ctx: Context, k: GtvKart): String? =
        Salon.ayarlar(ctx).getString("gtvyt_${k.baslik}", null)?.ifBlank { null }

    private fun ayni(a: String, b: String): Boolean {
        fun n(s: String) = s.lowercase(Salon.TR).filter { it.isLetterOrDigit() }.take(22)
        val x = n(a); val y = n(b)
        return x.length >= 3 && x == y
    }

    /** Google TV'nin kartındaki uygulama adından paket. */
    private fun paketBul(ctx: Context, ad: String): String? {
        PLATFORMLAR.firstOrNull { it.ad.equals(ad, true) }?.let { return it.paket }
        if (ad.equals("YouTube", true)) return Izlenen.YOUTUBE
        return uygulamaAdlari(ctx)[ad.lowercase(Salon.TR)]
    }

    @Volatile private var adOnbellek: Map<String, String>? = null
    private fun uygulamaAdlari(ctx: Context): Map<String, String> = adOnbellek ?: run {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .associate { it.loadLabel(pm).toString().trim().lowercase(Salon.TR) to it.activityInfo.packageName }
            .also { adOnbellek = it }
    }

    private fun gtvOge(ctx: Context, k: GtvKart, sadeceOnbellek: Boolean): Oge? {
        val paket = paketBul(ctx, k.uygulama) ?: return null
        val sil = listOf<Pair<String, (Context) -> Unit>>("Bu kartı gizle" to { c ->
            val p = Salon.ayarlar(c)
            p.edit().putStringSet("gtv_gizli", p.getStringSet("gtv_gizli", emptySet())!! + k.baslik).apply()
        })
        if (k.baslik in Salon.ayarlar(ctx).getStringSet("gtv_gizli", emptySet())!!) return null
        if (paket == Izlenen.YOUTUBE) {
            val p = Salon.ayarlar(ctx)
            val anahtar = "gtvyt_${k.baslik}"
            var id = p.getString(anahtar, null)
            if (id == null && !sadeceOnbellek) {
                id = runCatching { videoBul("${k.baslik} ${k.ayrinti}") }.getOrNull() ?: ""
                p.edit().putString(anahtar, id).apply()
            }
            return Oge(
                baslik = k.baslik, alt = "${k.ayrinti} · YouTube",
                resim = id?.ifBlank { null }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                ac = { c -> if (!id.isNullOrBlank()) youtubeAc(c, id, 0) else uygulamaAc(c, paket) },
                kimlik = "gtv|${k.baslik}|${id.orEmpty()}", secenekler = sil,
                tekil = id?.ifBlank { null }?.let { "yt:$it" },
            )
        }
        val kod = PLATFORMLAR.firstOrNull { it.paket == paket }?.kod
        val j = jwAra(ctx, k.baslik, kod, getirilsin = !sadeceOnbellek)
        val url = j?.optString("url")?.ifBlank { null }
        return Oge(
            baslik = k.baslik, alt = listOf(k.uygulama, k.ayrinti).filter { it.isNotBlank() }.joinToString(" · "),
            resim = j?.optString("arka")?.ifBlank { null } ?: j?.optString("poster")?.ifBlank { null },
            ac = { c -> if (url == null || !linkAc(c, url, paket)) uygulamaAc(c, paket) },
            kimlik = "gtv|${k.baslik}|${j?.optString("arka").orEmpty()}", secenekler = sil,
            afisPaketi = paket,
            tekil = j?.optString("id")?.ifBlank { null }?.let { "jw:$it" },
        )
    }

    private fun kendiOge(ctx: Context, x: Izlenen): Oge {
        val pm = ctx.packageManager
        val uygulama = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(x.paket, 0)).toString() }.getOrDefault("")
        if (x.genel) return Oge(
            baslik = "Kaldığın yerden devam et",
            alt = "$uygulama · ${zamanMetni(x.son)}",
            resim = null,
            ac = { c -> uygulamaAc(c, x.paket) },
            kimlik = x.anahtar + x.son / 60_000,
            secenekler = listOf("Listeden kaldır" to { c -> Izlenenler.sil(c, x.anahtar) }),
            afisPaketi = x.paket,
        )
        val kalan = if (x.sure > 0) " · ${((x.sure - x.konum) / 60_000).coerceAtLeast(1)} dk kaldı" else ""
        // Dizi/film ise (Netflix gibi) kapak yerine yapımın geniş görseli daha güzel durur.
        val yapim = if (x.paket != Izlenen.YOUTUBE) eslesme(ctx, x)?.optString("arka")?.ifBlank { null } else null
        val resim = yapim
            ?: x.resim?.let { File(Izlenenler.kapakKlasoru(ctx), it).path }
            ?: x.videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        return Oge(
            baslik = x.baslik,
            alt = listOf(x.alt, uygulama).filter { it.isNotBlank() }.distinct().joinToString(" · ") + kalan,
            resim = resim,
            ac = { c -> devamAc(c, x) },
            ilerleme = if (x.sure > 0) (x.konum.toFloat() / x.sure).coerceIn(0.03f, 1f) else -1f,
            kimlik = x.anahtar + (resim ?: ""),
            secenekler = listOf("Listeden kaldır" to { c -> Izlenenler.sil(c, x.anahtar) }),
            afisPaketi = if (resim == null) x.paket else null,
            tekil = x.videoId?.let { "yt:$it" } ?: x.anahtar,
        )
    }

    /** JustWatch'ta başlık araması (kod verilirse o platformda); kimlik, türler, link, geniş görsel. Önbellekli. */
    private fun jwAra(ctx: Context, ad: String, kod: String?, getirilsin: Boolean): JSONObject? {
        val f = File(ctx.filesDir, "jw_ara.json")
        val anahtar = "${kod.orEmpty()}|$ad"
        val hepsi = synchronized(this) { runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject()) }
        hepsi.optJSONObject(anahtar)?.let { return it.takeIf { o -> o.has("id") } }
        if (!getirilsin) return null
        val filtre = JSONObject().put("searchQuery", ad)
        if (kod != null) filtre.put("packages", JSONArray().put(kod))
        val ulke = PLATFORMLAR.firstOrNull { it.kod == kod }?.ulke ?: "TR"
        val sorgu = "query P(\$c:Country!,\$u:Country!,\$l:Language!,\$f:TitleFilter){popularTitles(country:\$u,first:1,filter:\$f){edges{node{$ICERIK}}}}"
        val y = runCatching { jw(sorgu, filtre, ulke) }.getOrNull() ?: return null
        var bulunan = JSONObject()
        val e = y.getJSONObject("popularTitles").getJSONArray("edges")
        if (e.length() > 0) {
            val n = e.getJSONObject(0).getJSONObject("node")
            val c = n.getJSONObject("content")
            val turler = JSONArray()
            c.optJSONArray("genres")?.let { g -> for (j in 0 until g.length()) turler.put(g.getJSONObject(j).optString("shortName")) }
            val arka = c.optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl")?.ifBlank { null }?.let { resimAdresi(it, "s640") }
            val poster = c.optString("posterUrl").ifBlank { null }?.let { resimAdresi(it, "s332") }
            bulunan = JSONObject().put("id", n.optString("id")).put("turler", turler).put("ad", c.optString("title"))
                .put("url", kod?.let { teklif(n, it) } ?: "").put("arka", arka ?: "").put("poster", poster ?: "")
        }
        synchronized(this) {
            val guncel = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
            guncel.put(anahtar, bulunan)
            f.writeText(guncel.toString())
        }
        return bulunan.takeIf { it.has("id") }
    }

    internal fun devamAc(ctx: Context, x: Izlenen) {
        // Uygulama hâlâ açıksa oynatıcısına dön: kaldığı saniyeden devam eder.
        Izlenenler.oturumlar[x.anahtar]?.let { pi ->
            val ok = runCatching {
                val secenek = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
                pi.send(ctx, 0, null, null, null, null, secenek)
            }.isSuccess
            if (ok) return
        }
        x.videoId?.let { youtubeAc(ctx, it, x.konum / 1000); return }
        if (x.paket == Izlenen.YOUTUBE) {
            Ag.arkada {
                val id = runCatching { videoBul("${x.baslik} ${x.alt}") }.getOrNull()
                Ag.anada { if (id != null) youtubeAc(ctx, id, x.konum / 1000) else uygulamaAc(ctx, x.paket) }
            }
            return
        }
        val url = eslesme(ctx, x)?.optString("url")?.ifBlank { null }
        if (url != null && linkAc(ctx, url, x.paket)) return
        uygulamaAc(ctx, x.paket)
    }

    // ---------------------------------------------------------------- YouTube

    private fun youtube(ctx: Context, sadeceOnbellek: Boolean): Raf {
        val p = Salon.ayarlar(ctx)
        val gizli = p.getStringSet("yt_gizli", emptySet())!!
        val izlenen = Izlenenler.uygulamada(ctx, Izlenen.YOUTUBE)
        // Abonelik listesi varsa (Google Takeout'un subscriptions.csv'si) abone olunan kanallar; yoksa izlenenler.
        val abone = abonelikler(ctx)
        // En çok ve en son izlenen kanallar önce.
        val kanalAdlari = if (abone.isNotEmpty()) emptyList() else izlenen.groupBy { it.alt }.filterKeys { it.isNotBlank() }
            .entries.sortedByDescending { (_, l) -> l.size * 2 + if (l.first().son > System.currentTimeMillis() - 3 * 86_400_000L) 5 else 0 }
            .map { it.key }.take(14)
        val kimlikler = LinkedHashSet<String>(abone)
        for (ad in kanalAdlari) {
            val anahtar = "ytk_$ad"
            var id = p.getString(anahtar, null)
            if (id == null && !sadeceOnbellek) {
                id = runCatching { kanalBul(ad) }.getOrNull() ?: ""
                p.edit().putString(anahtar, id).apply()
            }
            if (!id.isNullOrBlank()) kimlikler += id
        }
        p.getString("yt_ek", "")!!.split(',').filter { it.startsWith("UC") }.forEach { kimlikler += it }
        kimlikler.removeAll(gizli)

        val izlenenIdler = izlenen.mapNotNull { it.videoId }.toSet()
        val izlenenAdlar = izlenen.map { it.baslik }.toSet()
        val sinir = Instant.now().minus(if (abone.isNotEmpty()) 10L else 21L, ChronoUnit.DAYS)
        // Abonelikler yüzlerce kanal olabilir: akışlar paralel çekilir (her biri 3 saat saklanır).
        if (!sadeceOnbellek) akislariHazirla(ctx, kimlikler)
        val kanalBasina = if (abone.isNotEmpty()) 2 else 3
        val videolar = mutableListOf<Triple<Instant, String, Oge>>()
        for (kanal in kimlikler) {
            val xml = akis(ctx, kanal, sadeceOnbellek = true) ?: continue
            var adet = 0
            for (v in rssOku(xml)) {
                if (adet >= kanalBasina) break
                if (v.zaman.isBefore(sinir) || v.id in izlenenIdler || v.baslik in izlenenAdlar) continue
                if (shortsMu(ctx, v.id, sadeceOnbellek)) continue
                adet++
                videolar += Triple(v.zaman, kanal, Oge(
                    baslik = v.baslik,
                    alt = "${v.kanal} · ${once(v.zaman)}",
                    resim = "https://i.ytimg.com/vi/${v.id}/hqdefault.jpg",
                    ac = { c -> youtubeAc(c, v.id, 0) },
                    kimlik = v.id,
                    tekil = "yt:${v.id}",
                    secenekler = listOf("Bu kanalı gösterme" to { c ->
                        val pp = Salon.ayarlar(c)
                        pp.edit().putStringSet("yt_gizli", pp.getStringSet("yt_gizli", emptySet())!! + kanal).apply()
                    }),
                ))
            }
        }
        val ogeler = videolar.sortedByDescending { it.first }.map { it.third }.take(24)
        val baslik = if (abone.isNotEmpty()) "YouTube · abone olduğun kanallardan yeni" else "YouTube · izlediğin kanallardan yeni"
        return Raf(baslik, ogeler, poster = false, paket = Izlenen.YOUTUBE)
    }

    /**
     * Abone olunan kanallar. YouTube bu listeyi hesap izni olmadan vermiyor; Google Takeout'tan inen
     * "subscriptions.csv" (ilk sütun kanal kimliği, UC...) kurulumda TV'ye atılır:
     * /sdcard/Android/data/com.demir.salon/files/abonelikler.csv (kur.ps1 kurulum klasöründe bulursa kendisi atar).
     */
    private fun abonelikler(ctx: Context): List<String> {
        val f = File(ctx.getExternalFilesDir(null) ?: return emptyList(), "abonelikler.csv")
        if (!f.exists()) return emptyList()
        return runCatching { Regex("UC[\\w-]{22}").findAll(f.readText()).map { it.value }.distinct().toList() }.getOrDefault(emptyList())
    }

    private fun akislariHazirla(ctx: Context, kanallar: Collection<String>) {
        val havuz = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            kanallar.map { k -> havuz.submit { runCatching { akis(ctx, k, sadeceOnbellek = false) } } }.forEach { runCatching { it.get() } }
        } finally {
            havuz.shutdown()
        }
    }

    /**
     * RSS akışı Shorts'u ayırmıyor. youtube.com/shorts/ID normal videoda izleme sayfasına yönlendirir,
     * Shorts'ta açılır; cevap bir kez sorulup saklanır. Bilinmiyorsa (çevrimdışı) gösterilmez.
     */
    private fun shortsMu(ctx: Context, id: String, sadeceOnbellek: Boolean): Boolean {
        val p = ctx.getSharedPreferences("shorts", Context.MODE_PRIVATE)
        if (p.contains(id)) return p.getBoolean(id, false)
        if (sadeceOnbellek) return true
        val sonuc = runCatching { Ag.yonlendirmeYok("https://www.youtube.com/shorts/$id") }.getOrNull() ?: return true
        p.edit().putBoolean(id, sonuc).apply()
        return sonuc
    }

    private class Video(val id: String, val baslik: String, val kanal: String, val zaman: Instant)

    private fun rssOku(xml: String): List<Video> {
        val kanal = Regex("<author>\\s*<name>([^<]*)</name>").find(xml)?.groupValues?.get(1)?.let(::xmlCoz).orEmpty()
        return Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { e ->
            val b = e.groupValues[1]
            val id = Regex("<yt:videoId>([^<]+)</yt:videoId>").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            val baslik = Regex("<title>([^<]*)</title>").find(b)?.groupValues?.get(1)?.let(::xmlCoz) ?: return@mapNotNull null
            val zaman = Regex("<published>([^<]+)</published>").find(b)?.groupValues?.get(1)
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return@mapNotNull null
            // Shorts dikey ve kısa; televizyonda anlamsız, başlığında #shorts geçenleri atla.
            if (baslik.contains("#shorts", ignoreCase = true)) return@mapNotNull null
            Video(id, baslik, kanal, zaman)
        }.toList()
    }

    internal fun xmlCoz(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    private fun akis(ctx: Context, kanal: String, sadeceOnbellek: Boolean): String? {
        val f = File(File(ctx.filesDir, "yt").apply { mkdirs() }, "$kanal.xml")
        if (f.exists() && (sadeceOnbellek || System.currentTimeMillis() - f.lastModified() < YT_SURE_MS)) return f.readText()
        if (sadeceOnbellek) return null
        return runCatching {
            Ag.metin("https://www.youtube.com/feeds/videos.xml?channel_id=$kanal").also { f.writeText(it) }
        }.getOrElse { if (f.exists()) f.readText() else null }
    }

    /** Kanal adından kanal kimliği: YouTube aramasının "kanallar" süzgeciyle ilk sonuç. */
    private fun kanalBul(ad: String): String? {
        val html = Ag.metin("https://www.youtube.com/results?search_query=${enc(ad)}&sp=EgIQAg%253D%253D", tarayici = true)
        return Regex("\"channelId\":\"(UC[\\w-]{22})\"").find(html)?.groupValues?.get(1)
    }

    private fun videoBul(sorgu: String): String? {
        val html = Ag.metin("https://www.youtube.com/results?search_query=${enc(sorgu)}", tarayici = true)
        return Regex("\"videoId\":\"([\\w-]{11})\"").find(html)?.groupValues?.get(1)
    }

    /** Maç özetini YouTube'da aç: beIN SPORTS Türkiye'nin yüklediği özeti arar, bulamazsa ilk sonuç. */
    fun ozetAc(ctx: Context, sorgu: String) {
        Ag.arkada {
            val id = runCatching { videoBul("beIN SPORTS Türkiye $sorgu") }.getOrNull()
                ?: runCatching { videoBul(sorgu) }.getOrNull()
            Ag.anada { if (id != null) youtubeAc(ctx, id, 0) else uygulamaAc(ctx, Izlenen.YOUTUBE) }
        }
    }

    private fun youtubeAc(ctx: Context, id: String, saniye: Long) {
        val t = if (saniye > 30) "&t=${saniye}s" else ""
        if (!linkAc(ctx, "https://www.youtube.com/watch?v=$id$t", Izlenen.YOUTUBE)) uygulamaAc(ctx, Izlenen.YOUTUBE)
    }

    /** "bugün 21:40", "dün 22:10", "3 gün önce" */
    internal fun zamanMetni(ms: Long): String {
        val t = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
        val gun = ChronoUnit.DAYS.between(t.toLocalDate(), java.time.LocalDate.now())
        val saat = t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return when (gun) { 0L -> "bugün $saat"; 1L -> "dün $saat"; else -> "$gun gün önce" }
    }

    private fun once(t: Instant): String {
        val saat = ChronoUnit.HOURS.between(t, Instant.now())
        return when {
            saat < 1 -> "az önce"
            saat < 24 -> "$saat saat önce"
            saat < 48 -> "dün"
            else -> "${saat / 24} gün önce"
        }
    }

    // ---------------------------------------------------------------- Netflix, Prime, Disney+...

    private const val JW = "https://apis.justwatch.com/graphql"
    private const val ICERIK = "id objectType content(country:\$c,language:\$l){title posterUrl originalReleaseYear " +
        "genres{shortName} backdrops{backdropUrl}} offers(country:\$u,platform:WEB){standardWebURL package{shortName}}"

    private fun platform(ctx: Context, p: Platform, sadeceOnbellek: Boolean): Raf {
        val f = File(ctx.filesDir, "raf_${p.kod}.json")
        val izlenen = Izlenenler.uygulamada(ctx, p.paket)
        val imza = "v3|" + izlenen.take(8).joinToString { it.baslik }
        var veri = runCatching { JSONObject(f.readText()) }.getOrNull()
        val bayat = veri == null || System.currentTimeMillis() - veri.optLong("zaman") > PLATFORM_SURE_MS || veri.optString("imza") != imza
        if (bayat && !sadeceOnbellek) {
            runCatching { platformGetir(ctx, p, izlenen) }.getOrNull()?.let {
                veri = it.put("zaman", System.currentTimeMillis()).put("imza", imza)
                f.writeText(veri.toString())
            }
        }
        val v = veri ?: return Raf(p.ad, emptyList(), true)
        val a = v.getJSONArray("ogeler")
        val ogeler = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Oge(
                baslik = o.getString("ad"),
                alt = siraYazisi(o.optString("yil")),
                resim = o.optString("poster").ifBlank { null },
                ac = { c -> if (!linkAc(c, o.optString("url"), p.paket)) uygulamaAc(c, p.paket) },
                kimlik = o.getString("id"),
                tekil = "jw:" + o.getString("id"),
            )
        }
        val baslik = if (v.optBoolean("ilk10")) "${p.ad} · Türkiye'de bu hafta ilk 10" else "${p.ek} bu hafta öne çıkanlar"
        return Raf(baslik, ogeler, poster = true, paket = p.paket)
    }

    /** İlk 10'da dizi ve film listesi karışık; "Dizi · 1. sıra" ile "Film · 1. sıra" yan yana karışmasın. */
    private fun siraYazisi(s: String): String {
        val m = Regex("^(Dizi|Film) · (\\d+)\\. sıra$").find(s) ?: return s
        return "${if (m.groupValues[1] == "Dizi") "Dizilerde" else "Filmlerde"} ${m.groupValues[2]}."
    }

    /**
     * Netflix'in kendi açıkladığı "Türkiye'de bu hafta ilk 10" (dizi + film). Adlar İngilizce gelir;
     * JustWatch'ta aranıp Türkçe adı, afişi ve Netflix linki bulunur.
     */
    private fun netflixIlk10(ctx: Context): JSONObject? {
        fun liste(url: String): List<String> {
            val html = Ag.metin(url, tarayici = true)
            return Regex("<td[^>]*class=\"[^\"]*title[^\"]*\"[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(html)
                .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim().replace(Regex("^\\d+"), "").trim() }
                .map { it.substringBefore(": Season").substringBefore(": Limited Series").substringBefore(": Sezon").trim() }
                .filter { it.isNotBlank() }.distinct().take(10).toList()
        }
        val diziler = runCatching { liste("https://www.netflix.com/tudum/top10/turkey/tv") }.getOrDefault(emptyList())
        val filmler = runCatching { liste("https://www.netflix.com/tudum/top10/turkey") }.getOrDefault(emptyList())
        if (diziler.isEmpty() && filmler.isEmpty()) return null
        // Dizi, film, dizi, film... sırayla; her birinin kendi listesindeki sırası yazılır.
        val karisik = mutableListOf<Triple<String, Int, Boolean>>()
        for (i in 0 until 10) {
            diziler.getOrNull(i)?.let { karisik += Triple(it, i + 1, true) }
            filmler.getOrNull(i)?.let { karisik += Triple(it, i + 1, false) }
        }
        val ogeler = JSONArray()
        for ((ad, sira, dizi) in karisik) {
            val j = jwAra(ctx, ad, "nfx", getirilsin = true) ?: continue
            val poster = j.optString("poster").ifBlank { null } ?: continue
            ogeler.put(JSONObject().put("id", j.optString("id")).put("ad", j.optString("ad").ifBlank { ad })
                .put("yil", "${if (dizi) "Dizi" else "Film"} · $sira. sıra").put("poster", poster).put("url", j.optString("url")))
        }
        return JSONObject().put("ogeler", ogeler).put("ilk10", true)
    }

    private fun platformGetir(ctx: Context, p: Platform, izlenen: List<Izlenen>): JSONObject {
        if (p.kod == "nfx") runCatching { netflixIlk10(ctx) }.getOrNull()?.takeIf { it.getJSONArray("ogeler").length() >= 6 }?.let { return it }
        // İzlenen dizileri JustWatch'ta bul, türlerini topla.
        val turSayisi = HashMap<String, Int>()
        val izlenenIdler = HashSet<String>()
        for (x in izlenen.take(8)) {
            val e = eslesme(ctx, x, getirilsin = true) ?: continue
            izlenenIdler += e.optString("id")
            val t = e.optJSONArray("turler") ?: continue
            for (i in 0 until t.length()) turSayisi.merge(t.getString(i), 1, Int::plus)
        }
        // Netflix'te bu hafta popüler olanlar: türe göre süzme yok, izlenenler sadece listeden çıkar.
        val turler = emptyList<String>()
        val filtre = JSONObject().put("packages", JSONArray().put(p.kod))
            .put("releaseYear", JSONObject().put("min", java.time.LocalDate.now().year - 1))
        if (turler.isNotEmpty()) filtre.put("genres", JSONArray(turler))
        val sorgu = "query P(\$c:Country!,\$u:Country!,\$l:Language!,\$f:TitleFilter){popularTitles(country:\$u,first:40,sortBy:TRENDING,filter:\$f){edges{node{$ICERIK}}}}"
        val yanit = jw(sorgu, filtre, p.ulke)
        val dugumler = yanit.getJSONObject("popularTitles").getJSONArray("edges")
        val liste = mutableListOf<Pair<Int, JSONObject>>()
        for (i in 0 until dugumler.length()) {
            val n = dugumler.getJSONObject(i).getJSONObject("node")
            if (n.optString("id") in izlenenIdler) continue
            val c = n.getJSONObject("content")
            val url = teklif(n, p.kod) ?: continue
            val poster = c.optString("posterUrl").ifBlank { null }?.let { resimAdresi(it, "s332") } ?: continue
            var ortak = 0
            c.optJSONArray("genres")?.let { g -> for (j in 0 until g.length()) if (g.getJSONObject(j).optString("shortName") in turler) ortak++ }
            liste += (ortak * 100 - i) to JSONObject().put("id", n.optString("id")).put("ad", c.optString("title"))
                .put("yil", c.optString("originalReleaseYear")).put("poster", poster).put("url", url)
        }
        val ogeler = JSONArray()
        liste.sortedByDescending { it.first }.take(24).forEach { ogeler.put(it.second) }
        return JSONObject().put("ogeler", ogeler).put("kisisel", turler.isNotEmpty())
    }

    /** İzlenen bir başlığın JustWatch karşılığı (kimlik, türler, uygulama içi link, geniş görsel); önbellekli. */
    internal fun eslesme(ctx: Context, x: Izlenen, getirilsin: Boolean = false): JSONObject? {
        if (x.genel || x.paket == Izlenen.YOUTUBE) return null
        val kod = PLATFORMLAR.firstOrNull { it.paket == x.paket }?.kod
        // Dizilerde başlık genelde bölüm adı, dizinin adı alt satırda olur; ikisini de dene.
        val aday = listOf(x.alt, x.baslik).map { it.substringBefore(":").substringBefore(" - ").trim() }.filter { it.length >= 2 }.distinct()
        for (s in aday) jwAra(ctx, s, kod, getirilsin)?.let { return it }
        return null
    }

    /** Arka planda: devam et satırındaki başlıkların eşleşmesini önceden bul ki tıklanınca beklemesin. */
    fun eslesmeleriHazirla(ctx: Context) {
        for (x in Izlenenler.devamEt(ctx).take(16)) runCatching { eslesme(ctx, x, getirilsin = true) }
    }

    private fun teklif(n: JSONObject, kod: String): String? {
        val t = n.optJSONArray("offers") ?: return null
        for (i in 0 until t.length()) {
            val o = t.getJSONObject(i)
            if (o.optJSONObject("package")?.optString("shortName") == kod) return o.optString("standardWebURL").ifBlank { null }
        }
        return null
    }

    private fun resimAdresi(yol: String, profil: String) =
        "https://images.justwatch.com" + yol.replace("{profile}", profil).replace("{format}", "jpg")

    private fun jw(sorgu: String, filtre: JSONObject, ulke: String = "TR"): JSONObject {
        val govde = JSONObject().put("query", sorgu)
            .put("variables", JSONObject().put("c", "TR").put("u", ulke).put("l", "tr").put("f", filtre))
        val y = JSONObject(Ag.gonder(JW, govde.toString()))
        return y.getJSONObject("data")
    }

    // ---------------------------------------------------------------- açma

    /** Linki doğrudan o uygulamada açar (Netflix'te dizinin sayfası gibi). Açamazsa false. */
    fun linkAc(ctx: Context, url: String, paket: String): Boolean {
        if (url.isBlank()) return false
        val niyet = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(paket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (paket == "com.netflix.ninja") niyet.putExtra("source", "30")
        return runCatching { ctx.startActivity(niyet) }.isSuccess
    }

    fun uygulamaAc(ctx: Context, paket: String) {
        val pm = ctx.packageManager
        val niyet = pm.getLeanbackLaunchIntentForPackage(paket) ?: pm.getLaunchIntentForPackage(paket) ?: return
        runCatching { ctx.startActivity(niyet.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

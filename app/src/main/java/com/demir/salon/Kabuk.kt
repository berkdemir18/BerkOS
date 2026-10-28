package com.demir.salon

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * TV'nin kendi adb kabuğu (127.0.0.1:5555). Android 14'te uygulamalar başka uygulamayı kapatamıyor;
 * "arka planı temizle" bunu kabuktan `am force-stop` ile yapar. Kütüphanesiz, adb protokolünün en küçük hâli.
 *
 * İlk bağlantıda TV "USB hata ayıklamasına izin verilsin mi?" diye sorar ("Her zaman izin ver" seçilmeli).
 * Port 5555 TV yeniden başlayınca kapanır; o zaman bilgisayardan kurulumla bir kez açılması gerekir.
 */
object Kabuk {
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257

    /** SHA-1 DigestInfo başlığı: adbd imzayı "SHA-1 özetiymiş gibi" PKCS#1 ile doğrular. */
    private val SHA1_BASLIK = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    class Hata(mesaj: String) : Exception(mesaj)

    /** Komutu çalıştırıp çıktısını döndürür; bağlanamazsa [Hata]. Ana iş parçacığında çağırma. */
    @Synchronized
    fun calistir(ctx: Context, komut: String, onayBekle: Long = 30_000): String {
        val anahtar = anahtar(ctx)
        Socket().use { s ->
            runCatching { s.connect(InetSocketAddress("127.0.0.1", 5555), 2_000) }
                .onFailure { throw Hata("kapali") }
            s.soTimeout = 10_000
            val giris = DataInputStream(s.getInputStream())
            val cikis = s.getOutputStream()
            gonder(cikis, CNXN, 0x01000001, 256 * 1024, "host::\u0000".toByteArray())
            var imzaladi = false
            while (true) {
                val m = oku(giris)
                when (m.komut) {
                    CNXN -> break
                    AUTH -> if (!imzaladi) {
                        imzaladi = true
                        gonder(cikis, AUTH, 2, 0, imzala(anahtar, m.veri))
                    } else {
                        // Anahtar tanınmadı: açık anahtarı gönder, TV'de onay penceresi çıkar.
                        gonder(cikis, AUTH, 3, 0, (acikAnahtar(anahtar) + " seyir@tv\u0000").toByteArray())
                        s.soTimeout = onayBekle.toInt()
                    }
                    else -> throw Hata("beklenmeyen")
                }
            }
            s.soTimeout = 15_000
            gonder(cikis, OPEN, 1, 0, "shell:$komut\u0000".toByteArray())
            val cikti = StringBuilder()
            while (true) {
                val m = runCatching { oku(giris) }.getOrNull() ?: break
                when (m.komut) {
                    WRTE -> { cikti.append(String(m.veri)); gonder(cikis, OKAY, 1, m.arg0, ByteArray(0)) }
                    CLSE -> { gonder(cikis, CLSE, 1, m.arg0, ByteArray(0)); break }
                }
            }
            return cikti.toString()
        }
    }

    private class Mesaj(val komut: Int, val arg0: Int, val veri: ByteArray)

    private fun oku(g: DataInputStream): Mesaj {
        val b = ByteArray(24)
        g.readFully(b)
        val h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val komut = h.int; val arg0 = h.int; h.int; val uzunluk = h.int
        val veri = ByteArray(uzunluk)
        g.readFully(veri)
        return Mesaj(komut, arg0, veri)
    }

    private fun gonder(c: OutputStream, komut: Int, arg0: Int, arg1: Int, veri: ByteArray) {
        val h = ByteBuffer.allocate(24 + veri.size).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(komut).putInt(arg0).putInt(arg1).putInt(veri.size)
        h.putInt(veri.sumOf { it.toInt() and 0xFF }).putInt(komut.inv())
        h.put(veri)
        c.write(h.array())
        c.flush()
    }

    private fun imzala(k: KeyPair, jeton: ByteArray): ByteArray = runCatching {
        Signature.getInstance("NONEwithRSA").run { initSign(k.private); update(SHA1_BASLIK + jeton); sign() }
    }.getOrElse {
        // Bazı sağlayıcılarda NONEwithRSA yok; özel anahtarla PKCS#1 şifreleme aynı imzayı verir.
        javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(javax.crypto.Cipher.ENCRYPT_MODE, k.private); doFinal(SHA1_BASLIK + jeton) }
    }

    /** adbd'nin beklediği biçim: base64(mincrypt RSAPublicKey yapısı). */
    private fun acikAnahtar(k: KeyPair): String {
        val pub = k.public as RSAPublicKey
        val n = pub.modulus
        val kelime = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(kelime).putInt(n0inv)
        b.put(kucukUclu(n)).put(kucukUclu(rr))
        b.putInt(pub.publicExponent.toInt())
        return Base64.encodeToString(b.array(), Base64.NO_WRAP)
    }

    private fun kucukUclu(x: BigInteger): ByteArray {
        val buyuk = x.toByteArray()
        val sonuc = ByteArray(256)
        for (i in 0 until minOf(256, buyuk.size)) sonuc[i] = buyuk[buyuk.size - 1 - i]
        return sonuc
    }

    private var onbellek: KeyPair? = null

    private fun anahtar(ctx: Context): KeyPair {
        onbellek?.let { return it }
        val ozel = File(ctx.filesDir, "adb.key")
        val acik = File(ctx.filesDir, "adb.pub")
        val kf = KeyFactory.getInstance("RSA")
        val k = if (ozel.exists() && acik.exists()) {
            KeyPair(kf.generatePublic(X509EncodedKeySpec(acik.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(ozel.readBytes())))
        } else {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().also {
                ozel.writeBytes(it.private.encoded)
                acik.writeBytes(it.public.encoded)
            }
        }
        return k.also { onbellek = it }
    }
}

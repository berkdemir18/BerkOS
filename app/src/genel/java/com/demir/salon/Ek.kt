package com.demir.salon

import android.content.Context
import org.json.JSONArray

/** Sürüme özel parçaların boş hâli: sabit konum yok, ek eser yok, ek satır yok, başka uygulamanın ekranı okunmaz. */
object Ek {
    val VARSAYILAN_KONUM: Konum? = null
    const val SERGI_YAZISI = "Tablolar ve uzay fotoğrafları sırayla gelir. Saat ve hava köşede yazar."
    val EKRANDAN_OKUNAN: Set<String> = emptySet()

    fun koleksiyon(ctx: Context): JSONArray = JSONArray()

    fun raflar(ctx: Context, yuklu: Set<String>, sadeceOnbellek: Boolean): List<Raf> = emptyList()

    fun siraDuzelt(sira: MutableList<String>) {}

    fun pencereOlayi(s: EvTusuServisi, paket: String): Boolean = false
}

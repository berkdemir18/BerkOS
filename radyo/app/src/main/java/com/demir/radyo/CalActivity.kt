package com.demir.radyo

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * BerkOS ana ekranındaki eski radyo, Radyo kapalıyken buradan açar: görünmez, son dinlenen radyoyu
 * (ya da popülerin ilkini) çalar. Android arka plandan çalmaya izin vermediği için çalma başlayana
 * kadar (en çok 8 sn) önde durur, sonra kendiliğinden kapanır; BerkOS arkada görünmeye devam eder.
 */
class CalActivity : Activity() {
    private val ana = Handler(Looper.getMainLooper())
    private val bitir = Runnable { finish() }
    private val dinleyici: () -> Unit = { if (Radyo.caliyor || Radyo.hata != null) ana.postDelayed(bitir, 300) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val yon = intent.getIntExtra("yon", 0)
        val ist = Radyo.istasyon ?: Radyo.sonIstasyon(this) ?: Radyolar.kategori(this, "populer").firstOrNull()
        if (ist == null) { finish(); return }
        if (yon != 0 && Radyo.istasyon != null) Radyo.sonraki(this, yon) else Radyo.cal(this, ist)
        Radyo.dinle(dinleyici)
        ana.postDelayed(bitir, 8_000)
    }

    override fun onDestroy() {
        Radyo.birak(dinleyici)
        ana.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Bu arada kumandaya basılırsa: sağ/sol radyo değiştirir, gerisi kapatır (tuş BerkOS'a kalsın). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Radyo.sonraki(this, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> Radyo.sonraki(this, 1)
            else -> finish()
        }
        return true
    }
}

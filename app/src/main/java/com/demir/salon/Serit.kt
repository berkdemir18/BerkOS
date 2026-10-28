package com.demir.salon

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.HorizontalScrollView

/**
 * Uygulama şeridi. Odak değişince kendisi yarım yamalak kaydırmaz; [MainActivity] kutu kutu kaydırır ki
 * şeridin iki ucunda hiçbir uygulama kesik görünmesin.
 */
class Serit(ctx: Context, a: AttributeSet?) : HorizontalScrollView(ctx, a) {
    override fun computeScrollDeltaToGetChildRectOnScreen(r: Rect?) = 0
    override fun executeKeyEvent(e: KeyEvent) = false
}

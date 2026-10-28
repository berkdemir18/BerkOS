package com.demir.salon

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.ScrollView

/**
 * Ana ekranın dikey sayfası. Odak değişince kendisi kaydırmaz; hangi satıra inildiyse o satır üst çubuğun
 * hemen altına gelecek şekilde [MainActivity] kaydırır (Google TV'deki gibi satır satır iner).
 */
class Sayfa(ctx: Context, a: AttributeSet?) : ScrollView(ctx, a) {
    override fun computeScrollDeltaToGetChildRectOnScreen(r: Rect?) = 0
    override fun executeKeyEvent(e: KeyEvent) = false
}

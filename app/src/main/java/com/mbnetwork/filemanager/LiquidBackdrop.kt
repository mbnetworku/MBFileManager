package com.mbnetwork.filemanager

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Liquid Glass background – kept simple so everything is easy to read:
 *  - White theme: clean white with a very soft grey at the top.
 *  - Black theme: pure black with a very soft dark grey at the top.
 * No colored glows.
 */
class LiquidBackdrop(private val night: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val h = bounds.height().toFloat()
        val top = if (night) 0xFF121212.toInt() else 0xFFF2F3F5.toInt()
        val bottom = if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

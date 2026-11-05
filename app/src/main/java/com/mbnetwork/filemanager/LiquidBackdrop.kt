package com.mbnetwork.filemanager

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Smooth Liquid Glass background: base gradient + soft light glows drawn over the full screen (no hard edges). */
class LiquidBackdrop(private val night: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            if (night) 0xFF03060E.toInt() else 0xFFEAF2FF.toInt(),
            if (night) 0xFF062033.toInt() else 0xFFD4EEF0.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(b, paint)
        val a = if (night) 0x70 else 0x90
        glow(c, w * 0.15f, h * 0.12f, w * 0.8f, (a shl 24) or 0x1E88E5)
        glow(c, w * 0.95f, h * 0.45f, w * 0.75f, (a shl 24) or 0x00BFA5)
        glow(c, w * 0.20f, h * 0.85f, w * 0.85f, (a shl 24) or 0x26C6DA)
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.shader = RadialGradient(x, y, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

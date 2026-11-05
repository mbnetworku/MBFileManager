package com.mbnetwork.filemanager

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.Random
import kotlin.math.abs

/** Animated equalizer bars. They dance while music plays and settle down when it is paused. */
class EqualizerView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val count = 30
    private val cur = FloatArray(count) { 0.06f }
    private val tgt = FloatArray(count) { 0.06f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = Random()
    private var frame = 0
    private var running = false

    /** true while a song is playing */
    var playing = false

    private val tick = object : Runnable {
        override fun run() {
            step()
            invalidate()
            if (running) postOnAnimation(this)
        }
    }

    fun start() { if (!running) { running = true; postOnAnimation(tick) } }
    fun stop() { running = false }

    private fun step() {
        frame++
        for (i in 0 until count) {
            if (playing) {
                if (frame % 7 == i % 7) {
                    val middle = 1f - abs(i - count / 2f) / (count / 2f) * 0.55f      // louder in the centre
                    tgt[i] = (0.15f + rnd.nextFloat() * 0.85f) * middle
                }
            } else tgt[i] = 0.06f
            cur[i] += (tgt[i] - cur[i]) * 0.28f
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF7DD3FC.toInt(), 0xFF0A84FF.toInt(), 0xFF7DD3FC.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val gap = w / count
        val bw = gap * 0.55f
        for (i in 0 until count) {
            val bh = (h * cur[i]).coerceAtLeast(bw)
            val x = i * gap + (gap - bw) / 2f
            val top = (h - bh) / 2f
            c.drawRoundRect(x, top, x + bw, top + bh, bw / 2f, bw / 2f, paint)       // symmetric around the middle
        }
    }

    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
}

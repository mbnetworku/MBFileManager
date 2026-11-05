package com.mbnetwork.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shows the photo being edited, with an optional draggable crop frame. */
class EditorCanvas(ctx: Context) : View(ctx) {

    var bitmap: Bitmap? = null
        set(v) { field = v; recalc(); invalidate() }

    /** Live preview of filter + adjustments. */
    var previewFilter: ColorFilter? = null
        set(v) { field = v; paint.colorFilter = v; invalidate() }

    var cropping = false
        set(v) { field = v; if (v) resetCrop(); invalidate() }

    /** 0 = free, otherwise width / height */
    var aspect = 0f
        set(v) { field = v; if (cropping) resetCrop() }

    private val d = resources.displayMetrics.density
    private val dst = RectF()
    private val crop = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2 * d }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = d }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** Magic wand: tap a point of the photo (bitmap pixels). */
    var wandMode = false
    var onWandTap: ((Int, Int) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    /** Checkerboard shown behind transparent areas. */
    private val checker = Paint().apply {
        val cell = (10 * d).toInt().coerceAtLeast(6)
        val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tile)
        tc.drawColor(0xFFE8E8E8.toInt())
        val dark = Paint().apply { color = 0xFFC8C8C8.toInt() }
        tc.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), dark)
        tc.drawRect(cell.toFloat(), cell.toFloat(), (cell * 2).toFloat(), (cell * 2).toFloat(), dark)
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private var mode = 0        // 0 none, 1 move, 2 TL, 3 TR, 4 BL, 5 BR
    private var lx = 0f
    private var ly = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); recalc() }

    private fun recalc() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * s; val h = b.height * s
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        if (cropping) resetCrop()
    }

    fun resetCrop() {
        if (dst.isEmpty) return
        if (aspect > 0f) {
            var w = dst.width(); var h = w / aspect
            if (h > dst.height()) { h = dst.height(); w = h * aspect }
            crop.set(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2)
        } else crop.set(dst)
        invalidate()
    }

    /** The crop frame in bitmap pixels. */
    fun cropInBitmap(): Rect? {
        val b = bitmap ?: return null
        val s = b.width / dst.width()
        val l = ((crop.left - dst.left) * s).toInt().coerceIn(0, b.width - 1)
        val t = ((crop.top - dst.top) * s).toInt().coerceIn(0, b.height - 1)
        val r = ((crop.right - dst.left) * s).toInt().coerceIn(l + 1, b.width)
        val bt = ((crop.bottom - dst.top) * s).toInt().coerceIn(t + 1, b.height)
        return Rect(l, t, r, bt)
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        if (b.hasAlpha()) c.drawRect(dst, checker)
        c.drawBitmap(b, null, dst, paint)
        if (!cropping) return
        c.drawRect(dst.left, dst.top, dst.right, crop.top, dim)
        c.drawRect(dst.left, crop.bottom, dst.right, dst.bottom, dim)
        c.drawRect(dst.left, crop.top, crop.left, crop.bottom, dim)
        c.drawRect(crop.right, crop.top, dst.right, crop.bottom, dim)
        val w3 = crop.width() / 3f; val h3 = crop.height() / 3f
        for (i in 1..2) {
            c.drawLine(crop.left + w3 * i, crop.top, crop.left + w3 * i, crop.bottom, grid)
            c.drawLine(crop.left, crop.top + h3 * i, crop.right, crop.top + h3 * i, grid)
        }
        c.drawRect(crop, line)
        val r = 8 * d
        c.drawCircle(crop.left, crop.top, r, handle); c.drawCircle(crop.right, crop.top, r, handle)
        c.drawCircle(crop.left, crop.bottom, r, handle); c.drawCircle(crop.right, crop.bottom, r, handle)
    }

    private fun near(x: Float, y: Float, px: Float, py: Float) = abs(x - px) < 30 * d && abs(y - py) < 30 * d

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (wandMode && !cropping) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> {
                    val b = bitmap
                    if (b != null && abs(e.x - downX) < 14 * d && abs(e.y - downY) < 14 * d && dst.contains(e.x, e.y)) {
                        val bx = ((e.x - dst.left) / dst.width() * b.width).toInt().coerceIn(0, b.width - 1)
                        val by = ((e.y - dst.top) / dst.height() * b.height).toInt().coerceIn(0, b.height - 1)
                        onWandTap?.invoke(bx, by)
                    }
                }
            }
            return true
        }
        if (!cropping) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = when {
                    near(e.x, e.y, crop.left, crop.top) -> 2
                    near(e.x, e.y, crop.right, crop.top) -> 3
                    near(e.x, e.y, crop.left, crop.bottom) -> 4
                    near(e.x, e.y, crop.right, crop.bottom) -> 5
                    crop.contains(e.x, e.y) -> 1
                    else -> 0
                }
                lx = e.x; ly = e.y
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1) move(e.x - lx, e.y - ly) else if (mode != 0) resize(e.x, e.y)
                lx = e.x; ly = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun move(dx0: Float, dy0: Float) {
        var dx = dx0; var dy = dy0
        if (crop.left + dx < dst.left) dx = dst.left - crop.left
        if (crop.right + dx > dst.right) dx = dst.right - crop.right
        if (crop.top + dy < dst.top) dy = dst.top - crop.top
        if (crop.bottom + dy > dst.bottom) dy = dst.bottom - crop.bottom
        crop.offset(dx, dy)
    }

    private fun resize(x: Float, y: Float) {
        val minS = 48 * d
        val px = x.coerceIn(dst.left, dst.right)
        val py = y.coerceIn(dst.top, dst.bottom)
        val ax = if (mode == 2 || mode == 4) crop.right else crop.left      // opposite corner stays fixed
        val ay = if (mode == 2 || mode == 3) crop.bottom else crop.top
        var w = max(abs(px - ax), minS)
        var h = max(abs(py - ay), minS)
        if (aspect > 0f) {
            h = w / aspect
            val maxW = if (px < ax) ax - dst.left else dst.right - ax
            val maxH = if (py < ay) ay - dst.top else dst.bottom - ay
            if (h > maxH) { h = maxH; w = h * aspect }
            if (w > maxW) { w = maxW; h = w / aspect }
        }
        val nx = if (px < ax) ax - w else ax + w
        val ny = if (py < ay) ay - h else ay + h
        crop.set(min(ax, nx), min(ay, ny), max(ax, nx), max(ay, ny))
    }
}

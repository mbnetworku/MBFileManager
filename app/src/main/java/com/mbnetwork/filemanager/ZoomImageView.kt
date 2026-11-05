package com.mbnetwork.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/** Image that you can pinch-zoom, drag and double-tap. A quick swipe (when not zoomed) changes the photo. */
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    var onSingleTap: (() -> Unit)? = null
    /** -1 = previous photo, +1 = next photo */
    var onSwipe: ((Int) -> Unit)? = null

    private val m = Matrix()
    private val vals = FloatArray(9)
    private var baseScale = 1f

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onSingleTap?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed()) fit() else zoomBy(2.5f, e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoomed()) { m.postTranslate(-dx, -dy); fix(); imageMatrix = m }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!zoomed() && abs(vx) > 900 && abs(vx) > abs(vy)) { onSwipe?.invoke(if (vx < 0) 1 else -1); return true }
            return false
        }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageBitmap(bm: Bitmap?) { super.setImageBitmap(bm); fit() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fit() }

    private fun currentScale(): Float { m.getValues(vals); return vals[Matrix.MSCALE_X] }
    private fun zoomed() = currentScale() > baseScale * 1.02f

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        baseScale = min(width / dw, height / dh)
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((width - dw * baseScale) / 2f, (height - dh * baseScale) / 2f)
        imageMatrix = m
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val cur = currentScale()
        val target = (cur * f).coerceIn(baseScale, baseScale * 6f)
        val real = target / cur
        m.postScale(real, real, fx, fy)
        fix()
        imageMatrix = m
    }

    /** Keeps the image inside the view (or centred when smaller). */
    private fun fix() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }
}

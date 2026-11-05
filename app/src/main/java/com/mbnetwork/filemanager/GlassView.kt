package com.mbnetwork.filemanager

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/**
 * Liquid Glass surface (Apple style).
 *
 * What you see:
 *  1. a LIVE blurred copy of whatever is behind it (content scrolling under the bar is blurred),
 *  2. a frosted tint (white in the White theme, near-black in the Black theme),
 *  3. a soft shine on the top half,
 *  4. a thin glass edge: dark hairline + bright specular highlight on the top-left / bottom-right.
 *
 * How the blur works (all Android versions, API 21+): the [source] view is drawn into a tiny bitmap
 * (1/8 size), blurred, and painted back scaled up. It only refreshes when the pixels behind change.
 * If anything goes wrong it falls back to a plain frosted fill, so it can never crash the app.
 */
class GlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val SHAPE_ALL = 0     // rounded on all corners (pills, floating panels)
        const val SHAPE_TOP = 1     // rounded top corners only (bottom sheets)
        const val SHAPE_NONE = 2    // straight edges (full-width top bar)

        const val RIM_FULL = 0      // edge highlight all around
        const val RIM_BOTTOM = 1    // a single hairline at the bottom (top bar)
        const val RIM_NONE = 2

        private const val SCALE = 8     // snapshot is 1/8 of the real size
        private const val BLUR_RADIUS = 4
    }

    private var shape = SHAPE_ALL
    private var radiusDp = 28f
    private var capsule = false
    private var rim = RIM_FULL
    private var tintAlpha = 0.6f

    /** The view whose pixels are blurred behind this glass. */
    var source: View? = null
        set(value) { field = value; lastHash = 0; invalidate() }

    private val liquidOn = Prefs(context).liquidGlass
    private val userTransparency = Prefs(context).transparency
    private var broken = false

    private val density = resources.displayMetrics.density
    private val night get() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private var snap: Bitmap? = null
    private var snapCanvas: Canvas? = null
    private var blurred: Bitmap? = null
    private var px = IntArray(0)
    private var tmp = IntArray(0)
    private var lastHash = 0
    private val loc = IntArray(2)
    private val sloc = IntArray(2)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // slightly boosted saturation, like Apple's glass
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.35f) })
    }
    private val tintPaint = Paint()
    private val sheenPaint = Paint()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dst = RectF()

    private var obs: ViewTreeObserver? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { refresh(); true }

    init {
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                if (w <= 0 || h <= 0) return
                val r = radiusPx()
                when (shape) {
                    SHAPE_NONE -> outline.setRect(0, 0, w, h)
                    SHAPE_TOP -> outline.setRoundRect(0, 0, w, h + r.toInt(), r)
                    else -> outline.setRoundRect(0, 0, w, h, r)
                }
            }
        }
    }

    /** One call to configure the look. */
    fun setup(
        source: View?,
        radiusDp: Float = 28f,
        shape: Int = SHAPE_ALL,
        rim: Int = RIM_FULL,
        capsule: Boolean = false,
        tintAlpha: Float = 0.6f
    ) {
        this.radiusDp = radiusDp
        this.shape = shape
        this.rim = rim
        this.capsule = capsule
        this.tintAlpha = tintAlpha
        invalidateOutline()
        this.source = source
    }

    private fun radiusPx() = if (capsule) height / 2f else radiusDp * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        lastHash = 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        obs = viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
    }

    override fun onDetachedFromWindow() {
        try { obs?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw) } catch (e: Throwable) { }
        obs = null
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ blur snapshot

    private fun refresh() {
        val src = source ?: return
        if (!liquidOn || broken || width < 8 || height < 8 || !isShown) return
        try {
            val w = (width / SCALE).coerceAtLeast(2)
            val h = (height / SCALE).coerceAtLeast(2)
            var s = snap
            if (s == null || s.width != w || s.height != h) {
                s = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                snap = s
                snapCanvas = Canvas(s)
                blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                px = IntArray(w * h)
                tmp = IntArray(w * h)
                lastHash = 0
            }
            val c = snapCanvas ?: return
            getLocationOnScreen(loc)
            src.getLocationOnScreen(sloc)

            c.drawColor(ContextCompat.getColor(context, R.color.window_bg))
            c.save()
            c.scale(1f / SCALE, 1f / SCALE)
            c.translate(-(loc[0] - sloc[0]).toFloat(), -(loc[1] - sloc[1]).toFloat())
            src.draw(c)
            c.restore()

            s.getPixels(px, 0, w, 0, 0, w, h)
            val hash = px.contentHashCode()
            if (hash == lastHash) return          // nothing changed behind the glass
            lastHash = hash

            blur(px, tmp, w, h)
            blurred?.setPixels(px, 0, w, 0, 0, w, h)
            invalidate()
        } catch (t: Throwable) {
            broken = true                          // fall back to plain frosted fill
            invalidate()
        }
    }

    /** Two rounds of horizontal + vertical box blur (looks like a Gaussian). */
    private fun blur(p: IntArray, t: IntArray, w: Int, h: Int) {
        repeat(2) {
            boxPass(p, t, w, h, true)
            boxPass(t, p, w, h, false)
        }
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        val r = BLUR_RADIUS
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var ra = 0
            var ga = 0
            var ba = 0
            for (k in -r..r) {
                val c = src[base + k.coerceIn(0, len - 1) * step]
                ra += (c shr 16) and 0xFF
                ga += (c shr 8) and 0xFF
                ba += c and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * step] = (0xFF shl 24) or ((ra / div) shl 16) or ((ga / div) shl 8) or (ba / div)
                val add = src[base + (i + r + 1).coerceAtMost(len - 1) * step]
                val rem = src[base + (i - r).coerceAtLeast(0) * step]
                ra += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                ga += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                ba += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun tintColor(alpha: Float): Int {
        val base = if (night) 0x1C1C1E else 0xFFFFFF
        return (base and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val bmp = blurred
        if (liquidOn && !broken && bmp != null && source != null && lastHash != 0) {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
            // user transparency slider: more transparent = less tint
            val a = (tintAlpha + (15 - userTransparency) / 100f * 0.5f).coerceIn(0.18f, 0.92f)
            tintPaint.color = tintColor(a)
            canvas.drawRect(0f, 0f, w, h, tintPaint)
            // soft shine on the top half
            sheenPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.55f,
                if (night) 0x26FFFFFF else 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h * 0.55f, sheenPaint)
        } else {
            // plain frosted fill (blur not available / still preparing / liquid glass off)
            tintPaint.color = tintColor(if (liquidOn) 0.9f else 0.97f)
            canvas.drawRect(0f, 0f, w, h, tintPaint)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (rim != RIM_NONE) drawRim(canvas)
    }

    private fun drawRim(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = 1.2f * density
        rimPaint.strokeWidth = sw
        rimPaint.shader = null
        val hair = if (night) 0x40FFFFFF else 0x1F000000

        if (rim == RIM_BOTTOM) {
            rimPaint.color = hair
            rimPaint.strokeWidth = density.coerceAtLeast(1f) * 0.8f
            c.drawLine(0f, h - 0.5f, w, h - 0.5f, rimPaint)
            return
        }

        val r = radiusPx()
        val inset = sw / 2f
        val bottom = if (shape == SHAPE_TOP) h + r + sw else h - inset
        val rect = RectF(inset, inset, w - inset, bottom)
        // outer hairline
        rimPaint.color = hair
        c.drawRoundRect(rect, r, r, rimPaint)
        // specular highlight: bright top-left and bottom-right, clear in the middle
        val hi = if (night) 0xAAFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        val mid = 0x00FFFFFF
        rimPaint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(hi, mid, mid, hi),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        rect.inset(sw, sw)
        c.drawRoundRect(rect, (r - sw).coerceAtLeast(0f), (r - sw).coerceAtLeast(0f), rimPaint)
        rimPaint.shader = null
    }
}

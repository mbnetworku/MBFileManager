package com.mbnetwork.filemanager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.FaceDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Smart" editing tools. Everything runs on the phone (nothing is uploaded):
 * face detection (Android FaceDetector), histogram analysis and an edge-based "interesting area" search.
 */
object SmartTools {

    // ------------------------------------------------------------------ faces

    /** Faces in bitmap pixels. The Android detector finds front-facing faces. */
    fun detectFaces(src: Bitmap, maxFaces: Int = 8): List<RectF> {
        val scale = min(1f, 640f / max(src.width, src.height))
        val w = ((src.width * scale).toInt() / 2) * 2
        val h = (src.height * scale).toInt()
        if (w < 32 || h < 32) return emptyList()
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val b565 = small.copy(Bitmap.Config.RGB_565, false) ?: return emptyList()
        val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
        val n = FaceDetector(w, h, maxFaces).findFaces(b565, faces)
        val out = mutableListOf<RectF>()
        for (i in 0 until n) {
            val f = faces[i] ?: continue
            if (f.confidence() < 0.4f) continue
            val p = PointF()
            f.getMidPoint(p)
            val d = f.eyesDistance()
            val cx = p.x / scale
            val cy = (p.y + d * 0.35f) / scale
            val fw = d * 2.6f / scale
            val fh = d * 3.2f / scale
            out += RectF(
                (cx - fw / 2).coerceAtLeast(0f), (cy - fh / 2).coerceAtLeast(0f),
                (cx + fw / 2).coerceAtMost(src.width.toFloat()), (cy + fh / 2).coerceAtMost(src.height.toFloat()))
        }
        return out
    }

    /** Hides faces with a mosaic. */
    fun pixelateFaces(src: Bitmap, faces: List<RectF>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val c = Canvas(out)
        for (f in faces) {
            val x = (f.left - f.width() * 0.08f).toInt().coerceIn(0, src.width - 2)
            val y = (f.top - f.height() * 0.08f).toInt().coerceIn(0, src.height - 2)
            val w = (f.width() * 1.16f).toInt().coerceIn(2, src.width - x)
            val h = (f.height() * 1.16f).toInt().coerceIn(2, src.height - y)
            val sub = Bitmap.createBitmap(src, x, y, w, h)
            val tiny = Bitmap.createScaledBitmap(sub, max(1, w / 14), max(1, h / 14), true)
            c.drawBitmap(Bitmap.createScaledBitmap(tiny, w, h, false), x.toFloat(), y.toFloat(), null)
        }
        return out
    }

    /** Keeps the people sharp and blurs the background (focus follows the faces, or the centre). */
    fun portraitBlur(src: Bitmap, faces: List<RectF>): Bitmap {
        val w = src.width
        val h = src.height
        val blurred = blur(src)
        val u = if (faces.isEmpty()) RectF(w * 0.3f, h * 0.3f, w * 0.7f, h * 0.7f)
            else RectF(faces[0]).also { r -> faces.drop(1).forEach { r.union(it) } }
        val cx = u.centerX()
        val cy = u.centerY() + u.height() * 0.6f          // include shoulders / body below the face
        val r = max(u.width() * 1.8f, u.height() * 1.4f).coerceAtLeast(min(w, h) * 0.25f)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawBitmap(blurred, 0f, 0f, null)

        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(src, 0f, 0f, null)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = RadialGradient(cx, cy, r, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply { postScale(1f, 1.3f, cx, cy) })      // taller than wide, like a person
        mask.shader = shader
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        lc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        oc.drawBitmap(layer, 0f, 0f, null)
        return out
    }

    /** Soft blur: shrink, smooth, enlarge. */
    private fun blur(src: Bitmap): Bitmap {
        val sw = max(2, src.width / 14)
        val sh = max(2, src.height / 14)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val tmp = IntArray(px.size)
        repeat(2) { boxPass(px, tmp, sw, sh, true); boxPass(tmp, px, sw, sh, false) }
        val smooth = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        smooth.setPixels(px, 0, sw, 0, 0, sw, sh)
        return Bitmap.createScaledBitmap(smooth, src.width, src.height, true)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        val r = 2
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var ra = 0; var ga = 0; var ba = 0
            for (k in -r..r) {
                val c = src[base + k.coerceIn(0, len - 1) * step]
                ra += (c shr 16) and 0xFF; ga += (c shr 8) and 0xFF; ba += c and 0xFF
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

    // ------------------------------------------------------------------ auto enhance

    /**
     * Looks at the photo's histogram and returns one colour matrix that stretches each colour channel
     * (contrast + colour balance), lifts or lowers the brightness towards a natural level and adds a little saturation.
     */
    fun autoEnhance(src: Bitmap): ColorMatrix {
        val s = min(1f, 256f / max(src.width, src.height))
        val w = max(1, (src.width * s).toInt())
        val h = max(1, (src.height * s).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256)
        var sumY = 0.0
        for (c in px) {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            hr[r]++; hg[g]++; hb[b]++
            sumY += 0.299 * r + 0.587 * g + 0.114 * b
        }
        fun pct(hist: IntArray, p: Float): Int {
            val target = (px.size * p).toInt()
            var acc = 0
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
            return 255
        }
        fun scaleFor(lo: Int, hi: Int) = (255f / (hi - lo).coerceAtLeast(40)).coerceIn(1f, 1.7f)
        val lr = pct(hr, 0.01f); val lg = pct(hg, 0.01f); val lb = pct(hb, 0.01f)
        val rs = scaleFor(lr, pct(hr, 0.99f)); val gs = scaleFor(lg, pct(hg, 0.99f)); val bs = scaleFor(lb, pct(hb, 0.99f))
        val ro = -lr * rs * 0.9f; val go = -lg * gs * 0.9f; val bo = -lb * bs * 0.9f
        val mean = (sumY / px.size).toFloat()
        val estimate = mean * (rs + gs + bs) / 3f + (ro + go + bo) / 3f
        val bright = ((118f - estimate) * 0.5f).coerceIn(-25f, 30f)
        val cm = ColorMatrix(floatArrayOf(
            rs, 0f, 0f, 0f, ro + bright,
            0f, gs, 0f, 0f, go + bright,
            0f, 0f, bs, 0f, bo + bright,
            0f, 0f, 0f, 1f, 0f))
        cm.postConcat(ColorMatrix().apply { setSaturation(1.15f) })
        return cm
    }

    // ------------------------------------------------------------------ smart crop

    /** Crop rectangle: around the faces if there are any, otherwise the most detailed area. [aspect] 0 = keep the photo's shape. */
    fun smartCrop(src: Bitmap, faces: List<RectF>, aspect: Float): Rect {
        val iw = src.width.toFloat()
        val ih = src.height.toFloat()
        val a = if (aspect > 0f) aspect else iw / ih
        var cw: Float
        var ch: Float
        val cx: Float
        val cy: Float

        if (faces.isNotEmpty()) {
            val u = RectF(faces[0])
            faces.drop(1).forEach { u.union(it) }
            ch = max(u.height() * 3.0f, u.width() * 3.0f / a)
            cw = ch * a
            cx = u.centerX()
            cy = u.centerY() + ch * 0.1f          // faces sit in the upper part of the picture
        } else {
            if (iw / ih > a) { ch = ih * 0.85f; cw = ch * a } else { cw = iw * 0.85f; ch = cw / a }
            val g = 64
            val small = Bitmap.createScaledBitmap(src, g, g, true)
            val px = IntArray(g * g)
            small.getPixels(px, 0, g, 0, 0, g, g)
            val lum = FloatArray(g * g) { i ->
                val c = px[i]
                0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
            }
            val ii = Array(g + 1) { DoubleArray(g + 1) }
            for (y in 0 until g) for (x in 0 until g) {
                val e = if (x in 1 until g - 1 && y in 1 until g - 1)
                    abs(lum[y * g + x + 1] - lum[y * g + x - 1]) + abs(lum[(y + 1) * g + x] - lum[(y - 1) * g + x]) else 0f
                ii[y + 1][x + 1] = e + ii[y][x + 1] + ii[y + 1][x] - ii[y][x]
            }
            val wc = (cw / iw * g).toInt().coerceIn(4, g)
            val hc = (ch / ih * g).toInt().coerceIn(4, g)
            var best = -1.0
            var bx = 0
            var by = 0
            for (y in 0..g - hc) for (x in 0..g - wc) {
                val sum = ii[y + hc][x + wc] - ii[y][x + wc] - ii[y + hc][x] + ii[y][x]
                val dx = (x + wc / 2f) / g - 0.5f
                val dy = (y + hc / 2f) / g - 0.5f
                val score = sum * (1.0 - 1.2 * (dx * dx + dy * dy))        // slightly prefer the centre
                if (score > best) { best = score; bx = x; by = y }
            }
            cx = (bx + wc / 2f) / g * iw
            cy = (by + hc / 2f) / g * ih
        }
        if (cw > iw) { cw = iw; ch = cw / a }
        if (ch > ih) { ch = ih; cw = ch * a }
        val left = (cx - cw / 2).coerceIn(0f, iw - cw)
        val top = (cy - ch / 2).coerceIn(0f, ih - ch)
        return Rect(left.toInt(), top.toInt(), (left + cw).toInt().coerceAtMost(src.width), (top + ch).toInt().coerceAtMost(src.height))
    }
}

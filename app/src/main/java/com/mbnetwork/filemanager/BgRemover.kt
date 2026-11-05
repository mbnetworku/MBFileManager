package com.mbnetwork.filemanager

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Background removal that runs fully on the phone (no internet, no online AI).
 *
 *  - [auto]: learns the colours of the background (picture edges) and of the subject (faces, or the centre),
 *    scores every pixel, smooths the result, keeps the subject region(s) and cuts the rest out.
 *    Works best when the subject stands out from the background.
 *  - [byColor] (magic wand): removes the area of similar colour that you tap.
 */
object BgRemover {

    private class Model(val k: Int) {
        val cr = FloatArray(k); val cg = FloatArray(k); val cb = FloatArray(k)
        val inv = FloatArray(k); val lnv = FloatArray(k)
    }

    // ------------------------------------------------------------------ automatic

    fun auto(src: Bitmap, faces: List<RectF>): Bitmap {
        val iw = src.width
        val ih = src.height
        val s = min(1f, 320f / max(iw, ih))
        val w = max(8, (iw * s).toInt())
        val h = max(8, (ih * s).toInt())
        val n = w * h
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(n)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            r[i] = ((c shr 16) and 255).toFloat(); g[i] = ((c shr 8) and 255).toFloat(); b[i] = (c and 255).toFloat()
        }

        // seeds: 0 unknown, 1 background, 2 subject
        val seed = IntArray(n)
        val ring = max(2, (min(w, h) * 0.04f).toInt())
        for (y in 0 until h) for (x in 0 until w) if (x < ring || y < ring || x >= w - ring || y >= h - ring) seed[y * w + x] = 1
        if (faces.isNotEmpty()) {
            for (f in faces) {
                val cx = f.centerX() * s; val cy = f.centerY() * s
                val fw = f.width() * s; val fh = f.height() * s
                mark(seed, w, h, cx, cy, fw * 0.35f, fh * 0.35f, 2, false)                  // face
                mark(seed, w, h, cx, cy + fh * 1.6f, fw * 0.6f, fh * 0.7f, 2, true)         // shoulders / body below the face
            }
        } else {
            mark(seed, w, h, w / 2f, h / 2f, w * 0.18f, h * 0.18f, 2, false)               // centre of the picture
        }

        val lab = BooleanArray(n)
        var pf = FloatArray(n)
        val bgIdx = IntArray(n)
        val fgIdx = IntArray(n)
        for (iter in 0 until 3) {
            var nb = 0; var nf = 0
            for (i in 0 until n) {
                val isBg = seed[i] == 1 || (iter > 0 && !lab[i] && pf[i] < 0.2f && seed[i] != 2)
                val isFg = seed[i] == 2 || (iter > 0 && lab[i] && pf[i] > 0.8f && seed[i] != 1)
                if (isBg) bgIdx[nb++] = i else if (isFg) fgIdx[nf++] = i
            }
            val mb = fit(r, g, b, bgIdx, nb)
            val mf = fit(r, g, b, fgIdx, nf)
            val raw = FloatArray(n)
            for (i in 0 until n) {
                val d = (cost(mb, r[i], g[i], b[i]) - cost(mf, r[i], g[i], b[i])).coerceIn(-12f, 12f)
                raw[i] = 1f / (1f + exp(-d))
            }
            pf = smooth(raw, r, g, b, w, h, 6)
            for (i in 0 until n) lab[i] = when (seed[i]) { 1 -> false; 2 -> true; else -> pf[i] > 0.5f }
        }

        // keep the subject region(s): the connected parts that contain a subject seed
        val (comp, nc) = components(lab, w, h, true)
        val keep = BooleanArray(max(1, nc))
        var any = false
        for (i in 0 until n) if (seed[i] == 2 && comp[i] >= 0) { keep[comp[i]] = true; any = true }
        if (!any && nc > 0) {
            val size = IntArray(nc)
            for (i in 0 until n) if (comp[i] >= 0) size[comp[i]]++
            var best = 0
            for (c in 1 until nc) if (size[c] > size[best]) best = c
            keep[best] = true
        }
        val fg = BooleanArray(n) { i -> comp[i] >= 0 && keep[comp[i]] }

        // fill small holes inside the subject
        val (hc, hn) = components(fg, w, h, false)
        if (hn > 0) {
            val size = IntArray(hn)
            val border = BooleanArray(hn)
            for (y in 0 until h) for (x in 0 until w) {
                val c = hc[y * w + x]
                if (c >= 0) { size[c]++; if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border[c] = true }
            }
            for (i in 0 until n) { val c = hc[i]; if (c >= 0 && !border[c] && size[c] < n * 0.08f) fg[i] = true }
        }

        // soft edge, then enlarge the mask to the real photo size
        var m = FloatArray(n) { if (fg[it]) 1f else 0f }
        repeat(2) { m = box3(m, w, h) }
        val mp = IntArray(n) { ((m[it] * 255f).toInt().coerceIn(0, 255)) shl 24 }
        val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mb.setPixels(mp, 0, w, 0, 0, w, h)
        val big = Bitmap.createScaledBitmap(mb, iw, ih, true)
        val bp = IntArray(iw * ih)
        big.getPixels(bp, 0, iw, 0, 0, iw, ih)
        val sp = IntArray(iw * ih)
        src.getPixels(sp, 0, iw, 0, 0, iw, ih)
        for (i in sp.indices) {
            val a = (bp[i] ushr 24) / 255f
            val t = ((a - 0.30f) / 0.40f).coerceIn(0f, 1f)
            val sm = t * t * (3f - 2f * t)
            val na = (sm * (sp[i] ushr 24)).toInt()
            sp[i] = (na shl 24) or (sp[i] and 0x00FFFFFF)
        }
        val out = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        out.setPixels(sp, 0, iw, 0, 0, iw, ih)
        return out
    }

    private fun mark(seed: IntArray, w: Int, h: Int, cx: Float, cy: Float, rx0: Float, ry0: Float, v: Int, onlyUnknown: Boolean) {
        val rx = rx0.coerceAtLeast(2f)
        val ry = ry0.coerceAtLeast(2f)
        val x0 = max(0, (cx - rx).toInt()); val x1 = min(w - 1, (cx + rx).toInt())
        val y0 = max(0, (cy - ry).toInt()); val y1 = min(h - 1, (cy + ry).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = (x - cx) / rx; val dy = (y - cy) / ry
            if (dx * dx + dy * dy <= 1f) { val i = y * w + x; if (!onlyUnknown || seed[i] == 0) seed[i] = v }
        }
    }

    /** k-means colour model (up to 4 colour groups) of the given pixels. */
    private fun fit(r: FloatArray, g: FloatArray, b: FloatArray, idx: IntArray, count: Int): Model {
        val k = min(4, max(1, count))
        val m = Model(k)
        if (count == 0) {
            m.cr[0] = 128f; m.cg[0] = 128f; m.cb[0] = 128f
            m.inv[0] = 1f / 3600f; m.lnv[0] = ln(3600f)
            return m
        }
        for (c in 0 until k) {
            val p = idx[if (k == 1) 0 else (count - 1) * c / (k - 1)]
            m.cr[c] = r[p]; m.cg[c] = g[p]; m.cb[c] = b[p]
        }
        val asg = IntArray(count)
        repeat(6) {
            for (t in 0 until count) {
                val p = idx[t]
                var best = 0; var bd = Float.MAX_VALUE
                for (c in 0 until k) {
                    val dr = r[p] - m.cr[c]; val dg = g[p] - m.cg[c]; val db = b[p] - m.cb[c]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bd) { bd = d; best = c }
                }
                asg[t] = best
            }
            val sr = FloatArray(k); val sg = FloatArray(k); val sb = FloatArray(k); val cnt = IntArray(k)
            for (t in 0 until count) { val p = idx[t]; val c = asg[t]; sr[c] += r[p]; sg[c] += g[p]; sb[c] += b[p]; cnt[c]++ }
            for (c in 0 until k) if (cnt[c] > 0) { m.cr[c] = sr[c] / cnt[c]; m.cg[c] = sg[c] / cnt[c]; m.cb[c] = sb[c] / cnt[c] }
        }
        val sv = FloatArray(k); val cnt = IntArray(k)
        for (t in 0 until count) {
            val p = idx[t]; val c = asg[t]
            val dr = r[p] - m.cr[c]; val dg = g[p] - m.cg[c]; val db = b[p] - m.cb[c]
            sv[c] += (dr * dr + dg * dg + db * db) / 3f; cnt[c]++
        }
        for (c in 0 until k) {
            val v = max(40f, if (cnt[c] > 0) sv[c] / cnt[c] else 3600f)
            m.inv[c] = 1f / v; m.lnv[c] = ln(v)
        }
        return m
    }

    /** Negative log-likelihood of a colour under the model (smaller = more likely). */
    private fun cost(m: Model, r: Float, g: Float, b: Float): Float {
        var best = Float.MAX_VALUE
        for (c in 0 until m.k) {
            val dr = r - m.cr[c]; val dg = g - m.cg[c]; val db = b - m.cb[c]
            val v = 0.5f * (dr * dr + dg * dg + db * db) * m.inv[c] + 1.5f * m.lnv[c]
            if (v < best) best = v
        }
        return best
    }

    /** Edge-aware smoothing: neighbours with a similar colour count more. */
    private fun smooth(p: FloatArray, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, passes: Int): FloatArray {
        var a = p
        var o = FloatArray(a.size)
        repeat(passes) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var sum = 0f; var ws = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    val dr = r[i] - r[j]; val dg = g[i] - g[j]; val db = b[i] - b[j]
                    val wt = 1f / (1f + (dr * dr + dg * dg + db * db) / 500f)
                    sum += a[j] * wt; ws += wt
                }
                o[i] = sum / ws
            }
            val t = a; a = o; o = t
        }
        return a
    }

    private fun box3(a: FloatArray, w: Int, h: Int): FloatArray {
        val o = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                s += a[ny * w + nx]; c++
            }
            o[y * w + x] = s / c
        }
        return o
    }

    /** Connected regions (4 neighbours) of pixels whose value equals [target]. Returns the region id of each pixel (-1 = other) and the count. */
    private fun components(mask: BooleanArray, w: Int, h: Int, target: Boolean): Pair<IntArray, Int> {
        val n = w * h
        val comp = IntArray(n) { -1 }
        val stack = IntArray(n)
        var nc = 0
        for (start in 0 until n) {
            if (comp[start] != -1 || mask[start] != target) continue
            var sp = 0
            stack[sp++] = start; comp[start] = nc
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                if (x > 0) { val q = p - 1; if (comp[q] == -1 && mask[q] == target) { comp[q] = nc; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (comp[q] == -1 && mask[q] == target) { comp[q] = nc; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (comp[q] == -1 && mask[q] == target) { comp[q] = nc; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (comp[q] == -1 && mask[q] == target) { comp[q] = nc; stack[sp++] = q } }
            }
            nc++
        }
        return comp to nc
    }

    // ------------------------------------------------------------------ magic wand

    /** Removes the connected area whose colour is close to the tapped pixel. [tolerance] 5..100. */
    fun byColor(src: Bitmap, sx: Int, sy: Int, tolerance: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val x0 = sx.coerceIn(0, w - 1); val y0 = sy.coerceIn(0, h - 1)
        val t = px[y0 * w + x0]
        val tr = (t shr 16) and 255; val tg = (t shr 8) and 255; val tb = t and 255
        val limit = tolerance * tolerance * 3
        val removed = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = y0 * w + x0
        removed[y0 * w + x0] = true
        fun matches(i: Int): Boolean {
            val c = px[i]
            if ((c ushr 24) < 16) return true                      // already transparent: let the fill pass through
            val dr = ((c shr 16) and 255) - tr; val dg = ((c shr 8) and 255) - tg; val db = (c and 255) - tb
            return dr * dr + dg * dg + db * db <= limit
        }
        while (sp > 0) {
            val p = stack[--sp]
            val x = p % w; val y = p / w
            if (x > 0) { val q = p - 1; if (!removed[q] && matches(q)) { removed[q] = true; stack[sp++] = q } }
            if (x < w - 1) { val q = p + 1; if (!removed[q] && matches(q)) { removed[q] = true; stack[sp++] = q } }
            if (y > 0) { val q = p - w; if (!removed[q] && matches(q)) { removed[q] = true; stack[sp++] = q } }
            if (y < h - 1) { val q = p + w; if (!removed[q] && matches(q)) { removed[q] = true; stack[sp++] = q } }
        }
        // soften the cut edge: pixels touching the removed area become half transparent
        val out = px.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (removed[i]) { out[i] = out[i] and 0x00FFFFFF; continue }
            val edge = (x > 0 && removed[i - 1]) || (x < w - 1 && removed[i + 1]) || (y > 0 && removed[i - w]) || (y < h - 1 && removed[i + w])
            if (edge) out[i] = (((out[i] ushr 24) * 0.5f).toInt() shl 24) or (out[i] and 0x00FFFFFF)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}

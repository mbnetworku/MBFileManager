package com.mbnetwork.filemanager

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Windows-style file icons:
 *  - folders look like the yellow Windows 11 folder (back plate, white paper, glossy front),
 *  - files look like a white page with a folded corner and a coloured type label (PDF, DOCX, XLSX, MP3…).
 */
object FileIcons {

    enum class Kind(val color: Int) {
        PDF(0xFFD93025.toInt()),
        WORD(0xFF2B579A.toInt()),
        EXCEL(0xFF1D7A45.toInt()),
        PPT(0xFFD24726.toInt()),
        TEXT(0xFF5F6B7A.toInt()),
        ZIP(0xFFB07D2B.toInt()),
        APK(0xFF2DA44E.toInt()),
        IMAGE(0xFF34A853.toInt()),
        VIDEO(0xFFE53935.toInt()),
        AUDIO(0xFFF57C00.toInt()),
        OTHER(0xFF6B7683.toInt())
    }

    private val kinds: Map<String, Kind> = buildMap {
        fun add(kind: Kind, vararg ext: String) = ext.forEach { put(it, kind) }
        add(Kind.PDF, "pdf")
        add(Kind.WORD, "doc", "docx", "docm", "odt", "rtf")
        add(Kind.EXCEL, "xls", "xlsx", "xlsm", "csv", "ods")
        add(Kind.PPT, "ppt", "pptx", "pptm", "odp")
        add(Kind.TEXT, "txt", "log", "md", "json", "xml", "ini", "conf", "cfg", "prop", "properties", "html", "htm",
            "js", "css", "kt", "java", "py", "sh", "bat", "yml", "yaml", "srt", "nfo")
        add(Kind.ZIP, "zip", "rar", "7z", "tar", "tgz", "gz", "xz", "bz2", "obb")
        add(Kind.APK, "apk")
        add(Kind.IMAGE, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
        add(Kind.VIDEO, "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts", "m4v")
        add(Kind.AUDIO, "mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "amr", "wma")
    }

    fun kindOf(name: String): Kind = kinds[name.substringAfterLast('.', "").lowercase()] ?: Kind.OTHER

    fun drawable(f: FileItem): Drawable {
        if (f.isDir) return FileIconDrawable(true, Kind.OTHER, "")
        val ext = if (f.name.contains('.')) f.name.substringAfterLast('.').uppercase().take(4) else ""
        return FileIconDrawable(false, kindOf(f.name), ext)
    }
}

/** Shared paints / paths, all drawn in a 100 x 100 design box. */
private object Shapes {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    // ---- folder (Windows 11 style) ----
    val folderBack = Path().apply {
        moveTo(6f, 24f); quadTo(6f, 17f, 13f, 17f); lineTo(37f, 17f); quadTo(41f, 17f, 44f, 21f)
        lineTo(48f, 26f); lineTo(87f, 26f); quadTo(94f, 26f, 94f, 33f); lineTo(94f, 79f)
        quadTo(94f, 86f, 87f, 86f); lineTo(13f, 86f); quadTo(6f, 86f, 6f, 79f); close()
    }
    val folderBackShader = LinearGradient(0f, 17f, 0f, 86f, 0xFFDC9A00.toInt(), 0xFFEFB41C.toInt(), Shader.TileMode.CLAMP)
    val folderFrontShader = LinearGradient(0f, 38f, 0f, 86f, 0xFFFFD976.toInt(), 0xFFFFB81C.toInt(), Shader.TileMode.CLAMP)
    val paper = RectF(13f, 29f, 87f, 62f)
    val front = RectF(6f, 38f, 94f, 86f)

    // ---- page ----
    val page = Path().apply {
        moveTo(22f, 6f); lineTo(60f, 6f); lineTo(78f, 24f); lineTo(78f, 90f)
        quadTo(78f, 94f, 74f, 94f); lineTo(26f, 94f); quadTo(22f, 94f, 22f, 90f); close()
    }
    val fold = Path().apply {
        moveTo(60f, 6f); lineTo(60f, 20f); quadTo(60f, 24f, 64f, 24f); lineTo(78f, 24f); close()
    }
    val pageShader = LinearGradient(0f, 6f, 0f, 94f, 0xFFFFFFFF.toInt(), 0xFFECEFF3.toInt(), Shader.TileMode.CLAMP)
    val badge = RectF(14f, 60f, 72f, 82f)

    // ---- glyphs ----
    val mountains = Path().apply {
        moveTo(30f, 54f); lineTo(42f, 39f); lineTo(50f, 48f); lineTo(56f, 42f); lineTo(70f, 54f); close()
    }
    val play = Path().apply { moveTo(45f, 33f); lineTo(45f, 51f); lineTo(60f, 42f); close() }
    val beam = Path().apply { moveTo(44f, 34f); lineTo(62.5f, 30f); lineTo(62.5f, 36f); lineTo(44f, 40f); close() }
    val android = RectF(37f, 38f, 63f, 64f)
}

private class FileIconDrawable(
    private val folder: Boolean,
    private val kind: FileIcons.Kind,
    private val label: String
) : Drawable() {

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val side = minOf(b.width(), b.height()).toFloat()
        val s = side / 100f
        c.save()
        c.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f)
        c.scale(s, s)
        if (folder) drawFolder(c) else drawPage(c)
        c.restore()
    }

    private fun fillPath(c: Canvas, p: Path, color: Int) {
        Shapes.fill.shader = null; Shapes.fill.color = color; c.drawPath(p, Shapes.fill)
    }

    private fun fillRound(c: Canvas, r: RectF, rad: Float, color: Int) {
        Shapes.fill.shader = null; Shapes.fill.color = color; c.drawRoundRect(r, rad, rad, Shapes.fill)
    }

    private fun drawFolder(c: Canvas) {
        // back plate with tab
        Shapes.fill.shader = Shapes.folderBackShader; Shapes.fill.color = Color.WHITE
        c.drawPath(Shapes.folderBack, Shapes.fill)
        Shapes.fill.shader = null
        // white paper peeking out
        fillRound(c, Shapes.paper, 3f, 0xEBFFFFFF.toInt())
        // soft shadow under the front flap
        fillRound(c, RectF(6f, 36.5f, 94f, 40f), 3f, 0x22000000)
        // glossy front plate
        Shapes.fill.shader = Shapes.folderFrontShader; Shapes.fill.color = Color.WHITE
        c.drawRoundRect(Shapes.front, 8f, 8f, Shapes.fill)
        Shapes.fill.shader = null
        // top edge highlight
        Shapes.stroke.shader = null; Shapes.stroke.color = 0x99FFFFFF.toInt(); Shapes.stroke.strokeWidth = 1.6f
        Shapes.stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(13f, 39.6f, 87f, 39.6f, Shapes.stroke)
    }

    private fun drawPage(c: Canvas) {
        // shadow
        c.save(); c.translate(0f, 1.6f); fillPath(c, Shapes.page, 0x26000000); c.restore()
        // page
        Shapes.fill.shader = Shapes.pageShader; Shapes.fill.color = Color.WHITE
        c.drawPath(Shapes.page, Shapes.fill); Shapes.fill.shader = null
        Shapes.stroke.shader = null; Shapes.stroke.color = 0xFFC3C9D2.toInt(); Shapes.stroke.strokeWidth = 1.4f
        c.drawPath(Shapes.page, Shapes.stroke)
        // folded corner
        fillPath(c, Shapes.fold, 0xFFDCE0E6.toInt())
        c.drawPath(Shapes.fold, Shapes.stroke)

        glyph(c)

        if (label.isNotEmpty()) {
            // coloured type label across the lower part of the page
            fillRound(c, Shapes.badge, 5f, kind.color)
            Shapes.stroke.color = 0x55FFFFFF; Shapes.stroke.strokeWidth = 1.2f
            c.drawLine(18f, 61.2f, 68f, 61.2f, Shapes.stroke)
            Shapes.text.textSize = 13f
            val w = Shapes.text.measureText(label)
            val size = 13f * minOf(1f, 50f / w.coerceAtLeast(1f))
            Shapes.text.textSize = size
            c.drawText(label, 43f, 71f + size * 0.35f, Shapes.text)
        }
    }

    private fun glyph(c: Canvas) {
        val col = kind.color
        val soft = (col and 0x00FFFFFF) or 0x33000000
        when (kind) {
            FileIcons.Kind.IMAGE -> {
                Shapes.fill.shader = null; Shapes.fill.color = 0xFFFFC107.toInt()
                c.drawCircle(60f, 36f, 4.2f, Shapes.fill)
                fillPath(c, Shapes.mountains, col)
            }
            FileIcons.Kind.VIDEO -> {
                fillRound(c, RectF(30f, 30f, 70f, 54f), 4f, soft)
                fillPath(c, Shapes.play, col)
            }
            FileIcons.Kind.AUDIO -> {
                Shapes.fill.shader = null; Shapes.fill.color = col
                c.drawCircle(40f, 51f, 5f, Shapes.fill)
                c.drawCircle(58f, 47f, 5f, Shapes.fill)
                c.drawRect(43.5f, 34f, 46f, 51f, Shapes.fill)
                c.drawRect(61.5f, 30f, 64f, 47f, Shapes.fill)
                fillPath(c, Shapes.beam, col)
            }
            FileIcons.Kind.ZIP -> {
                var y = 8f
                var left = true
                while (y < 56f) {
                    fillRound(c, if (left) RectF(45f, y, 50f, y + 4f) else RectF(50f, y, 55f, y + 4f), 1f, col)
                    left = !left; y += 4.5f
                }
                fillRound(c, RectF(45f, 50f, 55f, 58f), 2.5f, col)
            }
            FileIcons.Kind.APK -> {
                Shapes.fill.shader = null; Shapes.fill.color = col
                c.drawArc(Shapes.android, 180f, 180f, true, Shapes.fill)
                Shapes.fill.color = Color.WHITE
                c.drawCircle(45.5f, 47f, 1.5f, Shapes.fill)
                c.drawCircle(54.5f, 47f, 1.5f, Shapes.fill)
                Shapes.stroke.color = col; Shapes.stroke.strokeWidth = 1.6f; Shapes.stroke.strokeCap = Paint.Cap.ROUND
                c.drawLine(42f, 40f, 39f, 35f, Shapes.stroke)
                c.drawLine(58f, 40f, 61f, 35f, Shapes.stroke)
            }
            FileIcons.Kind.EXCEL -> {
                Shapes.stroke.color = col; Shapes.stroke.strokeWidth = 1.5f
                val g = RectF(30f, 30f, 70f, 54f)
                c.drawRect(g, Shapes.stroke)
                c.drawLine(30f, 38f, 70f, 38f, Shapes.stroke); c.drawLine(30f, 46f, 70f, 46f, Shapes.stroke)
                c.drawLine(43f, 30f, 43f, 54f, Shapes.stroke); c.drawLine(57f, 30f, 57f, 54f, Shapes.stroke)
            }
            else -> {
                // text lines
                Shapes.stroke.color = 0xFFC8CED6.toInt(); Shapes.stroke.strokeWidth = 3.2f
                Shapes.stroke.strokeCap = Paint.Cap.ROUND
                val top = if (label.isEmpty()) 36f else 33f
                val gap = if (label.isEmpty()) 12f else 8f
                val n = if (label.isEmpty()) 5 else 3
                for (i in 0 until n) {
                    val y = top + i * gap
                    c.drawLine(31f, y, if (i == n - 1) 55f else 69f, y, Shapes.stroke)
                }
            }
        }
        Shapes.stroke.strokeCap = Paint.Cap.BUTT
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

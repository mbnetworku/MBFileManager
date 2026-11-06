package com.mbnetwork.filemanager

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.w3c.dom.Element
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Turns a Word (.docx) file into a PDF on the phone.
 * Keeps text, bold / italic / underline, headings, lists, simple tables and pictures.
 * Complex layout (columns, text boxes, headers / footers) is simplified.
 */
object DocxToPdf {
    private const val PW = 595          // A4 in points
    private const val PH = 842
    private const val M = 56

    private class Block(val text: CharSequence?, val size: Float, val image: android.graphics.Bitmap?, val gap: Float)

    fun convert(src: File, dst: File): Boolean {
        val doc = PdfDocument()
        try {
            val blocks = ArrayList<Block>()
            ZipFile(src).use { z ->
                val d = OfficeDoc.xml(z, "word/document.xml") ?: return false
                val rels = OfficeDoc.rels(z, "word/_rels/document.xml.rels")
                val body = OfficeDoc.children(d.documentElement, OfficeDoc.W, "body").firstOrNull() ?: return false
                var n = body.firstChild
                while (n != null) {
                    if (n is Element && n.namespaceURI == OfficeDoc.W) when (n.localName) {
                        "p" -> paragraph(z, rels, n, blocks)
                        "tbl" -> for (tr in OfficeDoc.all(n, OfficeDoc.W, "tr")) {
                            val cells = OfficeDoc.children(tr, OfficeDoc.W, "tc").map { tc ->
                                OfficeDoc.children(tc, OfficeDoc.W, "p").joinToString(" ") { OfficeDoc.text(it, OfficeDoc.W) }
                            }
                            blocks += Block(cells.joinToString("   |   "), 10f, null, 4f)
                        }
                    }
                    n = n.nextSibling
                }
            }
            val width = PW - 2 * M
            var pageNo = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create())
            var y = M.toFloat()
            val bottom = (PH - M).toFloat()
            fun newPage() {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create())
                y = M.toFloat()
            }
            for (b in blocks) {
                val c = page.canvas
                if (b.image != null) {
                    val img = b.image
                    var w = img.width.toFloat(); var h = img.height.toFloat()
                    val k = minOf(width / w, (bottom - M) / h, 1f)
                    w *= k; h *= k
                    if (y + h > bottom) newPage()
                    page.canvas.drawBitmap(img, null, RectF(M.toFloat(), y, M + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    y += h + b.gap
                    img.recycle()
                    continue
                }
                val text = b.text ?: continue
                if (text.isEmpty()) { y += b.size * 1.1f; if (y > bottom) newPage(); continue }
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = b.size; color = 0xFF000000.toInt() }
                val lay = StaticLayout(text, tp, width, Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false)
                var line = 0
                while (line < lay.lineCount) {
                    var end = line
                    val top = lay.getLineTop(line)
                    while (end < lay.lineCount && y + (lay.getLineBottom(end) - top) <= bottom) end++
                    if (end == line) { newPage(); continue }       // not even one line fits
                    val h = (lay.getLineBottom(end - 1) - top).toFloat()
                    val cv = page.canvas
                    cv.save()
                    cv.clipRect(M.toFloat(), y, (M + width).toFloat(), y + h)
                    cv.translate(M.toFloat(), y - top)
                    lay.draw(cv)
                    cv.restore()
                    y += h
                    line = end
                    if (line < lay.lineCount) newPage()
                }
                y += b.gap
                if (y > bottom) newPage()
            }
            doc.finishPage(page)
            FileOutputStream(dst).use { doc.writeTo(it) }
            return true
        } catch (e: Throwable) {
            return false
        } finally { doc.close() }
    }

    private fun paragraph(z: ZipFile, rels: Map<String, String>, p: Element, out: MutableList<Block>) {
        val style = OfficeDoc.all(p, OfficeDoc.W, "pStyle").firstOrNull()?.getAttributeNS(OfficeDoc.W, "val") ?: ""
        val heading = style.startsWith("Heading", true) || style.equals("Title", true)
        val level = style.filter { it.isDigit() }.toIntOrNull() ?: 1
        val size = when { style.equals("Title", true) -> 24f; heading -> (20f - 2f * level).coerceAtLeast(13f); else -> 11f }
        val sb = SpannableStringBuilder()
        if (OfficeDoc.all(p, OfficeDoc.W, "numPr").isNotEmpty()) sb.append("•  ")
        for (r in OfficeDoc.all(p, OfficeDoc.W, "r")) {
            val rpr = OfficeDoc.children(r, OfficeDoc.W, "rPr").firstOrNull()
            val bold = heading || (rpr != null && OfficeDoc.children(rpr, OfficeDoc.W, "b").isNotEmpty())
            val ital = rpr != null && OfficeDoc.children(rpr, OfficeDoc.W, "i").isNotEmpty()
            val und = rpr != null && OfficeDoc.children(rpr, OfficeDoc.W, "u").isNotEmpty()
            val start = sb.length
            var c = r.firstChild
            while (c != null) {
                if (c is Element && c.namespaceURI == OfficeDoc.W) when (c.localName) {
                    "t" -> sb.append(c.textContent)
                    "tab" -> sb.append("    ")
                    "br" -> sb.append('\n')
                }
                c = c.nextSibling
            }
            if (sb.length > start) {
                if (bold || ital) sb.setSpan(StyleSpan(if (bold && ital) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (und) sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        out += Block(sb, size, null, if (heading) 8f else 5f)
        for (blip in OfficeDoc.all(p, OfficeDoc.A, "blip")) {
            val id = blip.getAttributeNS(OfficeDoc.R, "embed")
            val target = rels[id] ?: continue
            try {
                val e = z.getEntry(OfficeDoc.resolve("word", target)) ?: continue
                val bmp = z.getInputStream(e).use { BitmapFactory.decodeStream(it) } ?: continue
                out += Block(null, 0f, bmp, 8f)
            } catch (t: Throwable) { }
        }
    }

    /** Converts in the background, then opens the PDF in the viewer. */
    fun start(a: androidx.appcompat.app.AppCompatActivity, src: File) {
        android.widget.Toast.makeText(a, R.string.converting, android.widget.Toast.LENGTH_SHORT).show()
        a.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                var dir = src.parentFile
                if (dir == null || !dir.canWrite()) dir = a.getExternalFilesDir(null) ?: a.cacheDir
                var out = File(dir, src.nameWithoutExtension + ".pdf")
                var n = 2
                while (out.exists()) { out = File(dir, src.nameWithoutExtension + " ($n).pdf"); n++ }
                if (convert(src, out)) out else null
            }
            if (ok != null) {
                android.media.MediaScannerConnection.scanFile(a, arrayOf(ok.path), null, null)
                a.startActivity(android.content.Intent(a, PdfActivity::class.java).putExtra(PdfActivity.EXTRA_PATH, ok.path))
            } else android.widget.Toast.makeText(a, R.string.convert_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

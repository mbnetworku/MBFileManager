package com.mbnetwork.filemanager

import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/** Built-in viewer/editor for Word (.docx), Excel (.xlsx) and PowerPoint (.pptx). */
class OfficeActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var content: FrameLayout
    private var kind = ""
    private var editing = false
    private var dirty = false
    private val d by lazy { resources.displayMetrics.density }
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Word
    private var docx: Document? = null
    private var docEditor: EditText? = null
    // Excel
    private data class Sheet(val name: String, val entry: String, val doc: Document)
    private var sheets = listOf<Sheet>()
    private var shared = listOf<String>()
    private var sheetIndex = 0
    private val changedSheets = mutableSetOf<String>()
    // PowerPoint
    private data class Slide(val entry: String, val doc: Document)
    private var slides = listOf<Slide>()
    private val slideEditors = mutableListOf<EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_office)
        DeviceUi.enterSlide(this)
        file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        kind = OfficeDoc.kind(file.name) ?: "old"
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = file.name
        supportActionBar?.subtitle = when (kind) { "docx" -> "Word"; "xlsx" -> "Excel"; "pptx" -> "PowerPoint"; else -> "" }
        content = findViewById(R.id.content)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeAsk()
        })
        if (kind == "old") { message(getString(R.string.office_old_format)); return }
        load()
    }

    private fun message(t: String) {
        content.removeAllViews()
        content.addView(TextView(this).apply { text = t; gravity = Gravity.CENTER; setPadding(px(32), px(32), px(32), px(32)); textSize = 16f })
    }

    private fun px(v: Int) = (v * d).toInt()

    private fun load() {
        message(getString(R.string.loading))
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    ZipFile(file).use { z ->
                        when (kind) {
                            "docx" -> docx = OfficeDoc.xml(z, "word/document.xml")
                            "xlsx" -> loadXlsx(z)
                            "pptx" -> loadPptx(z)
                        }
                    }
                }
            }
            r.onSuccess { render() }.onFailure { message(getString(R.string.failed, it.message ?: "")) }
        }
    }

    private fun render() {
        when (kind) { "docx" -> renderDocx(); "xlsx" -> renderXlsx(); "pptx" -> renderPptx() }
        invalidateOptionsMenu()
    }

    // ================= WORD =================
    private fun body() = docx?.documentElement?.let { OfficeDoc.children(it, OfficeDoc.W, "body").firstOrNull() }
    private fun paragraphs() = body()?.let { OfficeDoc.all(it, OfficeDoc.W, "p") } ?: emptyList()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun paraHtml(p: Element): String {
        val style = OfficeDoc.all(p, OfficeDoc.W, "pStyle").firstOrNull()?.getAttributeNS(OfficeDoc.W, "val") ?: ""
        val runs = OfficeDoc.all(p, OfficeDoc.W, "r").joinToString("") { r ->
            var t = esc(OfficeDoc.text(r, OfficeDoc.W))
            if (OfficeDoc.all(r, OfficeDoc.W, "tab").isNotEmpty()) t = "&emsp;$t"
            if (OfficeDoc.all(r, OfficeDoc.W, "b").isNotEmpty()) t = "<b>$t</b>"
            if (OfficeDoc.all(r, OfficeDoc.W, "i").isNotEmpty()) t = "<i>$t</i>"
            if (OfficeDoc.all(r, OfficeDoc.W, "u").isNotEmpty()) t = "<u>$t</u>"
            t
        }.ifEmpty { "&nbsp;" }
        val h = Regex("(?i)heading(\\d)").find(style)?.groupValues?.get(1) ?: if (style.equals("Title", true)) "1" else null
        return if (h != null) "<h$h>$runs</h$h>" else "<p>$runs</p>"
    }

    private fun renderDocx() {
        content.removeAllViews()
        if (editing) {
            val e = EditText(this).apply {
                setText(paragraphs().joinToString("\n") { OfficeDoc.text(it, OfficeDoc.W) })
                gravity = Gravity.TOP; background = null; setPadding(px(16), px(16), px(16), px(16))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { dirty = true }
                })
            }
            docEditor = e
            content.addView(card(e, match = true))
            e.requestFocus()
            return
        }
        val b = body() ?: return
        val sb = StringBuilder()
        var n = b.firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == OfficeDoc.W) when (n.localName) {
                "p" -> sb.append(paraHtml(n))
                "tbl" -> {
                    sb.append("<table>")
                    OfficeDoc.children(n, OfficeDoc.W, "tr").forEach { tr ->
                        sb.append("<tr>")
                        OfficeDoc.children(tr, OfficeDoc.W, "tc").forEach { tc ->
                            sb.append("<td>").append(OfficeDoc.children(tc, OfficeDoc.W, "p").joinToString("") { paraHtml(it) }).append("</td>")
                        }
                        sb.append("</tr>")
                    }
                    sb.append("</table>")
                }
            }
            n = n.nextSibling
        }
        val fg = if (dark) "#EDEDED" else "#1B1B1B"; val bg = if (dark) "#141414" else "#FFFFFF"; val line = if (dark) "#333" else "#DDD"
        val html = """<html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:sans-serif;color:$fg;background:$bg;padding:14px;line-height:1.5;word-wrap:break-word}
            p{margin:0 0 8px}h1,h2,h3{color:#1E88E5}table{border-collapse:collapse;width:100%;margin:8px 0}
            td{border:1px solid $line;padding:6px;vertical-align:top}</style></head><body>$sb</body></html>"""
        val web = WebView(this).apply { setBackgroundColor(0) }
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        content.addView(card(web, match = true))
    }

    private fun saveDocx(): Map<String, ByteArray> {
        val doc = docx ?: return emptyMap()
        val lines = docEditor?.text?.toString()?.split("\n") ?: return emptyMap()
        val ps = paragraphs()
        for (i in lines.indices) {
            if (i < ps.size) {
                if (OfficeDoc.text(ps[i], OfficeDoc.W) != lines[i]) OfficeDoc.setParaText(ps[i], OfficeDoc.W, "r", lines[i])
            } else {
                // new paragraph at the end of the body (before section settings)
                val b = body()!!
                val prefix = b.prefix?.let { "$it:" } ?: ""
                val p = doc.createElementNS(OfficeDoc.W, "${prefix}p")
                OfficeDoc.setParaText(p, OfficeDoc.W, "r", lines[i])
                val sect = OfficeDoc.children(b, OfficeDoc.W, "sectPr").firstOrNull()
                if (sect != null) b.insertBefore(p, sect) else b.appendChild(p)
            }
        }
        for (i in lines.size until ps.size) {
            val p = ps[i]
            if (p.parentNode == body()) p.parentNode.removeChild(p) else OfficeDoc.setParaText(p, OfficeDoc.W, "r", "")
        }
        return mapOf("word/document.xml" to OfficeDoc.bytes(doc))
    }

    // ================= EXCEL =================
    private fun loadXlsx(z: ZipFile) {
        shared = OfficeDoc.xml(z, "xl/sharedStrings.xml")?.let { d ->
            OfficeDoc.all(d.documentElement, OfficeDoc.S, "si").map { OfficeDoc.text(it, OfficeDoc.S) }
        } ?: emptyList()
        val wb = OfficeDoc.xml(z, "xl/workbook.xml") ?: return
        val rels = OfficeDoc.rels(z, "xl/_rels/workbook.xml.rels")
        sheets = OfficeDoc.all(wb.documentElement, OfficeDoc.S, "sheet").mapNotNull { s ->
            val target = rels[s.getAttributeNS(OfficeDoc.R, "id")] ?: return@mapNotNull null
            val entry = OfficeDoc.resolve("xl", target)
            OfficeDoc.xml(z, entry)?.let { Sheet(s.getAttribute("name"), entry, it) }
        }
    }

    private fun colIndex(ref: String): Int { var c = 0; for (ch in ref) { if (!ch.isLetter()) break; c = c * 26 + (ch.uppercaseChar() - 'A' + 1) }; return c - 1 }
    private fun rowIndex(ref: String) = (ref.dropWhile { it.isLetter() }.toIntOrNull() ?: 1) - 1
    private fun colName(i: Int): String { var n = i + 1; var s = ""; while (n > 0) { val m = (n - 1) % 26; s = ('A' + m) + s; n = (n - 1) / 26 }; return s }

    private fun cellValue(c: Element): String {
        val t = c.getAttribute("t")
        val v = OfficeDoc.children(c, OfficeDoc.S, "v").firstOrNull()?.textContent ?: ""
        return when (t) {
            "s" -> shared.getOrElse(v.toIntOrNull() ?: -1) { "" }
            "inlineStr" -> OfficeDoc.text(c, OfficeDoc.S)
            "b" -> if (v == "1") "TRUE" else "FALSE"
            else -> v
        }
    }

    private fun renderXlsx() {
        content.removeAllViews()
        if (sheets.isEmpty()) { message(getString(R.string.office_empty)); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = TabLayout(this).apply { tabMode = TabLayout.MODE_SCROLLABLE; setBackgroundColor(0) }
        sheets.forEach { tabs.addTab(tabs.newTab().setText(it.name)) }
        tabs.getTabAt(sheetIndex)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { sheetIndex = t.position; renderXlsx() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        root.addView(tabs)

        val sheet = sheets[sheetIndex]
        val cells = HashMap<Pair<Int, Int>, String>()
        var maxR = 0; var maxC = 0
        OfficeDoc.all(sheet.doc.documentElement, OfficeDoc.S, "c").forEach { c ->
            val ref = c.getAttribute("r"); if (ref.isEmpty()) return@forEach
            val r = rowIndex(ref); val col = colIndex(ref)
            if (r > 500 || col > 50) return@forEach
            cells[r to col] = cellValue(c); maxR = maxOf(maxR, r); maxC = maxOf(maxC, col)
        }
        val rows = maxOf(maxR + 2, 20); val cols = maxOf(maxC + 2, 6)
        val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line = ContextCompat.getColor(this, R.color.row_divider)
        fun cell(text: String, w: Int, header: Boolean, click: (() -> Unit)?) = TextView(this).apply {
            this.text = text; maxLines = 2; textSize = 13f
            setPadding(px(6), px(6), px(6), px(6))
            layoutParams = LinearLayout.LayoutParams(px(w), px(36)).apply { setMargins(0, 0, 1, 1) }
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (header) (if (dark) 0xFF1E2A3A.toInt() else 0xFFE3EEFB.toInt()) else DeviceUi.glass(context, R.color.card_bg))
            if (header) { setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER }
            if (click != null) { isFocusable = true; isClickable = true; setOnClickListener { click() } }
        }
        table.addView(LinearLayout(this).apply {
            addView(cell("", 44, true, null))
            for (c in 0 until cols) addView(cell(colName(c), 110, true, null))
        })
        for (r in 0 until rows) table.addView(LinearLayout(this).apply {
            addView(cell("${r + 1}", 44, true, null))
            for (c in 0 until cols) addView(cell(cells[r to c] ?: "", 110, false) { editCell(sheet, r, c, cells[r to c] ?: "") })
        })
        table.setBackgroundColor(line)
        val scroll = ScrollView(this).apply { addView(HorizontalScrollView(context).apply { addView(table) }) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply { setText(R.string.office_tap_cell); alpha = 0.7f; setPadding(px(12), px(6), px(12), px(6)); textSize = 12f })
        content.addView(root)
    }

    private fun editCell(sheet: Sheet, r: Int, c: Int, current: String) {
        val input = EditText(this).apply { setText(current); setSelection(text.length) }
        val box = FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle("${sheet.name} • ${colName(c)}${r + 1}").setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> setCell(sheet, r, c, input.text.toString()); dirty = true; renderXlsx() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Writes a value into the sheet XML (keeps cell style, removes formula). */
    private fun setCell(sheet: Sheet, r: Int, c: Int, value: String) {
        val doc = sheet.doc
        val data = OfficeDoc.all(doc.documentElement, OfficeDoc.S, "sheetData").firstOrNull() ?: return
        val pfx = data.prefix?.let { "$it:" } ?: ""
        val ref = "${colName(c)}${r + 1}"
        val rowEl = OfficeDoc.children(data, OfficeDoc.S, "row").firstOrNull { it.getAttribute("r") == "${r + 1}" } ?: run {
            val nr = doc.createElementNS(OfficeDoc.S, "${pfx}row").apply { setAttribute("r", "${r + 1}") }
            val after = OfficeDoc.children(data, OfficeDoc.S, "row").firstOrNull { (it.getAttribute("r").toIntOrNull() ?: 0) > r + 1 }
            if (after != null) data.insertBefore(nr, after) else data.appendChild(nr); nr
        }
        val cell = OfficeDoc.children(rowEl, OfficeDoc.S, "c").firstOrNull { it.getAttribute("r") == ref } ?: run {
            val nc = doc.createElementNS(OfficeDoc.S, "${pfx}c").apply { setAttribute("r", ref) }
            val after = OfficeDoc.children(rowEl, OfficeDoc.S, "c").firstOrNull { colIndex(it.getAttribute("r")) > c }
            if (after != null) rowEl.insertBefore(nc, after) else rowEl.appendChild(nc); nc
        }
        while (cell.firstChild != null) cell.removeChild(cell.firstChild)
        if (value.isEmpty()) { cell.removeAttribute("t") }
        else if (value.toDoubleOrNull() != null) {
            cell.removeAttribute("t")
            cell.appendChild(doc.createElementNS(OfficeDoc.S, "${pfx}v").apply { textContent = value })
        } else {
            cell.setAttribute("t", "inlineStr")
            val isEl = doc.createElementNS(OfficeDoc.S, "${pfx}is")
            isEl.appendChild(doc.createElementNS(OfficeDoc.S, "${pfx}t").apply { textContent = value; setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve") })
            cell.appendChild(isEl)
        }
        changedSheets += sheet.entry
    }

    // ================= POWERPOINT =================
    private fun loadPptx(z: ZipFile) {
        val pres = OfficeDoc.xml(z, "ppt/presentation.xml") ?: return
        val rels = OfficeDoc.rels(z, "ppt/_rels/presentation.xml.rels")
        slides = OfficeDoc.all(pres.documentElement, "http://schemas.openxmlformats.org/presentationml/2006/main", "sldId").mapNotNull { s ->
            val target = rels[s.getAttributeNS(OfficeDoc.R, "id")] ?: return@mapNotNull null
            val entry = OfficeDoc.resolve("ppt", target)
            OfficeDoc.xml(z, entry)?.let { Slide(entry, it) }
        }
    }

    private fun slideParas(s: Slide) = OfficeDoc.all(s.doc.documentElement, OfficeDoc.A, "p")

    private fun renderPptx() {
        content.removeAllViews()
        slideEditors.clear()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), px(10), px(10), px(10)) }
        slides.forEachIndexed { i, s ->
            val texts = slideParas(s).map { OfficeDoc.text(it, OfficeDoc.A) }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(14), px(18), px(18)) }
            inner.addView(TextView(this).apply {
                text = getString(R.string.slide_n, i + 1); textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.grad_mid)); setTypeface(typeface, Typeface.BOLD)
            })
            if (editing) {
                val e = EditText(this).apply {
                    setText(texts.joinToString("\n")); background = null
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(x: android.text.Editable?) { dirty = true }
                    })
                }
                slideEditors += e; inner.addView(e)
            } else {
                val nonEmpty = texts.filter { it.isNotBlank() }
                nonEmpty.forEachIndexed { k, t ->
                    inner.addView(TextView(this).apply {
                        text = t; textSize = if (k == 0) 20f else 15f
                        if (k == 0) setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, px(if (k == 0) 6 else 4), 0, 0)
                    })
                }
                if (nonEmpty.isEmpty()) inner.addView(TextView(this).apply { text = "—"; alpha = 0.5f })
            }
            list.addView(card(inner, match = false).apply {
                (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, px(12))
                minimumHeight = px(if (editing) 0 else 160)
            })
        }
        if (slides.isEmpty()) { message(getString(R.string.office_empty)); return }
        content.addView(ScrollView(this).apply { addView(list) })
    }

    private fun savePptx(): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        slides.forEachIndexed { i, s ->
            val lines = slideEditors.getOrNull(i)?.text?.toString()?.split("\n") ?: return@forEachIndexed
            val ps = slideParas(s)
            var changed = false
            ps.forEachIndexed { k, p ->
                val v = when {
                    k < ps.size - 1 -> lines.getOrElse(k) { "" }
                    else -> lines.drop(k).joinToString(" ") // extra lines go into the last paragraph
                }
                if (OfficeDoc.text(p, OfficeDoc.A) != v) { OfficeDoc.setParaText(p, OfficeDoc.A, "r", v); changed = true }
            }
            if (changed) out[s.entry] = OfficeDoc.bytes(s.doc)
        }
        return out
    }

    // ================= common =================
    private fun card(v: View, match: Boolean) = MaterialCardView(this).apply {
        radius = 16 * d
        setCardBackgroundColor(DeviceUi.glass(context, R.color.card_bg))
        cardElevation = 2 * d
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (match) setMargins(px(10), px(10), px(10), px(10)) }
        addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            if (match) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun save(after: (() -> Unit)? = null) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val parts = when (kind) {
                        "docx" -> saveDocx()
                        "pptx" -> savePptx()
                        "xlsx" -> sheets.filter { it.entry in changedSheets }.associate { it.entry to OfficeDoc.bytes(it.doc) }
                        else -> emptyMap()
                    }
                    if (parts.isNotEmpty()) OfficeDoc.save(file, parts)
                }
            }
            r.onSuccess {
                dirty = false; changedSheets.clear()
                Toast.makeText(this@OfficeActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                if (after != null) after() else if (kind != "xlsx") { editing = false; load() }
            }.onFailure { Toast.makeText(this@OfficeActivity, getString(R.string.failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    private fun closeAsk() {
        if (!dirty) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.unsaved_title).setMessage(R.string.unsaved_message)
            .setPositiveButton(R.string.save) { _, _ -> save { finish() } }
            .setNeutralButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (kind == "old") return true
        if (kind != "xlsx") menu.add(0, 1, 0, R.string.edit).setIcon(R.drawable.ic_edit).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); isVisible = !editing
        }
        menu.add(0, 2, 0, R.string.save).setIcon(R.drawable.ic_save).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); isVisible = editing || kind == "xlsx"
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> closeAsk()
            1 -> { editing = true; render() }
            2 -> save()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    companion object { const val EXTRA_PATH = "path" }
}

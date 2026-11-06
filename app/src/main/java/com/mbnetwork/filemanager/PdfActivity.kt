package com.mbnetwork.filemanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * PDF reader with an edit mode (pen, highlighter, text notes).
 * Everything runs on the phone. Pages are drawn with the system PdfRenderer (Android 5+).
 * Saving writes a NEW pdf next to the original; the edited pages are stored as images.
 */
class PdfActivity : AppCompatActivity() {

    private sealed class Mark
    private class Stroke(val pts: ArrayList<Float>, val color: Int, val width: Float, val highlight: Boolean) : Mark()
    private class Note(val x: Float, val y: Float, val text: String, val color: Int, val size: Float) : Mark()

    private lateinit var file: File
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val lock = Any()
    private var pageCount = 0
    private val ratios = ArrayList<Float>()          // page height / width
    private val marks = HashMap<Int, ArrayList<Mark>>()
    private val undo = ArrayList<Pair<Int, Mark>>()

    private lateinit var rv: RecyclerView
    private lateinit var hs: HorizontalScrollView
    private lateinit var pageLabel: TextView
    private lateinit var editBar: View
    private lateinit var topBar: View
    private lateinit var adapter: PageAdapter
    private var zoom = 1f
    private var editing = false
    private var tool = 0                              // 0 pen, 1 highlighter, 2 text
    private var colorIdx = 0
    private var dirty = false
    private val colors = intArrayOf(0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt())
    private val d by lazy { resources.displayMetrics.density }
    private lateinit var scale: ScaleGestureDetector
    private var pinching = false

    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        DeviceUi.enterSlide(this)
        file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF2C2C2E.toInt()))
        try {
            openRenderer(file)
            buildUi()
        } catch (e: SecurityException) {
            askPdfPassword(false)
        } catch (e: Throwable) {
            Toast.makeText(this, R.string.pdf_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun openRenderer(f: File) {
        try { renderer?.close(); pfd?.close() } catch (e: Throwable) { }
        renderer = null; pfd = null
        ratios.clear()
        val p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(p)
            pfd = p; renderer = r
            pageCount = r.pageCount
            for (i in 0 until pageCount) synchronized(lock) {
                r.openPage(i).use { pg -> ratios += pg.height.toFloat() / pg.width }
            }
        } catch (e: Throwable) { try { p.close() } catch (x: Throwable) { }; throw e }
    }

    private var unlocked: File? = null

    /** Password-protected PDF: ask for the password, make an unlocked temporary copy and open that. */
    private fun askPdfPassword(wrong: Boolean) {
        PasswordDialog.ask(this, file.name, wrong, onCancel = { finish() }) { pw ->
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                        val out = File(cacheDir, "unlocked_" + System.currentTimeMillis() + ".pdf")
                        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file, pw).use { doc ->
                            doc.setAllSecurityToBeRemoved(true)
                            doc.save(out)
                        }
                        unlocked = out
                        openRenderer(out)
                        1
                    } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { 0 }
                    catch (e: Throwable) { -1 }
                }
                when (ok) {
                    1 -> buildUi()
                    0 -> askPdfPassword(true)
                    else -> { Toast.makeText(this@PdfActivity, R.string.pdf_failed, Toast.LENGTH_LONG).show(); finish() }
                }
            }
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF2C2C2E.toInt()) }
        adapter = PageAdapter()
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PdfActivity.adapter
            setHasFixedSize(false)
            clipToPadding = false
            setPadding(0, dp(64), 0, dp(90))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) { updateLabel() }
            })
        }
        hs = HorizontalScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isHorizontalScrollBarEnabled = false }
        hs.addView(rv, FrameLayout.LayoutParams(screenW(), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            background = pill(0x99000000.toInt(), 14)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        root.addView(pageLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(18) })

        // top bar
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.scrim(true)
            setPadding(dp(6), dp(4), dp(6), dp(14))
            addView(UiKit.iconButton(context, R.drawable.ic_back_white, getString(R.string.close)) { closeAsk() })
            addView(TextView(context).apply {
                text = file.name; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(UiKit.iconButton(context, R.drawable.ic_edit, getString(R.string.edit)) { setEditing(!editing) })
            addView(UiKit.iconButton(context, R.drawable.ic_share, getString(R.string.share)) { share() })
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // edit bar
        editBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = pill(0xE61C1C1E.toInt(), 26)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(toolBtn("✎") { tool = 0 })
            addView(toolBtn("▮") { tool = 1 })
            addView(toolBtn("T") { tool = 2 })
            addView(UiKit.iconButton(context, R.drawable.ic_undo, getString(R.string.undo)) { undoLast() })
            addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(this@PdfActivity.colors[0]); setStroke(dp(2), Color.WHITE) }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { setMargins(dp(8), 0, dp(8), 0) }
                setOnClickListener {
                    colorIdx = (colorIdx + 1) % colors.size
                    (background as android.graphics.drawable.GradientDrawable).setColor(colors[colorIdx])
                }
                contentDescription = getString(R.string.color)
            })
            addView(UiKit.iconButton(context, R.drawable.ic_save, getString(R.string.save)) { save() })
        }
        root.addView(editBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(52) })
        setContentView(root)
        updateLabel()
        if (intent.getBooleanExtra(EXTRA_EDIT, false)) setEditing(true)

        scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinching = true; return true }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
                setZoom(zoom * detector.scaleFactor)
            }
        })
    }

    private fun screenW() = resources.displayMetrics.widthPixels

    private fun pill(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = r * d
    }

    private fun toolBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick(); refreshTools() }
        tag = "tool"
    }

    private fun refreshTools() {
        val bar = editBar as LinearLayout
        var n = 0
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i)
            if (v.tag == "tool") {
                v.background = if (n == tool) pill(0x66FFFFFF, 22) else null
                n++
            }
        }
    }

    // zoom: pinch (applied when the fingers lift) and ctrl-free buttons are not needed on touch screens
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!editing || tool == 2) scale.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun setZoom(z: Float) {
        val nz = z.coerceIn(1f, 4f)
        if (nz == zoom) return
        zoom = nz
        rv.layoutParams = FrameLayout.LayoutParams((screenW() * zoom).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        adapter.notifyDataSetChanged()
    }

    private fun updateLabel() {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val p = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        pageLabel.text = "${p + 1} / $pageCount"
    }

    private fun setEditing(on: Boolean) {
        editing = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        refreshTools()
        if (on) Toast.makeText(this, R.string.pdf_edit_hint, Toast.LENGTH_LONG).show()
        adapter.notifyDataSetChanged()
    }

    private fun undoLast() {
        val last = undo.removeLastOrNull() ?: return
        marks[last.first]?.remove(last.second)
        adapter.notifyItemChanged(last.first)
    }

    private fun addMark(page: Int, m: Mark) {
        marks.getOrPut(page) { ArrayList() } += m
        undo += page to m
        dirty = true
    }

    private fun askText(page: Int, x: Float, y: Float) {
        val et = EditText(this).apply { hint = getString(R.string.pdf_text_hint); setSingleLine(false) }
        AlertDialog.Builder(this).setTitle(R.string.pdf_add_text).setView(et)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { addMark(page, Note(x, y, t, colors[colorIdx], 0.03f)); adapter.notifyItemChanged(page) }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    // ------------------------------------------------------------------ pages

    private fun renderPage(i: Int, width: Int): Bitmap? = synchronized(lock) {
        try {
            val r = renderer ?: return null
            r.openPage(i).use { p ->
                val w = width.coerceIn(200, 2600)
                val h = (w * p.height.toFloat() / p.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (e: Throwable) { null }
    }

    private inner class PageView(ctx: Context) : View(ctx) {
        var index = -1
        var bmp: Bitmap? = null
        private var cur: Stroke? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = Path()

        override fun onDraw(c: Canvas) {
            val b = bmp
            val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
            if (b != null) c.drawBitmap(b, null, dst, paint) else { paint.color = Color.WHITE; c.drawRect(dst, paint) }
            drawMarks(c, width.toFloat(), marks[index], cur)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!editing || pinching || e.pointerCount > 1) return false
            val nx = e.x / width
            val ny = e.y / height
            if (tool == 2) {
                if (e.actionMasked == MotionEvent.ACTION_UP) askText(index, nx, ny * (height.toFloat() / width))
                return true
            }
            parent.requestDisallowInterceptTouchEvent(true)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cur = Stroke(arrayListOf(nx, ny * height / width), colors[colorIdx], if (tool == 1) 0.028f else 0.004f, tool == 1)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> { cur?.pts?.add(nx); cur?.pts?.add(ny * height / width); invalidate() }
                MotionEvent.ACTION_UP -> { cur?.let { if (it.pts.size >= 4) addMark(index, it) }; cur = null; invalidate() }
                MotionEvent.ACTION_CANCEL -> { cur = null; invalidate() }
            }
            return true
        }
    }

    /** Draws marks; coordinates are stored as fractions of the page width (y also in page widths). */
    private fun drawMarks(c: Canvas, w: Float, list: List<Mark>?, live: Stroke?) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val all = ArrayList<Mark>()
        list?.let { all.addAll(it) }
        live?.let { all.add(it) }
        for (m in all) when (m) {
            is Stroke -> {
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                p.strokeWidth = m.width * w
                p.color = if (m.highlight) (m.color and 0x00FFFFFF) or 0x66000000 else m.color
                val path = Path()
                path.moveTo(m.pts[0] * w, m.pts[1] * w)
                var i = 2
                while (i + 1 < m.pts.size) { path.lineTo(m.pts[i] * w, m.pts[i + 1] * w); i += 2 }
                if (m.pts.size == 4 && m.pts[0] == m.pts[2]) path.lineTo(m.pts[0] * w + 0.1f, m.pts[1] * w)
                c.drawPath(path, p)
            }
            is Note -> {
                p.style = Paint.Style.FILL; p.color = m.color; p.textSize = m.size * w
                var y = m.y * w
                for (line in m.text.split('\n')) { c.drawText(line, m.x * w, y + p.textSize, p); y += p.textSize * 1.2f }
            }
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pageCount
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = PageView(parent.context)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val v = h.itemView as PageView
            val w = (screenW() * zoom).toInt() - dp(16)
            v.index = pos
            v.bmp = null
            val lp = RecyclerView.LayoutParams(w, (w * ratios[pos]).toInt())
            lp.setMargins(dp(8), dp(4), dp(8), dp(4))
            v.layoutParams = lp
            v.invalidate()
            lifecycleScope.launch {
                val b = withContext(Dispatchers.IO) { renderPage(pos, w) }
                if (v.index == pos && b != null) { v.bmp = b; v.invalidate() }
            }
        }
        override fun onViewRecycled(h: RecyclerView.ViewHolder) { (h.itemView as PageView).bmp = null }
    }

    // ------------------------------------------------------------------ save / share

    private fun save() {
        if (!dirty) { Toast.makeText(this, R.string.pdf_nothing, Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, R.string.pdf_saving, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) { writeEdited() }
            if (out != null) {
                dirty = false
                Toast.makeText(this@PdfActivity, getString(R.string.pdf_saved, out.name), Toast.LENGTH_LONG).show()
                android.media.MediaScannerConnection.scanFile(this@PdfActivity, arrayOf(out.path), null, null)
            } else Toast.makeText(this@PdfActivity, R.string.pdf_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun writeEdited(): File? {
        val doc = PdfDocument()
        try {
            val r = renderer ?: return null
            for (i in 0 until pageCount) {
                val dims = synchronized(lock) { r.openPage(i).use { intArrayOf(it.width, it.height) } }
                val pw = dims[0]; val ph = dims[1]
                val bmp = renderPage(i, (pw * 2).coerceAtMost(2000)) ?: return null
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val c = page.canvas
                c.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
                drawMarks(c, pw.toFloat(), marks[i], null)
                doc.finishPage(page)
            }
            var out = File(file.parentFile, file.nameWithoutExtension + "_edited.pdf")
            var n = 2
            while (out.exists()) out = File(file.parentFile, file.nameWithoutExtension + "_edited$n.pdf").also { n++ }
            FileOutputStream(out).use { doc.writeTo(it) }
            return out
        } catch (e: Throwable) {
            return null
        } finally { doc.close() }
    }

    private fun share() {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.share)))
    }

    private fun closeAsk() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this).setMessage(R.string.pdf_unsaved)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton(R.string.discard) { _, _ -> dirty = false; finish() }
            .setNeutralButton(android.R.string.cancel, null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { closeAsk() }

    override fun onDestroy() {
        try { renderer?.close(); pfd?.close() } catch (e: Throwable) { }
        unlocked?.delete()
        super.onDestroy()
    }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_EDIT = "edit"
        fun isPdf(name: String) = name.endsWith(".pdf", ignoreCase = true)
    }
}

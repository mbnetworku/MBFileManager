package com.mbnetwork.filemanager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Photo editor: crop, rotate, flip, filters and brightness / contrast / saturation. Save as a copy or replace. */
class PhotoEditorActivity : AppCompatActivity() {

    private class Preset(val label: Int, val matrix: FloatArray?)

    private lateinit var canvas: EditorCanvas
    private lateinit var panel: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var src: File
    private var bmp: Bitmap? = null
    private val undo = ArrayDeque<Bitmap>()

    private var brightness = 0f      // -80 .. 80
    private var contrast = 1f        // 0.5 .. 1.5
    private var saturation = 1f      // 0 .. 2
    private var presetIndex = 0
    private var tool = 0             // 0 crop, 1 rotate, 2 filters, 3 adjust
    private var changed = false
    private var enhance: ColorMatrix? = null     // "Auto enhance" result
    private var transparent = false              // background removed: save as PNG
    private var wandOn = false
    private var wandTol = 35

    private val presets = listOf(
        Preset(R.string.f_original, null),
        Preset(R.string.f_mono, floatArrayOf(0.33f, 0.59f, 0.11f, 0f, 0f, 0.33f, 0.59f, 0.11f, 0f, 0f, 0.33f, 0.59f, 0.11f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        Preset(R.string.f_noir, floatArrayOf(0.43f, 0.77f, 0.14f, 0f, -40f, 0.43f, 0.77f, 0.14f, 0f, -40f, 0.43f, 0.77f, 0.14f, 0f, -40f, 0f, 0f, 0f, 1f, 0f)),
        Preset(R.string.f_sepia, floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        Preset(R.string.f_vivid, floatArrayOf(1.5f, -0.25f, -0.25f, 0f, 0f, -0.25f, 1.5f, -0.25f, 0f, 0f, -0.25f, -0.25f, 1.5f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        Preset(R.string.f_cool, floatArrayOf(0.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.15f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)),
        Preset(R.string.f_warm, floatArrayOf(1.15f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.88f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        Preset(R.string.f_fade, floatArrayOf(0.85f, 0f, 0f, 0f, 40f, 0f, 0.85f, 0f, 0f, 40f, 0f, 0f, 0.85f, 0f, 40f, 0f, 0f, 0f, 1f, 0f)),
        Preset(R.string.f_invert, floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        DeviceUi.enterSlide(this)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        src = File(path)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        canvas = EditorCanvas(this)
        canvas.onWandTap = { x, y -> wandCut(x, y) }
        root.addView(topBar())
        root.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel = FrameLayout(this).apply { setPadding(UiKit.dp(context, 12), UiKit.dp(context, 8), UiKit.dp(context, 12), UiKit.dp(context, 4)) }
        root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tabs = LinearLayout(this).apply { setBackgroundColor(0xFF111111.toInt()) }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) { runCatching { ImageUtil.decode(src.absolutePath, 2400) }.getOrNull() }
            if (b == null) { Toast.makeText(this@PhotoEditorActivity, R.string.photo_failed, Toast.LENGTH_LONG).show(); finish(); return@launch }
            bmp = b
            canvas.bitmap = b
            selectTool(0)
        }
    }

    private fun topBar(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(UiKit.dp(context, 6), UiKit.dp(context, 4), UiKit.dp(context, 10), UiKit.dp(context, 4))
        addView(UiKit.iconButton(context, R.drawable.ic_close, getString(R.string.cancel)) { closeAsk() })
        addView(UiKit.iconButton(context, R.drawable.ic_undo, getString(R.string.undo)) { undoStep() })
        addView(android.view.View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(UiKit.pill(context, getString(R.string.save), selected = true) { askSave() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        })
    }

    // ------------------------------------------------------------------ tools

    private fun selectTool(i: Int) {
        tool = i
        if (i != 4) wandOn = false
        canvas.wandMode = i == 4 && wandOn
        canvas.cropping = i == 0
        tabs.removeAllViews()
        tabs.addView(UiKit.toolTab(this, R.drawable.ic_crop, getString(R.string.crop), i == 0) { selectTool(0) })
        tabs.addView(UiKit.toolTab(this, R.drawable.ic_rotate_right, getString(R.string.rotate), i == 1) { selectTool(1) })
        tabs.addView(UiKit.toolTab(this, R.drawable.ic_image, getString(R.string.filters), i == 2) { selectTool(2) })
        tabs.addView(UiKit.toolTab(this, R.drawable.ic_tune, getString(R.string.adjust), i == 3) { selectTool(3) })
        tabs.addView(UiKit.toolTab(this, R.drawable.ic_magic, getString(R.string.ai), i == 4) { selectTool(4) })
        panel.removeAllViews()
        panel.addView(when (i) { 0 -> cropPanel(); 1 -> rotatePanel(); 2 -> filterPanel(); 3 -> adjustPanel(); else -> aiPanel() })
    }

    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun cropPanel(): HorizontalScrollView {
        val r = row()
        val ratios = listOf(R.string.free to 0f, R.string.r_square to 1f, R.string.r_43 to 4f / 3f, R.string.r_169 to 16f / 9f, R.string.r_34 to 3f / 4f)
        ratios.forEach { (label, a) ->
            r.addView(UiKit.pill(this, getString(label), canvas.aspect == a) { canvas.aspect = a; selectTool(0) })
        }
        r.addView(UiKit.pill(this, getString(R.string.apply), true) { applyCrop() })
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    private fun rotatePanel(): LinearLayout {
        val r = row().apply { gravity = Gravity.CENTER }
        r.addView(UiKit.iconButton(this, R.drawable.ic_rotate_left, getString(R.string.rotate)) { transform { ImageUtil.rotate(it, -90f) } })
        r.addView(UiKit.iconButton(this, R.drawable.ic_rotate_right, getString(R.string.rotate)) { transform { ImageUtil.rotate(it, 90f) } })
        r.addView(UiKit.iconButton(this, R.drawable.ic_swap_h, getString(R.string.flip)) { transform { ImageUtil.flip(it, true) } })
        r.addView(UiKit.iconButton(this, R.drawable.ic_swap_v, getString(R.string.flip)) { transform { ImageUtil.flip(it, false) } })
        return r
    }

    private fun filterPanel(): HorizontalScrollView {
        val r = row()
        presets.forEachIndexed { i, p ->
            r.addView(UiKit.pill(this, getString(p.label), i == presetIndex) { presetIndex = i; updatePreview(); selectTool(2) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    private fun adjustPanel(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun slider(label: Int, from: Float, to: Float, value: Float, onChange: (Float) -> Unit) {
            col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = getString(label); textSize = 13f; setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(UiKit.dp(context, 92), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(Slider(context).apply {
                    valueFrom = from; valueTo = to; this.value = value
                    addOnChangeListener { _, v, _ -> onChange(v); updatePreview() }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }
        slider(R.string.brightness, -80f, 80f, brightness) { brightness = it }
        slider(R.string.contrast, 0.5f, 1.5f, contrast) { contrast = it }
        slider(R.string.saturation, 0f, 2f, saturation) { saturation = it }
        return col
    }

    // ------------------------------------------------------------------ smart (on-device) tools

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun aiPanel(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r = row()
        r.addView(UiKit.pill(this, getString(R.string.ai_enhance), enhance != null) { toggleEnhance() })
        r.addView(UiKit.pill(this, getString(R.string.ai_smart_crop)) { smartCrop() })
        r.addView(UiKit.pill(this, getString(R.string.ai_blur_faces)) { blurFaces() })
        r.addView(UiKit.pill(this, getString(R.string.ai_portrait)) { portraitBlur() })
        r.addView(UiKit.pill(this, getString(R.string.ai_remove_bg)) { removeBackground() })
        r.addView(UiKit.pill(this, getString(R.string.ai_wand), wandOn) {
            wandOn = !wandOn
            if (wandOn) toast(R.string.ai_wand_hint)
            selectTool(4)
        })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
        if (wandOn) col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = getString(R.string.ai_tolerance); textSize = 13f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(UiKit.dp(context, 92), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(Slider(context).apply {
                valueFrom = 8f; valueTo = 90f; value = wandTol.toFloat()
                addOnChangeListener { _, v, _ -> wandTol = v.toInt() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.ai_note); textSize = 11f; setTextColor(0x99FFFFFF.toInt())
            setPadding(0, UiKit.dp(context, 6), 0, 0)
        })
        return col
    }

    private fun toggleEnhance() {
        val b = bmp ?: return
        if (enhance != null) { enhance = null; updatePreview(); selectTool(4); return }
        lifecycleScope.launch {
            val m = withContext(Dispatchers.Default) { runCatching { SmartTools.autoEnhance(b) }.getOrNull() }
            if (m != null) { enhance = m; updatePreview(); toast(R.string.ai_enhanced) }
            selectTool(4)
        }
    }

    private fun smartCrop() {
        val b = bmp ?: return
        val aspect = canvas.aspect
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) {
                runCatching { val faces = SmartTools.detectFaces(b); SmartTools.smartCrop(b, faces, aspect) to faces.isNotEmpty() }.getOrNull()
            }
            if (res == null) { toast(R.string.photo_failed); return@launch }
            val (rect, hadFaces) = res
            if (rect.width() < 2 || rect.height() < 2) return@launch
            transform { Bitmap.createBitmap(it, rect.left, rect.top, rect.width(), rect.height()) }
            toast(if (hadFaces) R.string.ai_cropped_faces else R.string.ai_cropped_area)
        }
    }

    private fun blurFaces() {
        val b = bmp ?: return
        lifecycleScope.launch {
            val faces = withContext(Dispatchers.Default) { runCatching { SmartTools.detectFaces(b) }.getOrDefault(emptyList()) }
            if (faces.isEmpty()) { toast(R.string.ai_no_faces); return@launch }
            val out = withContext(Dispatchers.Default) { runCatching { SmartTools.pixelateFaces(b, faces) }.getOrNull() }
            if (out != null) transform { out }
        }
    }

    /** Automatic cut-out of the background (on the phone). Saved as PNG to keep the transparency. */
    private fun removeBackground() {
        val b = bmp ?: return
        toast(R.string.saving)
        lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) {
                try { BgRemover.auto(b, SmartTools.detectFaces(b)) } catch (t: Throwable) { null }
            }
            if (out == null) { toast(R.string.photo_failed); return@launch }
            transparent = true
            transform { out }
            toast(R.string.ai_bg_removed)
        }
    }

    /** Magic wand: remove the colour area that was tapped. */
    private fun wandCut(x: Int, y: Int) {
        val b = bmp ?: return
        lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) { runCatching { BgRemover.byColor(b, x, y, wandTol) }.getOrNull() }
            if (out == null) { toast(R.string.photo_failed); return@launch }
            transparent = true
            transform { out }
        }
    }

    private fun portraitBlur() {
        val b = bmp ?: return
        lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) {
                try { SmartTools.portraitBlur(b, SmartTools.detectFaces(b)) } catch (t: Throwable) { null }
            }
            if (out != null) transform { out } else toast(R.string.photo_failed)
        }
    }

    /** filter + adjustments as one colour matrix */
    private fun buildMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        enhance?.let { cm.postConcat(it) }
        cm.postConcat(ColorMatrix().apply { setSaturation(saturation) })
        val t = (1f - contrast) * 127.5f + brightness
        cm.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f)))
        presets[presetIndex].matrix?.let { cm.postConcat(ColorMatrix(it)) }
        return cm
    }

    private fun updatePreview() {
        changed = true
        canvas.previewFilter = ColorMatrixColorFilter(buildMatrix())
    }

    // ------------------------------------------------------------------ edits

    private fun push() { bmp?.let { undo.addLast(it); if (undo.size > 6) undo.removeFirst() } }

    private fun transform(op: (Bitmap) -> Bitmap) {
        val b = bmp ?: return
        push()
        val nb = op(b)
        bmp = nb; canvas.bitmap = nb; changed = true
    }

    private fun applyCrop() {
        val r = canvas.cropInBitmap() ?: return
        val b = bmp ?: return
        if (r.width() >= b.width && r.height() >= b.height) return
        transform { Bitmap.createBitmap(it, r.left, r.top, r.width(), r.height()) }
        canvas.resetCrop()
    }

    private fun undoStep() {
        val prev = undo.removeLastOrNull() ?: return
        bmp = prev; canvas.bitmap = prev
        if (tool == 0) canvas.resetCrop()
    }

    // ------------------------------------------------------------------ save

    private fun askSave() {
        val ext = MediaTypes.ext(src.name)
        val canReplace = ext in setOf("jpg", "jpeg", "png") && src.canWrite() && (!transparent || ext == "png")
        val items = if (canReplace) arrayOf(getString(R.string.save_copy), getString(R.string.replace_original)) else arrayOf(getString(R.string.save_copy))
        MaterialAlertDialogBuilder(this).setTitle(R.string.save)
            .setItems(items) { _, i -> save(replace = i == 1) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun save(replace: Boolean) {
        val b = bmp ?: return
        val matrix = buildMatrix()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                    Canvas(out).drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
                    val png = MediaTypes.ext(src.name) == "png" || transparent
                    val target = if (replace && (!transparent || MediaTypes.ext(src.name) == "png")) src
                        else ZipUtil.uniqueName(src.parentFile!!, src.nameWithoutExtension + "_edited", if (png) ".png" else ".jpg")
                    FileOutputStream(target).use { out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
                    target
                }
            }
            r.onSuccess {
                MediaScannerConnection.scanFile(this@PhotoEditorActivity, arrayOf(it.path), null, null)
                Toast.makeText(this@PhotoEditorActivity, getString(R.string.saved_as, it.name), Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure { Toast.makeText(this@PhotoEditorActivity, getString(R.string.failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    private fun closeAsk() {
        if (!changed) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.unsaved_title).setMessage(R.string.edit_discard)
            .setPositiveButton(R.string.save) { _, _ -> askSave() }
            .setNeutralButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { closeAsk() }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    companion object { const val EXTRA_PATH = "path" }
}

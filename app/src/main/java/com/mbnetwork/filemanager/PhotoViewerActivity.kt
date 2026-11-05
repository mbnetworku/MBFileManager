package com.mbnetwork.filemanager

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Photo viewer: swipe between the photos of the folder, pinch / double-tap to zoom, edit, share, delete. */
class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var zoom: ZoomImageView
    private lateinit var topBar: View
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private var files = mutableListOf<File>()
    private var index = 0
    private var barsOn = true
    private var job: Job? = null

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val cur = files.getOrNull(index)
        loadSiblings(cur ?: return@registerForActivityResult)
        show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        DeviceUi.enterSlide(this)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        loadSiblings(File(path))
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        zoom = ZoomImageView(this).apply {
            onSingleTap = { toggleBars() }
            onSwipe = { dir -> go(dir) }
        }
        root.addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        title = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        sub = TextView(this).apply { textSize = 12f; setTextColor(0xCCFFFFFF.toInt()) }
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.scrim(true)
            setPadding(UiKit.dp(context, 6), UiKit.dp(context, 4), UiKit.dp(context, 6), UiKit.dp(context, 16))
            addView(UiKit.iconButton(context, R.drawable.ic_back_white, getString(R.string.close)) { finish() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(context, 6), 0, UiKit.dp(context, 6), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(title); addView(sub)
            })
            addView(UiKit.iconButton(context, R.drawable.ic_edit, getString(R.string.edit_photo)) { editCurrent() })
            addView(UiKit.iconButton(context, R.drawable.ic_share, getString(R.string.share)) { shareCurrent() })
            addView(UiKit.iconButton(context, R.drawable.ic_delete, getString(R.string.delete)) { deleteCurrent() })
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        show()
    }

    private fun loadSiblings(cur: File) {
        files = (cur.parentFile?.listFiles() ?: emptyArray()).filter { it.isFile && MediaTypes.isImage(it.name) }
            .sortedBy { it.name.lowercase() }.toMutableList()
        if (files.isEmpty()) files = mutableListOf(cur)
        index = files.indexOfFirst { it.absolutePath == cur.absolutePath }.coerceAtLeast(0)
    }

    private fun show() {
        val f = files.getOrNull(index) ?: run { finish(); return }
        title.text = f.name
        sub.text = "${index + 1} / ${files.size}  •  " + Formatter.formatShortFileSize(this, f.length())
        job?.cancel()
        job = lifecycleScope.launch {
            val bm = withContext(Dispatchers.IO) { runCatching { ImageUtil.decode(f.absolutePath, 2048) }.getOrNull() }
            if (bm == null) { cannotShow(f); return@launch }
            zoom.alpha = 0f
            zoom.setImageBitmap(bm)
            zoom.animate().alpha(1f).setDuration(180).start()
        }
    }

    private var failedShown = false

    /** The format cannot be decoded here (for example HEIC before Android 9): offer another app. */
    private fun cannotShow(f: File) {
        if (failedShown) { Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show(); return }
        failedShown = true
        MaterialAlertDialogBuilder(this).setTitle(R.string.photo_failed).setMessage(R.string.photo_external)
            .setPositiveButton(R.string.open_with) { _, _ ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                try {
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, MediaTypes.mime(f.name))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.open_with)))
                } catch (e: Exception) { Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun go(dir: Int) {
        val n = index + dir
        if (n in files.indices) { index = n; show() }
        else zoom.animate().translationX(-dir * 30f).setDuration(90).withEndAction { zoom.animate().translationX(0f).setDuration(120).start() }.start()
    }

    private fun toggleBars() {
        barsOn = !barsOn
        topBar.animate().alpha(if (barsOn) 1f else 0f).setDuration(180).withEndAction {
            topBar.visibility = if (barsOn) View.VISIBLE else View.GONE
        }.start()
        if (barsOn) topBar.visibility = View.VISIBLE
    }

    private fun editCurrent() {
        val f = files.getOrNull(index) ?: return
        editLauncher.launch(Intent(this, PhotoEditorActivity::class.java).putExtra(PhotoEditorActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun shareCurrent() {
        val f = files.getOrNull(index) ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(MediaTypes.mime(f.name)).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.share)))
    }

    private fun deleteCurrent() {
        val f = files.getOrNull(index) ?: return
        val toBin = Trash.canTrash(this, f.absolutePath)
        MaterialAlertDialogBuilder(this).setTitle(R.string.delete)
            .setMessage(getString(if (toBin) R.string.delete_bin_confirm else R.string.delete_confirm, f.name))
            .setPositiveButton(if (toBin) R.string.move_to_bin else R.string.delete_forever) { _, _ ->
                val ok = try {
                    if (toBin && f.canWrite()) { Trash.moveToTrash(applicationContext, f.absolutePath); true } else f.delete()
                } catch (e: Exception) { false }
                if (ok) {
                    files.removeAt(index)
                    if (files.isEmpty()) finish() else { index = index.coerceAtMost(files.size - 1); show() }
                } else Toast.makeText(this, getString(R.string.failed, f.name), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    companion object { const val EXTRA_PATH = "path" }
}

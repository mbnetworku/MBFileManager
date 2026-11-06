package com.mbnetwork.filemanager

import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * "Save to File Manager": other apps (Google Photos, Samsung Gallery, Chrome ...) send photos / files here with Share.
 * Pick any folder on the phone, SD card or USB drive, and the files are copied there.
 */
class ShareReceiverActivity : AppCompatActivity() {

    private var uris: List<Uri> = emptyList()
    private val d by lazy { resources.displayMetrics.density }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)

        val list = ArrayList<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { list += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list += it }
        }
        if (list.isEmpty()) intent?.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { list += it } }
        uris = list.distinct()
        if (uris.isEmpty()) { Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_LONG).show(); finish(); return }

        val col = { r: Int -> ContextCompat.getColor(this, r) }
        val summary = TextView(this).apply {
            text = getString(R.string.share_received, uris.size); textSize = 20f; setTextColor(col(R.color.label)); gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = getString(R.string.share_hint); textSize = 14f; setTextColor(col(R.color.text_secondary)); gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, (24 * d).toInt())
        }
        val choose = MaterialButton(this).apply { text = getString(R.string.share_choose); setOnClickListener { pick() } }
        val cancel = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.cancel); setOnClickListener { finish() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding((32 * d).toInt(), 0, (32 * d).toInt(), 0)
            addView(summary); addView(sub); addView(choose); addView(cancel)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(col(R.color.window_bg))
            addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })
        if (savedInstanceState == null) pick()
    }

    private fun pick() {
        val first = uris.firstOrNull()
        val img = first?.let { contentResolver.getType(it)?.startsWith("image") } ?: false
        val start = File(Environment.getExternalStorageDirectory(), if (img) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS)
        if (!start.exists()) start.mkdirs()
        FolderPicker.show(this, start.path, getString(R.string.share_choose)) { save(File(it)) }
    }

    private fun nameOf(u: Uri): String {
        var n: String? = null
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) n = c.getString(i) }
            }
        } catch (e: Throwable) { }
        val ext = (contentResolver.getType(u) ?: "").substringAfter('/', "").substringBefore('+').substringBefore(';')
        return (n ?: u.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}")
            .let { if (!it.contains('.') && ext.isNotEmpty() && ext.length < 6) "$it.$ext" else it }.replace('/', '_')
    }

    private fun save(dest: File) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = uris.size }
        val holder = FrameLayout(this).apply { setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (8 * d).toInt()); addView(bar) }
        val dlg = MaterialAlertDialogBuilder(this).setTitle(R.string.share_saving).setView(holder).setCancelable(false).show()
        lifecycleScope.launch {
            val saved = ArrayList<String>()
            val failed = withContext(Dispatchers.IO) {
                var bad = 0
                dest.mkdirs()
                uris.forEachIndexed { i, u ->
                    try {
                        val t = ZipUtil.uniqueName(dest, nameOf(u).substringBeforeLast('.', nameOf(u)),
                            nameOf(u).let { if (it.contains('.')) "." + it.substringAfterLast('.') else "" })
                        contentResolver.openInputStream(u)!!.use { inp -> FileOutputStream(t).use { inp.copyTo(it) } }
                        saved += t.path
                    } catch (e: Throwable) { bad++ }
                    bar.post { bar.progress = i + 1 }
                }
                bad
            }
            dlg.dismiss()
            if (saved.isNotEmpty()) MediaScannerConnection.scanFile(this@ShareReceiverActivity, saved.toTypedArray(), null, null)
            MaterialAlertDialogBuilder(this@ShareReceiverActivity)
                .setMessage(getString(R.string.share_saved, saved.size, dest.path) + if (failed > 0) "\n" + getString(R.string.share_failed, failed) else "")
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { _, _ -> finish() }.show()
        }
    }
}

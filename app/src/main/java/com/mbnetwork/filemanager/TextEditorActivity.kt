package com.mbnetwork.filemanager

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Built-in notepad: read and edit text files directly inside the File Explorer. */
class TextEditorActivity : AppCompatActivity() {

    private lateinit var editor: EditText
    private lateinit var file: File
    private var root = false
    private var editing = false
    private var dirty = false
    private var loading = true
    private var wrapOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text)
        DeviceUi.enterSlide(this)
        file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        root = intent.getBooleanExtra(EXTRA_ROOT, false)
        editing = intent.getBooleanExtra(EXTRA_EDIT, false)

        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = file.name
        supportActionBar?.subtitle = file.parent

        editor = findViewById(R.id.editor)
        editor.typeface = Typeface.MONOSPACE
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!loading) dirty = true }
        })
        setEditing(editing)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeAsk()
        })
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val readable = file.canRead()
                    when {
                        readable && file.length() > MAX -> file.inputStream().use { val b = ByteArray(MAX); var n = 0; while (n < MAX) { val r = it.read(b, n, MAX - n); if (r < 0) break; n += r }; String(b, 0, n) } + "\n\n[… file too large, showing first 2 MB]"
                        readable -> file.readText()
                        root -> RootShell.run("cat ${RootShell.q(file.path)}").out
                        else -> throw java.io.IOException("Cannot read file")
                    }
                }
            }
            loading = true
            r.onSuccess { editor.setText(it) }.onFailure {
                Toast.makeText(this@TextEditorActivity, getString(R.string.failed, it.message ?: ""), Toast.LENGTH_LONG).show()
            }
            loading = false
            dirty = false
        }
    }

    private fun save(after: (() -> Unit)? = null) {
        val text = editor.text.toString()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (file.exists() && !file.canWrite() && root) {
                        val tmp = File(cacheDir, "edit.tmp").apply { writeText(text) }
                        val res = RootShell.run("cat ${RootShell.q(tmp.path)} > ${RootShell.q(file.path)}")
                        tmp.delete()
                        if (!res.ok) throw java.io.IOException(res.err)
                    } else file.writeText(text)
                }
            }
            r.onSuccess {
                dirty = false
                Toast.makeText(this@TextEditorActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                after?.invoke()
            }.onFailure {
                Toast.makeText(this@TextEditorActivity, getString(R.string.failed, it.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setEditing(on: Boolean) {
        editing = on
        editor.isFocusable = on
        editor.isFocusableInTouchMode = on
        editor.isCursorVisible = on
        editor.setTextIsSelectable(true)
        if (on) editor.requestFocus()
        invalidateOptionsMenu()
    }

    private fun closeAsk() {
        if (!dirty) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_message)
            .setPositiveButton(R.string.save) { _, _ -> save { finish() } }
            .setNeutralButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.edit).setIcon(R.drawable.ic_edit).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); isVisible = !editing
        }
        menu.add(0, 2, 0, R.string.save).setIcon(R.drawable.ic_save).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); isVisible = editing
        }
        menu.add(0, 3, 0, R.string.word_wrap).setCheckable(true).isChecked = !wrapOff
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> closeAsk()
            1 -> setEditing(true)
            2 -> save()
            3 -> { wrapOff = !wrapOff; editor.setHorizontallyScrolling(wrapOff); item.isChecked = !wrapOff }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_ROOT = "root"
        const val EXTRA_EDIT = "edit"
        private const val MAX = 2 * 1024 * 1024
        val TEXT_EXT = setOf("txt", "log", "md", "json", "xml", "csv", "ini", "conf", "cfg", "prop", "properties",
            "sh", "bat", "ps1", "yml", "yaml", "html", "htm", "css", "js", "kt", "java", "py", "c", "cpp", "h", "rc", "srt", "nfo")
        fun isText(name: String) = name.substringAfterLast('.', "").lowercase() in TEXT_EXT
    }
}

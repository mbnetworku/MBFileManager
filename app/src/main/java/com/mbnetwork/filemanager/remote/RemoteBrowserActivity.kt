package com.mbnetwork.filemanager.remote

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mbnetwork.filemanager.ArchiveUtil
import com.mbnetwork.filemanager.DeviceUi
import com.mbnetwork.filemanager.FileIcons
import com.mbnetwork.filemanager.FileItem
import com.mbnetwork.filemanager.MediaTypes
import com.mbnetwork.filemanager.MusicPlayer
import com.mbnetwork.filemanager.OfficeActivity
import com.mbnetwork.filemanager.OfficeDoc
import com.mbnetwork.filemanager.OfficeUnlock
import com.mbnetwork.filemanager.PasswordDialog
import com.mbnetwork.filemanager.PdfActivity
import com.mbnetwork.filemanager.PhotoViewerActivity
import com.mbnetwork.filemanager.PlayerActivity
import com.mbnetwork.filemanager.Prefs
import com.mbnetwork.filemanager.R
import com.mbnetwork.filemanager.TextEditorActivity
import com.mbnetwork.filemanager.VideoPlayerActivity
import com.mbnetwork.filemanager.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Browse one server or cloud drive: open, download, upload, new folder, rename, delete. */
class RemoteBrowserActivity : AppCompatActivity() {

    private lateinit var conn: Conn
    private var fs: RemoteFs? = null
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()   // one operation at a time (FTP / SSH need that)
    private val stack = ArrayList<Pair<String, String>>()                         // (path or id, folder name)
    private var entries: List<RemoteEntry> = emptyList()
    private lateinit var rv: RecyclerView
    private lateinit var busy: ProgressBar
    private lateinit var empty: TextView
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) upload(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        DeviceUi.enterSlide(this)
        conn = ConnStore.get(this, intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = conn.name

        val content = findViewById<FrameLayout>(R.id.content)
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = Adapter()
            clipToPadding = false; setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        busy = ProgressBar(this).apply { visibility = View.GONE }
        empty = TextView(this).apply { text = getString(R.string.folder_empty); visibility = View.GONE; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) }
        content.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        content.addView(busy, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { up() }
        })
        cleanCache()
        connect()
    }

    private fun cacheDir2() = File(cacheDir, "remote").also { it.mkdirs() }

    private fun cleanCache() {
        val old = System.currentTimeMillis() - 12 * 3600_000L
        cacheDir2().listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }

    // ------------------------------------------------------------------ connect / list

    private fun connect(password: String? = null) {
        var pw = password ?: Secrets.dec(conn.pass)
        if (!conn.isCloud && conn.user.isNotEmpty() && pw.isEmpty() && password == null) {
            PasswordDialog.ask(this, conn.name, false, onCancel = { finish() }) { connect(it) }
            return
        }
        busy.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val f = withContext(io) { Fs.open(applicationContext, conn, pw) }
                fs = f
                stack.clear(); stack += f.root to conn.name
                load()
            } catch (t: Throwable) {
                busy.visibility = View.GONE
                MaterialAlertDialogBuilder(this@RemoteBrowserActivity).setTitle(conn.name)
                    .setMessage(friendly(t)).setCancelable(false)
                    .setPositiveButton(R.string.ok) { _, _ -> finish() }.show()
            }
        }
    }

    private fun friendly(t: Throwable): String {
        val m = t.message ?: t.javaClass.simpleName
        return getString(R.string.conn_failed, m)
    }

    private fun load() {
        val f = fs ?: return
        val dir = stack.last().first
        busy.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val list = withContext(io) { f.list(dir) }
                entries = list.sortedWith(compareBy<RemoteEntry>({ !it.isDir }, { it.name.lowercase() }))
                rv.adapter?.notifyDataSetChanged()
                empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                supportActionBar?.subtitle = stack.drop(1).joinToString(" / ") { it.second }.ifEmpty { null }
            } catch (t: Throwable) {
                Toast.makeText(this@RemoteBrowserActivity, friendly(t), Toast.LENGTH_LONG).show()
            }
            busy.visibility = View.GONE
        }
    }

    private fun up() {
        if (stack.size > 1) { stack.removeAt(stack.size - 1); load() } else finish()
    }

    override fun onSupportNavigateUp(): Boolean { up(); return true }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.upload_files).setIcon(R.drawable.ic_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 1, R.string.new_folder).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 3, 2, R.string.refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> picker.launch("*/*")
            2 -> askName(R.string.new_folder, "") { n -> op(R.string.done) { fs!!.mkdir(stack.last().first, n) } }
            3 -> load()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ actions

    private fun askName(title: Int, initial: String, ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSingleLine(); selectAll() }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && !n.contains('/')) ok(n)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /** Runs one change on the server, then reloads the list. */
    private fun op(okMsg: Int, work: () -> Unit) {
        busy.visibility = View.VISIBLE
        lifecycleScope.launch {
            val err = withContext(io) { try { work(); null } catch (t: Throwable) { t } }
            if (err == null) Toast.makeText(this@RemoteBrowserActivity, okMsg, Toast.LENGTH_SHORT).show()
            else Toast.makeText(this@RemoteBrowserActivity, friendly(err), Toast.LENGTH_LONG).show()
            load()
        }
    }

    private fun click(e: RemoteEntry) {
        if (e.isDir) { stack += e.path to e.name; load(); return }
        val dest = File(cacheDir2(), e.name.replace('/', '_'))
        download(e, dest) { openLocal(it) }
    }

    private fun menu(e: RemoteEntry) {
        val items = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        if (!e.isDir) {
            items += getString(R.string.open); acts += { click(e) }
            items += getString(R.string.download_to_phone); acts += { saveToPhone(e) }
        }
        items += getString(R.string.rename); acts += { askName(R.string.rename, e.name) { n -> op(R.string.done) { fs!!.rename(e, n) } } }
        items += getString(R.string.delete); acts += {
            MaterialAlertDialogBuilder(this).setMessage(getString(R.string.delete_confirm, e.name))
                .setPositiveButton(R.string.delete) { _, _ -> op(R.string.done) { fs!!.delete(e) } }
                .setNegativeButton(R.string.cancel, null).show()
        }
        MaterialAlertDialogBuilder(this).setTitle(e.name).setItems(items.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun saveToPhone(e: RemoteEntry) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val base = e.name.substringBeforeLast('.', e.name)
        val ext = if (e.name.contains('.')) "." + e.name.substringAfterLast('.') else ""
        val dest = ZipUtil.uniqueName(dir, base, ext)
        download(e, dest) {
            android.media.MediaScannerConnection.scanFile(this, arrayOf(it.path), null, null)
            Toast.makeText(this, getString(R.string.saved_to, it.path), Toast.LENGTH_LONG).show()
        }
    }

    /** Counts what is written and stops when the user taps Cancel. */
    private class CountingOut(private val o: OutputStream, private val onBytes: (Long) -> Unit, private val cancelled: () -> Boolean) : OutputStream() {
        private var n = 0L
        override fun write(b: Int) { o.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (cancelled()) throw IOException("Cancelled")
            o.write(b, off, len); n += len; onBytes(n)
        }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    private fun download(e: RemoteEntry, dest: File, then: (File) -> Unit) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = e.size <= 0 }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); addView(bar) }
        var cancel = false
        val dlg = MaterialAlertDialogBuilder(this).setTitle(e.name).setView(box).setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancel = true }.show()
        var last = 0L
        lifecycleScope.launch {
            val err = withContext(io) {
                try {
                    dest.parentFile?.mkdirs()
                    CountingOut(FileOutputStream(dest), { n ->
                        val now = System.currentTimeMillis()
                        if (e.size > 0 && now - last > 150) { last = now; bar.post { bar.progress = (n * 1000 / e.size).toInt() } }
                    }, { cancel }).use { fs!!.download(e, it) }
                    null
                } catch (t: Throwable) { dest.delete(); t }
            }
            dlg.dismiss()
            if (err == null) then(dest)
            else if (!cancel) Toast.makeText(this@RemoteBrowserActivity, friendly(err), Toast.LENGTH_LONG).show()
        }
    }

    private fun upload(uris: List<Uri>) {
        busy.visibility = View.VISIBLE
        Toast.makeText(this, R.string.uploading, Toast.LENGTH_SHORT).show()
        val dir = stack.last().first
        lifecycleScope.launch {
            val err = withContext(io) {
                try {
                    for (u in uris) {
                        var name = "file"
                        contentResolver.query(u, null, null, null, null)?.use { c ->
                            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) ?: name }
                        }
                        val tmp = File(cacheDir2(), "up_" + System.currentTimeMillis() + "_" + name.replace('/', '_'))
                        contentResolver.openInputStream(u)!!.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
                        try { fs!!.upload(dir, name, tmp) } finally { tmp.delete() }
                    }
                    null
                } catch (t: Throwable) { t }
            }
            if (err == null) Toast.makeText(this@RemoteBrowserActivity, R.string.done, Toast.LENGTH_SHORT).show()
            else Toast.makeText(this@RemoteBrowserActivity, friendly(err), Toast.LENGTH_LONG).show()
            load()
        }
    }

    private fun openLocal(f: File) {
        val n = f.name
        val path = f.path
        when {
            MediaTypes.isImage(n) -> startActivity(Intent(this, PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PATH, path))
            MediaTypes.isVideo(n) -> startActivity(Intent(this, VideoPlayerActivity::class.java).putExtra(VideoPlayerActivity.EXTRA_PATH, path))
            MusicPlayer.isAudio(n) -> startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, path))
            PdfActivity.isPdf(n) -> startActivity(Intent(this, PdfActivity::class.java).putExtra(PdfActivity.EXTRA_PATH, path))
            OfficeDoc.isOffice(n) && OfficeDoc.kind(n) != "old" ->
                OfficeUnlock.open(this, f) { startActivity(Intent(this, OfficeActivity::class.java).putExtra(OfficeActivity.EXTRA_PATH, it.path)) }
            TextEditorActivity.isText(n) -> startActivity(Intent(this, TextEditorActivity::class.java).putExtra(TextEditorActivity.EXTRA_PATH, path))
            else -> try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substringAfterLast('.', "").lowercase()) ?: "*/*"
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), n))
            } catch (t: Throwable) { Toast.makeText(this, R.string.no_app_for_file, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onDestroy() {
        val f = fs
        Thread { try { f?.close() } catch (t: Throwable) { } }.start()
        io.close()
        super.onDestroy()
    }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    // ------------------------------------------------------------------ list

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(ContextCompat.getColor(ctx, R.color.card_bg)); cornerRadius = dp(14).toFloat() }
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                isClickable = true; isFocusable = true
                addView(ImageView(ctx).apply { id = View.generateViewId(); tag = "icon"; layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) } })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply { tag = "name"; textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setTextColor(ContextCompat.getColor(ctx, R.color.label)) })
                    addView(TextView(ctx).apply { tag = "sub"; textSize = 12f; maxLines = 1; setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary)) })
                })
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val e = entries[position]
            val v = h.itemView
            v.findViewWithTag<ImageView>("icon").setImageDrawable(FileIcons.drawable(FileItem(e.name, e.name, e.isDir, e.size, e.modified)))
            v.findViewWithTag<TextView>("name").text = e.name
            val date = if (e.modified > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.modified)) else ""
            val size = if (e.isDir || e.size <= 0) "" else Formatter.formatShortFileSize(this@RemoteBrowserActivity, e.size)
            v.findViewWithTag<TextView>("sub").text = listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
            v.setOnClickListener { click(e) }
            v.setOnLongClickListener { menu(e); true }
        }
    }

    companion object { const val EXTRA_ID = "id" }
}

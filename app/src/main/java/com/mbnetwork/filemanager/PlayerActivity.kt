package com.mbnetwork.filemanager

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Built-in music player screen. */
class PlayerActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var folder: TextView
    private lateinit var count: TextView
    private lateinit var seek: SeekBar
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var play: ImageButton
    private lateinit var shuffle: ImageButton
    private lateinit var repeat: ImageButton
    private lateinit var cover: android.widget.ImageView
    private lateinit var equalizer: EqualizerView
    private var shownCover: String? = null
    private var dragging = false
    private var queueDialog: BottomSheetDialog? = null
    private var queueAdapter: QueueAdapter? = null
    private var queueTitle: TextView? = null
    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val permissions = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        MusicPlayer.refreshNotification()
        attachEqualizer()
    }

    /** Real equalizer only when the microphone permission is granted (it reads the loudness of our own player). */
    private fun attachEqualizer() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Prefs(this).realEqualizer && granted && MusicPlayer.isPlaying) equalizer.attach(MusicPlayer.audioSessionId) else equalizer.detach()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!dragging) {
                seek.max = MusicPlayer.duration.coerceAtLeast(1)
                seek.progress = MusicPlayer.position
                pos.text = fmt(MusicPlayer.position)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        cover = findViewById(R.id.cover)
        equalizer = findViewById(R.id.equalizer)
        title = findViewById(R.id.songTitle)
        folder = findViewById(R.id.songFolder)
        count = findViewById(R.id.songCount)
        seek = findViewById(R.id.seek)
        pos = findViewById(R.id.position)
        dur = findViewById(R.id.duration)
        play = findViewById(R.id.btnPlay)
        shuffle = findViewById(R.id.btnShuffle)
        repeat = findViewById(R.id.btnRepeat)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnList).setOnClickListener { showQueue() }
        count.setOnClickListener { showQueue() }
        play.setOnClickListener { MusicPlayer.toggle() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { MusicPlayer.next() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { MusicPlayer.previous() }
        findViewById<ImageButton>(R.id.btnStop).setOnClickListener { MusicPlayer.stop(); finish() }
        shuffle.setOnClickListener { MusicPlayer.shuffle = !MusicPlayer.shuffle; updateUi() }
        repeat.setOnClickListener { MusicPlayer.repeatOne = !MusicPlayer.repeatOne; updateUi() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) pos.text = fmt(p) }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) { dragging = false; MusicPlayer.seek(s.progress) }
        })

        DeviceUi.enterRise(this)
        MusicPlayer.appContext = applicationContext
        // ask once (one dialog): notifications (Android 13+) and the microphone for the real equalizer
        val prefs = Prefs(this)
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && prefs.musicNotification &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need += android.Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 23 && prefs.realEqualizer && !prefs.micAsked &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need += android.Manifest.permission.RECORD_AUDIO
            prefs.micAsked = true
        }
        if (need.isNotEmpty()) permissions.launch(need.toTypedArray())
        intent.getStringExtra(EXTRA_PATH)?.let { if (savedInstanceState == null) MusicPlayer.playFolder(File(it)) }
        play.requestFocus() // TV remote: start on Play
    }

    override fun onStart() {
        super.onStart()
        MusicPlayer.listener = { runOnUiThread { updateUi() } }
        if (Prefs(this).animations) equalizer.start()
        updateUi()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        MusicPlayer.listener = null
        equalizer.stop()
        equalizer.detach()
        handler.removeCallbacks(ticker)
    }

    private fun updateUi() {
        if (!MusicPlayer.active && MusicPlayer.queue.isEmpty()) { queueDialog?.dismiss(); finish(); return }
        val f = MusicPlayer.current
        title.text = f?.nameWithoutExtension ?: "—"
        folder.text = f?.parentFile?.name ?: ""
        count.text = if (MusicPlayer.queue.isNotEmpty()) "${MusicPlayer.index + 1} / ${MusicPlayer.queue.size}" else ""
        dur.text = fmt(MusicPlayer.duration)
        play.setImageResource(if (MusicPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        shuffle.alpha = if (MusicPlayer.shuffle) 1f else 0.4f
        repeat.alpha = if (MusicPlayer.repeatOne) 1f else 0.4f
        showCover(f)
        // like Apple Music: the cover grows while playing and shrinks when paused
        (cover.parent as? View)?.let { frame ->
            val target = if (MusicPlayer.isPlaying) 1f else 0.86f
            if (Prefs(this).animations) frame.animate().scaleX(target).scaleY(target).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
            else { frame.scaleX = target; frame.scaleY = target }
        }
        equalizer.playing = MusicPlayer.isPlaying
        attachEqualizer()
        queueAdapter?.notifyDataSetChanged()
        queueTitle?.text = getString(R.string.playing_queue_n, MusicPlayer.queue.size)
    }

    // ------------------------------------------------------------------ playing queue

    private fun showQueue() {
        if (queueDialog != null) return
        val dlg = BottomSheetDialog(this, R.style.ThemeOverlay_MBFileManager_Sheet)
        val glass = GlassView(this).apply { setup(window.decorView, radiusDp = 32f, shape = GlassView.SHAPE_TOP, tintAlpha = 0.82f) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(View(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_handle)
            layoutParams = LinearLayout.LayoutParams(px(40), px(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(10) }
        })
        val title = TextView(this).apply {
            text = getString(R.string.playing_queue_n, MusicPlayer.queue.size)
            textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.label))
            setPadding(px(20), px(14), px(20), px(6))
        }
        queueTitle = title
        col.addView(title)
        val adapter = QueueAdapter()
        queueAdapter = adapter
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            clipToPadding = false
            setPadding(px(8), 0, px(8), px(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55f).toInt())
        }
        col.addView(rv)
        glass.addView(col)
        dlg.setContentView(glass)
        dlg.setOnDismissListener { queueDialog = null; queueAdapter = null; queueTitle = null }
        queueDialog = dlg
        dlg.show()
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        rv.scrollToPosition(MusicPlayer.index)
    }

    /** Remove from the list only, or delete the file from the phone. */
    private fun confirmRemove(pos: Int) {
        val f = MusicPlayer.queue.getOrNull(pos) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(f.nameWithoutExtension)
            .setItems(arrayOf(getString(R.string.remove_from_list), getString(R.string.delete_from_phone))) { _, i ->
                if (i == 0) MusicPlayer.removeAt(pos)
                else {
                    val toBin = Trash.canTrash(this, f.absolutePath)
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.delete)
                        .setMessage(getString(if (toBin) R.string.delete_bin_confirm else R.string.delete_confirm, f.name))
                        .setPositiveButton(if (toBin) R.string.move_to_bin else R.string.delete_forever) { _, _ ->
                            val at = MusicPlayer.queue.indexOfFirst { it.absolutePath == f.absolutePath }
                            if (at < 0 || !MusicPlayer.deleteFileAt(at))
                                android.widget.Toast.makeText(this, getString(R.string.failed, f.name), android.widget.Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.VH>() {
        inner class VH(val row: LinearLayout, val icon: ImageView, val name: TextView, val sub: TextView, val del: ImageButton) :
            RecyclerView.ViewHolder(row)

        override fun getItemCount() = MusicPlayer.queue.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = this@PlayerActivity
            val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(px(40), px(40)) }
            val name = TextView(ctx).apply { textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val sub = TextView(ctx).apply { textSize = 12f; setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary)) }
            val del = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_delete)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.danger)))
                background = null
                contentDescription = getString(R.string.delete)
                setPadding(px(10), px(10), px(10), px(10))
                layoutParams = LinearLayout.LayoutParams(px(44), px(44))
            }
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(60)
                setPadding(px(10), px(6), px(4), px(6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                addView(icon)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(12), 0, px(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(name); addView(sub)
                })
                addView(del)
            }
            return VH(row, icon, name, sub, del)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = MusicPlayer.queue[position]
            val current = position == MusicPlayer.index
            h.name.text = f.nameWithoutExtension
            h.name.setTextColor(ContextCompat.getColor(h.row.context, if (current) R.color.brand else R.color.label))
            h.name.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
            h.sub.text = if (current) getString(R.string.now_playing) else f.extension.uppercase()
            h.icon.setImageDrawable(FileIcons.drawable(FileItem(f.name, f.absolutePath, false, 0, 0)))
            h.row.setOnClickListener { MusicPlayer.playAt(h.bindingAdapterPosition) }
            h.del.setOnClickListener { confirmRemove(h.bindingAdapterPosition) }
        }
    }

    /** Album art of the playing song (embedded picture). Songs without a cover show a music note. */
    private fun showCover(f: File?) {
        val path = f?.absolutePath
        if (path == shownCover) return
        shownCover = path
        val pad = (84 * resources.displayMetrics.density).toInt()
        cover.setImageResource(R.drawable.ic_music_big)
        cover.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        cover.setPadding(pad, pad, pad, pad)
        if (path != null) ThumbLoader.load(this, path, cover) {
            cover.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            cover.setPadding(0, 0, 0, 0)
        }
    }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    // ---- swipe down anywhere (except on the seek bar) to go back to the mini player bar
    private var sx = 0f
    private var sy = 0f
    private var swiping = false
    private var onSeek = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        val content = findViewById<View>(android.R.id.content)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                sx = ev.rawX; sy = ev.rawY; swiping = false
                val r = android.graphics.Rect()
                onSeek = findViewById<View>(R.id.seek)?.let { it.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), (ev.rawY).toInt()) } ?: false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - sy
                val dx = ev.rawX - sx
                if (!swiping && !onSeek && dy > 16 * d && dy > Math.abs(dx) * 1.5f) {
                    swiping = true
                    val c = android.view.MotionEvent.obtain(ev); c.action = android.view.MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c); c.recycle()
                }
                if (swiping) { content.translationY = dy.coerceAtLeast(0f); return true }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (swiping) {
                swiping = false
                if (ev.rawY - sy > 110 * d && ev.actionMasked == android.view.MotionEvent.ACTION_UP) finish()
                else content.animate().translationY(0f).setDuration(180).start()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun finish() { super.finish(); DeviceUi.exitDrop(this) }

    companion object { const val EXTRA_PATH = "path" }
}

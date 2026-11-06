package com.mbnetwork.filemanager

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/** Video player with play / pause, ±10 s, a seek bar and quick access to the editor. */
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var video: VideoView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var play: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var tCur: TextView
    private lateinit var tDur: TextView
    private lateinit var path: String
    private lateinit var titleView: TextView
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var speedBtn: TextView
    private var siblings: List<String> = emptyList()
    private var index = 0
    private var mediaPlayer: android.media.MediaPlayer? = null
    private val speeds = floatArrayOf(1f, 1.5f, 2f, 0.5f)
    private var speedIdx = 0
    private var scanDir = 0
    private val scanner = object : Runnable {
        override fun run() {
            if (scanDir == 0 || !prepared) return
            val t = (video.currentPosition + scanDir * 3000).coerceIn(0, video.duration)
            video.seekTo(t); seek.progress = t; tCur.text = fmt(t)
            handler.postDelayed(this, 250)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var prepared = false
    private var resumePos = 0
    private var wasPlaying = true
    private var dragging = false
    private var barsOn = true

    private lateinit var flash: TextView
    private lateinit var centerRow: View
    private val hideBars = Runnable { if (video.isPlaying && !dragging) showBars(false) }

    private fun glass(radiusDp: Int, color: Int = 0x8C000000.toInt()) = GradientDrawable().apply {
        setColor(color); cornerRadius = UiKit.dp(this@VideoPlayerActivity, radiusDp).toFloat()
    }

    private fun circle(btn: ImageButton, sizeDp: Int, pad: Int): ImageButton {
        btn.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x8C000000.toInt()) }
        val s = UiKit.dp(this, sizeDp)
        btn.layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(UiKit.dp(this@VideoPlayerActivity, 6), 0, UiKit.dp(this@VideoPlayerActivity, 6), 0) }
        val p = UiKit.dp(this, pad); btn.setPadding(p, p, p, p)
        return btn
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideBars)
        if (barsOn) handler.postDelayed(hideBars, 3500)
    }

    private fun flashText(t: String, right: Boolean) {
        flash.text = t
        (flash.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (right) Gravity.END else Gravity.START)
        flash.requestLayout()
        flash.animate().cancel()
        flash.alpha = 1f
        flash.animate().alpha(0f).setStartDelay(450).setDuration(300).start()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (prepared && !dragging) {
                val p = video.currentPosition
                seek.progress = p
                tCur.text = fmt(p)
                play.setImageResource(if (video.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        DeviceUi.enterSlide(this)
        path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val f = File(path)
        // the playlist: the videos shown in the list the user came from, else all videos in the same folder
        val given = intent.getStringArrayExtra(EXTRA_LIST)?.toList()?.filter { File(it).exists() } ?: emptyList()
        var list: List<String> = given
        if (list.size < 2) {
            list = try {
                f.parentFile?.listFiles()?.filter { it.isFile && MediaTypes.isVideo(it.name) }
                    ?.sortedWith(compareBy({ it.name.lowercase() }, { it.name }))?.map { it.absolutePath } ?: emptyList()
            } catch (e: Throwable) { emptyList() }
        }
        fun same(x: String, y: String) = x == y || try { File(x).canonicalPath == File(y).canonicalPath } catch (e: Throwable) { false }
        var at = list.indexOfFirst { same(it, path) }
        if (at < 0) { list = list + path; at = list.size - 1 }
        siblings = list
        index = at

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleBars(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!prepared) return true
                val right = e.x > video.width / 2f
                val t = (video.currentPosition + (if (right) 10000 else -10000)).coerceIn(0, video.duration)
                video.seekTo(t); seek.progress = t; tCur.text = fmt(t)
                flashText(if (right) "+10 s  ⏩" else "⏪  -10 s", right)
                return true
            }
        })
        root.addView(View(this).apply {
            setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        flash = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); alpha = 0f
            background = glass(26)
            setPadding(UiKit.dp(context, 18), UiKit.dp(context, 10), UiKit.dp(context, 18), UiKit.dp(context, 10))
        }
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
            .apply { setMargins(UiKit.dp(this@VideoPlayerActivity, 36), 0, UiKit.dp(this@VideoPlayerActivity, 36), 0) })

        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(context, 10), UiKit.dp(context, 10), UiKit.dp(context, 10), UiKit.dp(context, 10))
            addView(circle(UiKit.iconButton(context, R.drawable.ic_back_white, getString(R.string.close)) { finish() }, 44, 11))
            titleView = TextView(context).apply {
                text = f.name; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                background = glass(22)
                setPadding(UiKit.dp(context, 16), UiKit.dp(context, 10), UiKit.dp(context, 16), UiKit.dp(context, 10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(UiKit.dp(context, 6), 0, UiKit.dp(context, 6), 0) }
            }
            addView(titleView)
            addView(circle(UiKit.iconButton(context, R.drawable.ic_edit, getString(R.string.edit_video)) {
                video.pause()
                startActivity(Intent(context, VideoEditorActivity::class.java).putExtra(VideoEditorActivity.EXTRA_PATH, path))
            }, 44, 11))
            addView(circle(UiKit.iconButton(context, R.drawable.ic_share, getString(R.string.share)) { share() }, 44, 11))
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        tCur = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE); text = "0:00" }
        tDur = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE); text = "0:00" }
        seek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) tCur.text = fmt(p) }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) { dragging = false; video.seekTo(s.progress) }
            })
        }
        play = circle(UiKit.iconButton(this, R.drawable.ic_pause, getString(R.string.play)) { togglePlay(); scheduleHide() }, 72, 20)
        prevBtn = circle(UiKit.iconButton(this, R.drawable.ic_prev, getString(R.string.previous)) { openAt(index - 1) }, 52, 14)
        nextBtn = circle(UiKit.iconButton(this, R.drawable.ic_next, getString(R.string.next)) { openAt(index + 1) }, 52, 14)
        speedBtn = TextView(this).apply {
            text = "1x"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            contentDescription = getString(R.string.speed)
            background = glass(18, 0x33FFFFFF)
            setPadding(UiKit.dp(context, 14), UiKit.dp(context, 7), UiKit.dp(context, 14), UiKit.dp(context, 7))
            setOnClickListener { cycleSpeed() }
        }
        updateNav()
        seek.progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        seek.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)

        centerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(prevBtn); addView(play); addView(nextBtn)
        }
        root.addView(centerRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(28)
            setPadding(UiKit.dp(context, 18), UiKit.dp(context, 10), UiKit.dp(context, 18), UiKit.dp(context, 8))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tCur); addView(seek); addView(tDur)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(holdable(UiKit.iconButton(context, R.drawable.ic_replay_10, "-10") { video.seekTo((video.currentPosition - 10000).coerceAtLeast(0)) }, -1))
                if (android.os.Build.VERSION.SDK_INT >= 23) addView(speedBtn)
                addView(holdable(UiKit.iconButton(context, R.drawable.ic_forward_10, "+10") { video.seekTo((video.currentPosition + 10000).coerceAtMost(video.duration)) }, 1))
            })
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(UiKit.dp(this@VideoPlayerActivity, 14), 0, UiKit.dp(this@VideoPlayerActivity, 14), UiKit.dp(this@VideoPlayerActivity, 14)) })
        setContentView(root)

        video.setOnPreparedListener { mp ->
            prepared = true
            mediaPlayer = mp
            applySpeed()
            seek.max = mp.duration
            tDur.text = fmt(mp.duration)
            if (resumePos > 0) video.seekTo(resumePos)
            if (wasPlaying) video.start()
        }
        video.setOnCompletionListener {
            if (index + 1 < siblings.size) openAt(index + 1) else { play.setImageResource(R.drawable.ic_play); showBars(true) }
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.video_failed, Toast.LENGTH_LONG).show(); prepared = false; true
        }
        video.setVideoPath(path)
        handler.post(ticker)
    }

    private fun openAt(i: Int) {
        if (i < 0 || i >= siblings.size) return
        index = i
        path = siblings[i]
        scanDir = 0
        prepared = false; mediaPlayer = null; resumePos = 0; wasPlaying = true
        seek.progress = 0; tCur.text = "0:00"
        titleView.text = File(path).name
        video.stopPlayback()
        video.setVideoPath(path)
        updateNav()
    }

    private fun updateNav() {
        prevBtn.alpha = if (index > 0) 1f else 0.35f
        nextBtn.alpha = if (index + 1 < siblings.size) 1f else 0.35f
    }

    private fun holdable(b: ImageButton, dir: Int): ImageButton {
        b.setOnLongClickListener { scanDir = dir; handler.post(scanner); true }
        b.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                scanDir = 0; handler.removeCallbacks(scanner)
            }
            false
        }
        return b
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        applySpeed()
    }

    private fun applySpeed() {
        val sp = speeds[speedIdx]
        speedBtn.text = (if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString()) + "x"
        if (android.os.Build.VERSION.SDK_INT < 23) return
        val mp = mediaPlayer ?: return
        try {
            val playing = video.isPlaying
            mp.playbackParams = mp.playbackParams.setSpeed(sp)
            if (!playing) video.pause()
        } catch (e: Throwable) { }
    }

    private fun togglePlay() {
        if (video.isPlaying) video.pause() else video.start()
        play.setImageResource(if (video.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun toggleBars() = showBars(!barsOn)

    private fun showBars(on: Boolean) {
        barsOn = on
        listOf(topBar, bottomBar, centerRow).forEach { v ->
            if (on) v.visibility = View.VISIBLE
            v.animate().alpha(if (on) 1f else 0f).setDuration(180).withEndAction { if (!barsOn) v.visibility = View.GONE }.start()
        }
        scheduleHide()
    }

    private fun share() {
        val f = File(path)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(MediaTypes.mime(f.name)).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.share)))
    }

    private fun fmt(ms: Int): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    override fun onPause() {
        super.onPause()
        if (prepared) { wasPlaying = video.isPlaying; resumePos = video.currentPosition; video.pause() }
    }

    override fun onResume() {
        super.onResume()
        if (prepared) { video.seekTo(resumePos); if (wasPlaying) video.start() }
    }

    override fun onDestroy() { handler.removeCallbacks(ticker); handler.removeCallbacks(scanner); handler.removeCallbacks(hideBars); super.onDestroy() }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    companion object { const val EXTRA_PATH = "path"; const val EXTRA_LIST = "list" }
}

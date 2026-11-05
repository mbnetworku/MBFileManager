package com.mbnetwork.filemanager

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
    private val handler = Handler(Looper.getMainLooper())
    private var prepared = false
    private var resumePos = 0
    private var wasPlaying = true
    private var dragging = false
    private var barsOn = true

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

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(View(this).apply { setOnClickListener { toggleBars() } },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.scrim(true)
            setPadding(UiKit.dp(context, 6), UiKit.dp(context, 4), UiKit.dp(context, 6), UiKit.dp(context, 16))
            addView(UiKit.iconButton(context, R.drawable.ic_back_white, getString(R.string.close)) { finish() })
            addView(TextView(context).apply {
                text = f.name; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(UiKit.dp(context, 6), 0, UiKit.dp(context, 6), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(UiKit.iconButton(context, R.drawable.ic_edit, getString(R.string.edit_video)) {
                video.pause()
                startActivity(Intent(context, VideoEditorActivity::class.java).putExtra(VideoEditorActivity.EXTRA_PATH, path))
            })
            addView(UiKit.iconButton(context, R.drawable.ic_share, getString(R.string.share)) { share() })
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
        play = UiKit.iconButton(this, R.drawable.ic_pause, getString(R.string.play)) { togglePlay() }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.scrim(false)
            setPadding(UiKit.dp(context, 16), UiKit.dp(context, 18), UiKit.dp(context, 16), UiKit.dp(context, 10))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tCur); addView(seek); addView(tDur)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(UiKit.iconButton(context, R.drawable.ic_replay_10, "-10") { video.seekTo((video.currentPosition - 10000).coerceAtLeast(0)) })
                addView(play)
                addView(UiKit.iconButton(context, R.drawable.ic_forward_10, "+10") { video.seekTo((video.currentPosition + 10000).coerceAtMost(video.duration)) })
            })
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        video.setOnPreparedListener { mp ->
            prepared = true
            seek.max = mp.duration
            tDur.text = fmt(mp.duration)
            if (resumePos > 0) video.seekTo(resumePos)
            if (wasPlaying) video.start()
        }
        video.setOnCompletionListener { play.setImageResource(R.drawable.ic_play); showBars(true) }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.video_failed, Toast.LENGTH_LONG).show(); true
        }
        video.setVideoPath(path)
        handler.post(ticker)
    }

    private fun togglePlay() {
        if (video.isPlaying) video.pause() else video.start()
        play.setImageResource(if (video.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun toggleBars() = showBars(!barsOn)

    private fun showBars(on: Boolean) {
        barsOn = on
        listOf(topBar, bottomBar).forEach { v ->
            if (on) v.visibility = View.VISIBLE
            v.animate().alpha(if (on) 1f else 0f).setDuration(180).withEndAction { if (!barsOn) v.visibility = View.GONE }.start()
        }
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

    override fun onDestroy() { handler.removeCallbacks(ticker); super.onDestroy() }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    companion object { const val EXTRA_PATH = "path" }
}

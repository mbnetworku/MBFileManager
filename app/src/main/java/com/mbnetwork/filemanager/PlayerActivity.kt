package com.mbnetwork.filemanager

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
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
    private var dragging = false

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

        intent.getStringExtra(EXTRA_PATH)?.let { if (savedInstanceState == null) MusicPlayer.playFolder(File(it)) }
        play.requestFocus() // TV remote: start on Play
    }

    override fun onStart() {
        super.onStart()
        MusicPlayer.listener = { runOnUiThread { updateUi() } }
        updateUi()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        MusicPlayer.listener = null
        handler.removeCallbacks(ticker)
    }

    private fun updateUi() {
        val f = MusicPlayer.current
        title.text = f?.nameWithoutExtension ?: "—"
        folder.text = f?.parentFile?.name ?: ""
        count.text = if (MusicPlayer.queue.isNotEmpty()) "${MusicPlayer.index + 1} / ${MusicPlayer.queue.size}" else ""
        dur.text = fmt(MusicPlayer.duration)
        play.setImageResource(if (MusicPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        shuffle.alpha = if (MusicPlayer.shuffle) 1f else 0.4f
        repeat.alpha = if (MusicPlayer.repeatOne) 1f else 0.4f
    }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    companion object { const val EXTRA_PATH = "path" }
}

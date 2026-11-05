package com.mbnetwork.filemanager

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Video editor: trim (cut the start / end), mute the sound and rotate. Saves a new MP4 without re-encoding. */
class VideoEditorActivity : AppCompatActivity() {

    private lateinit var video: VideoView
    private lateinit var slider: RangeSlider
    private lateinit var startText: TextView
    private lateinit var endText: TextView
    private lateinit var lenText: TextView
    private lateinit var rotText: TextView
    private lateinit var mute: MaterialSwitch
    private lateinit var src: File
    private val handler = Handler(Looper.getMainLooper())
    private var durationMs = 0
    private var startMs = 0
    private var endMs = 0
    private var rotation = 0
    private var previewing = false

    private val stopper = object : Runnable {
        override fun run() {
            if (previewing && video.isPlaying && video.currentPosition >= endMs) { video.pause(); previewing = false }
            if (previewing) handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        DeviceUi.enterSlide(this)
        src = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(context, 6), UiKit.dp(context, 4), UiKit.dp(context, 10), UiKit.dp(context, 4))
            addView(UiKit.iconButton(context, R.drawable.ic_close, getString(R.string.cancel)) { finish() })
            addView(TextView(context).apply {
                text = getString(R.string.edit_video); textSize = 16f; setTextColor(Color.WHITE)
                setPadding(UiKit.dp(context, 6), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(UiKit.pill(context, getString(R.string.save), selected = true) { save() }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
            })
        })

        video = VideoView(this)
        root.addView(FrameLayout(this).apply {
            addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        startText = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE) }
        endText = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE) }
        lenText = TextView(this).apply { textSize = 13f; setTextColor(0xFF5AA9FF.toInt()); gravity = Gravity.CENTER }
        slider = RangeSlider(this).apply {
            addOnChangeListener { s, _, fromUser ->
                val v = s.values
                startMs = v[0].toInt(); endMs = v[1].toInt()
                labels()
                if (fromUser) { previewing = false; video.seekTo(startMs) }
            }
        }
        rotText = TextView(this)
        mute = MaterialSwitch(this).apply { text = getString(R.string.mute_audio); setTextColor(Color.WHITE) }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16), UiKit.dp(context, 8), UiKit.dp(context, 16), UiKit.dp(context, 12))
            addView(TextView(context).apply { text = getString(R.string.trim); textSize = 14f; setTextColor(Color.WHITE) })
            addView(slider)
            addView(LinearLayout(context).apply {
                addView(startText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(lenText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(endText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { })
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, UiKit.dp(context, 10), 0, 0)
                addView(UiKit.pill(context, getString(R.string.preview)) { preview() })
                addView(UiKit.pill(context, getString(R.string.rotate_n, 0)) { }.also { rotText = it
                    it.setOnClickListener { rotation = (rotation + 90) % 360; rotText.text = getString(R.string.rotate_n, rotation) } })
            })
            addView(mute)
        })
        setContentView(root)

        video.setOnPreparedListener { mp ->
            durationMs = mp.duration
            if (durationMs > 0) {
                slider.valueFrom = 0f
                slider.valueTo = durationMs.toFloat()
                slider.values = listOf(0f, durationMs.toFloat())
                slider.setLabelFormatter { v -> fmt(v.toInt()) }
                startMs = 0; endMs = durationMs
                labels()
                video.seekTo(1)
            }
        }
        video.setOnErrorListener { _, _, _ -> Toast.makeText(this, R.string.video_failed, Toast.LENGTH_LONG).show(); true }
        video.setVideoPath(src.absolutePath)
    }

    private fun labels() {
        startText.text = fmt(startMs)
        endText.text = fmt(endMs)
        endText.gravity = Gravity.END
        lenText.text = fmt(endMs - startMs)
    }

    private fun preview() {
        video.seekTo(startMs)
        video.start()
        previewing = true
        handler.post(stopper)
    }

    private fun save() {
        if (durationMs <= 0 || endMs <= startMs) return
        video.pause(); previewing = false
        val dst = ZipUtil.uniqueName(src.parentFile!!, src.nameWithoutExtension + "_edited", ".mp4")
        val bar = LinearProgressIndicator(this).apply { max = 100 }
        val dlg = MaterialAlertDialogBuilder(this).setTitle(R.string.saving)
            .setView(FrameLayout(this).apply { setPadding(UiKit.dp(context, 24), UiKit.dp(context, 8), UiKit.dp(context, 24), 0); addView(bar) })
            .setCancelable(false).show()
        val s = startMs.toLong(); val e = endMs.toLong(); val m = mute.isChecked; val rot = rotation
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { VideoTrimmer.trim(src.absolutePath, dst.absolutePath, s, e, m, rot) { p -> runOnUiThread { bar.progress = p } } }
            }
            dlg.dismiss()
            r.onSuccess {
                MediaScannerConnection.scanFile(this@VideoEditorActivity, arrayOf(dst.path), null, null)
                Toast.makeText(this@VideoEditorActivity, getString(R.string.saved_as, dst.name), Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                dst.delete()
                MaterialAlertDialogBuilder(this@VideoEditorActivity).setTitle(R.string.failed_title)
                    .setMessage(R.string.video_unsupported).setPositiveButton(R.string.ok, null).show()
            }
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        val t = (ms % 1000) / 100
        return "%d:%02d.%d".format(s / 60, s % 60, t)
    }

    override fun onDestroy() { handler.removeCallbacks(stopper); super.onDestroy() }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    companion object { const val EXTRA_PATH = "path" }
}

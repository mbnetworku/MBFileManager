package com.mbnetwork.filemanager

import android.graphics.Typeface
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/** Builds the sidebar: storage volumes (with usage bars), quick folders, system places. */
class Sidebar(private val act: MainActivity, private val box: LinearLayout) {
    private val d = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    fun build() {
        box.removeAllViews()
        // glossy header
        box.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glossy)
            setPadding(px(20), px(36), px(20), px(20))
            addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(px(52), px(52))
            })
            addView(TextView(act).apply {
                setText(R.string.app_name); setTextColor(0xFFFFFFFF.toInt()); textSize = 20f
                setTypeface(typeface, Typeface.BOLD); setPadding(0, px(10), 0, 0)
            })
            addView(TextView(act).apply {
                text = act.getString(R.string.version_label, BuildConfig.VERSION_NAME)
                setTextColor(0xCCFFFFFF.toInt()); textSize = 13f
            })
        })

        val internal = Environment.getExternalStorageDirectory().absolutePath
        row(act.getString(R.string.tab_home), R.drawable.ic_home_white, R.color.grad_start) { act.selectTab(R.id.nav_home) }
        section(R.string.storage)
        storageRow(act.getString(R.string.internal_storage), internal, R.drawable.ic_storage, R.color.grad_start)
        StorageVolumes.list(act).drop(1).forEach {
            storageRow(it.label, it.path, if (it.usb) R.drawable.ic_usb else R.drawable.ic_sd, if (it.usb) R.color.t_ppt else R.color.accent)
        }

        row(act.getString(R.string.usb_tools), R.drawable.ic_usb, R.color.t_ppt) {
            act.closeDrawer(); act.startActivity(android.content.Intent(act, UsbToolsActivity::class.java))
        }
        section(R.string.quick_access)
        listOf(
            Triple(R.string.downloads, Environment.DIRECTORY_DOWNLOADS, R.drawable.ic_archive to R.color.t_doc),
            Triple(R.string.camera, Environment.DIRECTORY_DCIM, R.drawable.ic_image to R.color.t_image),
            Triple(R.string.pictures, Environment.DIRECTORY_PICTURES, R.drawable.ic_image to R.color.t_image),
            Triple(R.string.music, Environment.DIRECTORY_MUSIC, R.drawable.ic_audio to R.color.t_audio),
            Triple(R.string.videos, Environment.DIRECTORY_MOVIES, R.drawable.ic_video to R.color.t_video),
            Triple(R.string.documents, "Documents", R.drawable.ic_doc to R.color.t_doc)
        ).forEach { (name, dir, ic) ->
            val f = File(internal, dir)
            if (f.exists()) row(act.getString(name), ic.first, ic.second) { act.openLocation(f.absolutePath) }
        }

        section(R.string.system_section)
        row(act.getString(R.string.obb_folder), R.drawable.ic_zip, R.color.t_archive) { act.openLocation("$internal/Android/obb") }
        if (RootShell.deviceRooted) {
            row(act.getString(R.string.data_folder), R.drawable.ic_lock, R.color.danger) { act.openLocation("$internal/Android/data") }
            row(act.getString(R.string.root_fs), R.drawable.ic_lock, R.color.danger) { act.openLocation("/") }
        }
        row(act.getString(R.string.tab_trash), R.drawable.ic_delete, R.color.t_file) { act.selectTab(R.id.nav_trash) }
        row(act.getString(R.string.tab_apps), R.drawable.ic_apps_white, R.color.t_apk) { act.selectTab(R.id.nav_apps) }

        section(R.string.appearance)
        row(act.getString(R.string.appearance), R.drawable.ic_tune, R.color.grad_mid) { act.closeDrawer(); DeviceUi.chooseTheme(act, act.prefs) }
        row(act.getString(R.string.check_updates), R.drawable.ic_open, R.color.accent) { act.closeDrawer(); act.checkUpdates() }
    }

    private fun section(title: Int) {
        box.addView(TextView(act).apply {
            setText(title); textSize = 12f; isAllCaps = true; alpha = 0.6f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(px(20), px(18), px(20), px(6))
        })
    }

    private fun iconTile(ic: Int, color: Int) = ImageView(act).apply {
        setImageResource(ic)
        background = DeviceUi.tile(act, ContextCompat.getColor(act, color), 10f)
        androidx.core.widget.ImageViewCompat.setImageTintList(this, android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt()))
        setPadding(px(7), px(7), px(7), px(7))
        layoutParams = LinearLayout.LayoutParams(px(36), px(36))
    }

    private fun baseRow(click: () -> Unit) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_icon_item)
        isFocusable = true; isClickable = true
        minimumHeight = px(52)
        setPadding(px(16), px(6), px(16), px(6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(px(8), px(1), px(8), px(1)) }
        setOnClickListener { click() }
        DeviceUi.liquidTouch(this)
    }

    private fun row(title: String, ic: Int, color: Int, click: () -> Unit) {
        box.addView(baseRow(click).apply {
            addView(iconTile(ic, color))
            addView(TextView(act).apply {
                text = title; textSize = 15f; setPadding(px(14), 0, 0, 0)
            })
        })
    }

    private fun storageRow(title: String, path: String, ic: Int, color: Int) {
        box.addView(baseRow { act.openLocation(path) }.apply {
            addView(iconTile(ic, color))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(act).apply { text = title; textSize = 15f })
                try {
                    val st = StatFs(path)
                    val total = st.blockCountLong * st.blockSizeLong
                    val free = st.availableBlocksLong * st.blockSizeLong
                    addView(LinearProgressIndicator(act).apply {
                        progress = if (total > 0) ((total - free) * 100 / total).toInt() else 0
                        trackCornerRadius = px(3); trackThickness = px(5)
                        setIndicatorColor(ContextCompat.getColor(act, color))
                        setPadding(0, px(5), 0, px(3))
                    })
                    addView(TextView(act).apply {
                        text = "${Formatter.formatShortFileSize(act, free)} free of ${Formatter.formatShortFileSize(act, total)}"
                        textSize = 11f; alpha = 0.7f
                    })
                } catch (e: Exception) { }
            })
        })
    }
}

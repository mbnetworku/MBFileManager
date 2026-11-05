package com.mbnetwork.filemanager

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** About screen: app name, version, copyright and open-source notices. */
object About {
    fun show(act: Activity) {
        val d = act.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        fun text(t: String, size: Float, bold: Boolean = false, secondary: Boolean = false, top: Int = 0) = TextView(act).apply {
            this.text = t; textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(act, if (secondary) R.color.text_secondary else R.color.label))
            gravity = Gravity.CENTER
            setPadding(0, px(top), 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(8), px(24), px(8))
            addView(ImageView(act).apply {
                setImageResource(R.mipmap.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(px(72), px(72))
            })
            addView(text(act.getString(R.string.app_name), 22f, bold = true, top = 10))
            addView(text(act.getString(R.string.version_label, BuildConfig.VERSION_NAME), 14f, secondary = true))
            addView(text(act.getString(R.string.about_copyright), 15f, bold = true, top = 16))
            addView(text(act.getString(R.string.about_developed), 13f, secondary = true, top = 2))
            addView(text(act.getString(if (Net.blocked(act)) R.string.net_blocked else R.string.net_allowed), 14f, bold = true, top = 16))
            addView(text(act.getString(R.string.about_privacy), 12.5f, secondary = true, top = 6))
            addView(text(act.getString(R.string.about_notices_title), 14f, bold = true, top = 22))
            addView(TextView(act).apply {
                text = act.getString(R.string.about_notices); textSize = 13f
                setTextColor(ContextCompat.getColor(act, R.color.text_secondary))
                setPadding(0, px(6), 0, 0)
            })
            addView(text(act.getString(R.string.about_trademarks), 12f, secondary = true, top = 18))
        }
        MaterialAlertDialogBuilder(act)
            .setView(ScrollView(act).apply { addView(col) })
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}

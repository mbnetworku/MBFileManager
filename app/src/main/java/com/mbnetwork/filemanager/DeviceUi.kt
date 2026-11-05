package com.mbnetwork.filemanager

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Adapts the UI to the device: phone, tablet or Android TV. */
object DeviceUi {

    fun isTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** Tablets and TVs get the side rail + grid layout. */
    fun isLarge(ctx: Context) = isTv(ctx) || ctx.resources.configuration.smallestScreenWidthDp >= 600

    private fun contentWidthDp(ctx: Context) = ctx.resources.configuration.screenWidthDp - if (isLarge(ctx)) 80 else 0

    fun fileColumns(ctx: Context) = if (!isLarge(ctx)) 1 else maxOf(3, contentWidthDp(ctx) / 160)

    /** Icon view (like a photo grid): about 115dp per column. */
    fun iconColumns(ctx: Context) = maxOf(3, contentWidthDp(ctx) / 115)

    fun appColumns(ctx: Context) = when {
        isLarge(ctx) -> maxOf(2, contentWidthDp(ctx) / 340)
        ctx.resources.configuration.screenWidthDp >= 560 -> 2 // phone in landscape
        else -> 1
    }

    /** Remote-control friendly focus: the focused card grows and gets a glowing outline. */
    fun applyFocusEffect(card: View) {
        val d = card.resources.displayMetrics.density
        card.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(140).start()
            v.translationZ = if (has) 8 * d else 0f
            (v as? MaterialCardView)?.strokeWidth = if (has) (3 * d).toInt() else 0
        }
    }

    // ---- theme: 0 = system, 1 = white, 2 = black ----
    fun applyTheme(ctx: Context, prefs: Prefs) {
        val mode = when (prefs.theme) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> if (isTv(ctx)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Applies the user's transparency to a color (glass effect). */
    fun glass(ctx: Context, colorRes: Int): Int {
        val c = androidx.core.content.ContextCompat.getColor(ctx, colorRes)
        val alpha = (255 * (100 - Prefs(ctx).transparency) / 100).coerceIn(60, 255)
        return (c and 0x00FFFFFF) or (alpha shl 24)
    }

    /** Appearance: theme (White / Black / System) + transparency slider. */
    fun chooseTheme(activity: Activity, prefs: Prefs) {
        val d = activity.resources.displayMetrics.density
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val group = android.widget.RadioGroup(activity)
        listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).forEachIndexed { i, s ->
            group.addView(com.google.android.material.radiobutton.MaterialRadioButton(activity).apply {
                id = 100 + i; setText(s); isChecked = prefs.theme == i
            })
        }
        box.addView(group)
        box.addView(android.widget.TextView(activity).apply {
            setText(R.string.transparency); textSize = 16f
            setPadding(0, (16 * d).toInt(), 0, 0)
        })
        val slider = com.google.android.material.slider.Slider(activity).apply {
            valueFrom = 0f; valueTo = 70f; stepSize = 5f; value = prefs.transparency.toFloat()
        }
        box.addView(slider)
        box.addView(android.widget.TextView(activity).apply { setText(R.string.transparency_hint); alpha = 0.7f })
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.appearance)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newTheme = group.checkedRadioButtonId - 100
                val newT = slider.value.toInt()
                val changed = newTheme != prefs.theme || newT != prefs.transparency
                prefs.theme = newTheme; prefs.transparency = newT
                applyTheme(activity, prefs)
                if (changed) activity.recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Suppress("unused")
    fun chooseThemeOld(activity: Activity, prefs: Prefs) {
        val names = arrayOf(
            activity.getString(R.string.theme_system),
            activity.getString(R.string.theme_light),
            activity.getString(R.string.theme_dark)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(names, prefs.theme) { d, i ->
                prefs.theme = i
                d.dismiss()
                applyTheme(activity, prefs)
            }
            .show()
    }
}

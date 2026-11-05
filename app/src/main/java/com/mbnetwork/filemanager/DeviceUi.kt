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

    fun chooseTheme(activity: Activity, prefs: Prefs) {
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

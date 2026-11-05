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
            (v as? MaterialCardView)?.let { c ->
                if (has) { c.strokeColor = androidx.core.content.ContextCompat.getColor(c.context, R.color.focus_glow); c.strokeWidth = (3 * d).toInt() }
                else styleCard(c)
            }
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

    /** Glossy rounded icon tile: gradient (light -> dark) + white shine on the top half. */
    fun tile(ctx: Context, color: Int, radiusDp: Float = 12f): android.graphics.drawable.Drawable {
        val r = radiusDp * ctx.resources.displayMetrics.density
        fun shade(f: Float) = android.graphics.Color.rgb(
            (android.graphics.Color.red(color) * f).toInt().coerceIn(0, 255),
            (android.graphics.Color.green(color) * f).toInt().coerceIn(0, 255),
            (android.graphics.Color.blue(color) * f).toInt().coerceIn(0, 255))
        val base = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(shade(1.25f), color, shade(0.75f))
        ).apply { cornerRadius = r }
        val shine = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        return android.graphics.drawable.LayerDrawable(arrayOf(base, shine)).apply {
            setLayerInset(1, 0, 0, 0, (24 * ctx.resources.displayMetrics.density).toInt())
        }
    }

    fun liquid(ctx: Context) = Prefs(ctx).liquidGlass

    /** Liquid Glass backdrop: soft blue / teal / cyan light blobs (no pink). */
    fun liquidBackground(ctx: Context): android.graphics.drawable.Drawable {
        val dm = ctx.resources.displayMetrics
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val base = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            if (night) intArrayOf(0xFF02040A.toInt(), 0xFF061A33.toInt()) else intArrayOf(0xFFE8F1FF.toInt(), 0xFFD3ECF0.toInt()))
        fun blob(c: Int) = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = maxOf(dm.widthPixels, dm.heightPixels) * 0.45f
            colors = intArrayOf(c, c and 0x00FFFFFF)
        }
        val a = if (night) 0x66 else 0x88
        val layers = arrayOf(base, blob((a shl 24) or 0x1E88E5), blob((a shl 24) or 0x00BFA5), blob((a shl 24) or 0x26C6DA))
        return android.graphics.drawable.LayerDrawable(layers).apply {
            val w = dm.widthPixels; val h = dm.heightPixels
            setLayerInset(1, -w / 2, -h / 6, w / 3, h / 2)
            setLayerInset(2, w / 3, h / 3, -w / 2, -h / 6)
            setLayerInset(3, -w / 4, h / 2, w / 4, -h / 3)
        }
    }

    /** Card look: Liquid Glass = translucent + thin light edge, otherwise normal glass color. */
    fun styleCard(card: MaterialCardView) {
        val ctx = card.context
        card.setCardBackgroundColor(glass(ctx, R.color.card_bg))
        if (liquid(ctx)) {
            card.strokeColor = 0x55FFFFFF
            card.strokeWidth = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)
            card.cardElevation = 0f
        } else {
            card.strokeColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.focus_glow)
            card.strokeWidth = 0
        }
    }

    /** Applies the user's transparency to a color (glass effect). */
    fun glass(ctx: Context, colorRes: Int): Int {
        val c = androidx.core.content.ContextCompat.getColor(ctx, colorRes)
        val p = Prefs(ctx)
        val t = if (p.liquidGlass) maxOf(p.transparency, 45) else p.transparency
        val alpha = (255 * (100 - t) / 100).coerceIn(60, 255)
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
        val liquidSw = com.google.android.material.materialswitch.MaterialSwitch(activity).apply {
            setText(R.string.liquid_glass); isChecked = prefs.liquidGlass; textSize = 16f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        box.addView(liquidSw)
        box.addView(android.widget.TextView(activity).apply { setText(R.string.transparency_hint); alpha = 0.7f })
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.appearance)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newTheme = group.checkedRadioButtonId - 100
                val newT = slider.value.toInt()
                val changed = newTheme != prefs.theme || newT != prefs.transparency || liquidSw.isChecked != prefs.liquidGlass
                prefs.theme = newTheme; prefs.transparency = newT; prefs.liquidGlass = liquidSw.isChecked
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

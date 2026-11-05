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

    /** Android-based car radios / head units. */
    fun isCar(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_CAR ||
            ctx.packageManager.hasSystemFeature("android.hardware.type.automotive")
    }

    /** TVs, car radios and weak devices skip the live blur (plain frosted glass instead) to stay smooth. */
    fun lowPower(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return isTv(ctx) || isCar(ctx) || am?.isLowRamDevice == true
    }

    /** Tablets, TVs and car radios get the side rail + grid layout. */
    fun isLarge(ctx: Context) = isTv(ctx) || isCar(ctx) || ctx.resources.configuration.smallestScreenWidthDp >= 600

    private fun contentWidthDp(ctx: Context) = ctx.resources.configuration.screenWidthDp - if (isLarge(ctx)) 80 else 0

    fun fileColumns(ctx: Context) = if (!isLarge(ctx)) 1 else maxOf(3, contentWidthDp(ctx) / 160)

    /** Icon view (like a photo grid): about 115dp per column. */
    fun iconColumns(ctx: Context) = maxOf(4, contentWidthDp(ctx) / 76)

    /** Most columns that still look good: each column at least ~64dp wide. */
    fun maxIconColumns(ctx: Context) = maxOf(3, contentWidthDp(ctx) / 64)

    /** A column count chosen in portrait, scaled to the current width (landscape shows more). */
    fun scaledColumns(ctx: Context, portraitCols: Int): Int {
        val c = ctx.resources.configuration
        val portraitW = minOf(c.screenWidthDp, c.screenHeightDp).coerceAtLeast(1)
        return Math.round(portraitCols * contentWidthDp(ctx).toFloat() / portraitW)
    }

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
            else -> if (isTv(ctx) || isCar(ctx)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
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

    /** iOS card: flat rounded surface (light grey on white, dark grey on black), no outline. */
    fun styleCard(card: MaterialCardView) {
        val ctx = card.context
        card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.card_bg))
        card.strokeColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.focus_glow)
        card.strokeWidth = 0
        card.cardElevation = 0f
    }

    /**
     * Interactive Liquid Glass: on touch the card presses in, a soft light follows the finger,
     * and it springs back when released.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun liquidTouch(v: View) {
        if (!liquid(v.context)) { v.setOnTouchListener(null); return }
        val light = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0x55FFFFFF, 0x00FFFFFF)
        }
        v.setOnTouchListener { view, e ->
            val w = view.width.coerceAtLeast(1); val h = view.height.coerceAtLeast(1)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    light.gradientRadius = maxOf(w, h) * 0.6f
                    light.setGradientCenter(e.x / w, e.y / h)
                    if (android.os.Build.VERSION.SDK_INT >= 23) view.foreground = light
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).start()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    light.setGradientCenter((e.x / w).coerceIn(0f, 1f), (e.y / h).coerceIn(0f, 1f))
                    light.invalidateSelf()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                    view.postDelayed({ if (android.os.Build.VERSION.SDK_INT >= 23) view.foreground = null }, 220)
                }
            }
            false // let normal click / long-click still work
        }
    }

    // ---- screen transitions for the extra screens (player, notepad, Office, USB tools)
    @Suppress("DEPRECATION")
    fun enterRise(act: Activity) { if (Prefs(act).animations) act.overridePendingTransition(R.anim.slide_up, R.anim.stay) }
    @Suppress("DEPRECATION")
    fun exitDrop(act: Activity) { if (Prefs(act).animations) act.overridePendingTransition(R.anim.stay, R.anim.slide_down) }
    @Suppress("DEPRECATION")
    fun enterSlide(act: Activity) { if (Prefs(act).animations) act.overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out) }
    @Suppress("DEPRECATION")
    fun exitSlide(act: Activity) { if (Prefs(act).animations) act.overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right) }

    /** Android 12+: real blur of a view (used behind the sidebar and sheets). */
    fun blur(v: View, radius: Float) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            v.setRenderEffect(if (radius < 0.5f) null else
                android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
        }
    }

    /** Applies the user's transparency to a color (glass effect). */
    fun glass(ctx: Context, colorRes: Int): Int {
        val c = androidx.core.content.ContextCompat.getColor(ctx, colorRes)
        val p = Prefs(ctx)
        val t = if (p.liquidGlass) p.transparency.coerceIn(0, 25) else p.transparency
        val alpha = (255 * (100 - t) / 100).coerceIn(190, 255) // always readable
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
        val musicSw = com.google.android.material.materialswitch.MaterialSwitch(activity).apply {
            setText(R.string.music_notification); isChecked = prefs.musicNotification; textSize = 16f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        box.addView(musicSw)
        val animSw = com.google.android.material.materialswitch.MaterialSwitch(activity).apply {
            setText(R.string.animations); isChecked = prefs.animations; textSize = 16f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        box.addView(animSw)
        box.addView(android.widget.TextView(activity).apply { setText(R.string.transparency_hint); alpha = 0.7f })
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.appearance)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newTheme = group.checkedRadioButtonId - 100
                val newT = slider.value.toInt()
                val changed = newTheme != prefs.theme || newT != prefs.transparency || liquidSw.isChecked != prefs.liquidGlass
                prefs.theme = newTheme; prefs.transparency = newT; prefs.liquidGlass = liquidSw.isChecked
                prefs.animations = animSw.isChecked
                if (musicSw.isChecked != prefs.musicNotification) { prefs.musicNotification = musicSw.isChecked; MusicPlayer.applyNotificationSetting() }
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

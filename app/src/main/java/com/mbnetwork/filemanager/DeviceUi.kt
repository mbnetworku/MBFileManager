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

    /** Most columns that still look good: each column at least ~88dp wide. */
    fun maxIconColumns(ctx: Context) = maxOf(2, contentWidthDp(ctx) / 88)

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

    /** Liquid Glass backdrop: smooth blue / teal / cyan glows (no pink, no hard edges). */
    fun liquidBackground(ctx: Context): android.graphics.drawable.Drawable {
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return LiquidBackdrop(night)
    }

    /** Floating rounded glass pill (bottom bar). */
    fun pill(ctx: Context): android.graphics.drawable.Drawable {
        val dd = ctx.resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 34 * dd
            val base = androidx.core.content.ContextCompat.getColor(ctx, R.color.card_bg)
            setColor((base and 0x00FFFFFF) or ((if (liquid(ctx)) 0xC8 else 0xF2) shl 24))
            setStroke(dd.toInt().coerceAtLeast(1), if (liquid(ctx)) 0x66FFFFFF else 0x22000000)
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
        val t = if (p.liquidGlass) p.transparency.coerceIn(15, 28) else p.transparency
        val alpha = (255 * (100 - t) / 100).coerceIn(150, 255) // always readable
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

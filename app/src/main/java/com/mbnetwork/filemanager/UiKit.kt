package com.mbnetwork.filemanager

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat

/** Small helpers for the dark full-screen viewer / editor screens. */
object UiKit {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun iconButton(ctx: Context, res: Int, desc: String, onClick: () -> Unit) = ImageButton(ctx).apply {
        setImageResource(res)
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(Color.WHITE))
        val tv = TypedValue(); ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        contentDescription = desc
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp(ctx, 11); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 46), dp(ctx, 46))
        setOnClickListener { onClick() }
    }

    /** A rounded pill (chip / button). */
    fun pill(ctx: Context, text: String, selected: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 50).toFloat()
            setColor(if (selected) 0xFF0A84FF.toInt() else 0x33FFFFFF)
        }
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(ctx, 8) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Icon with a small label under it (editor tool tabs). */
    fun toolTab(ctx: Context, res: Int, label: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val c = if (selected) 0xFF5AA9FF.toInt() else Color.WHITE
        addView(ImageView(ctx).apply {
            setImageResource(res)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(c))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24))
        })
        addView(TextView(ctx).apply { text = label; textSize = 11f; setTextColor(c); setPadding(0, dp(ctx, 3), 0, 0) })
        setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    fun scrim(top: Boolean) = GradientDrawable(
        if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(0xCC000000.toInt(), 0x00000000))
}

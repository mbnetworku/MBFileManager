package com.mbnetwork.filemanager

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Small dialogs that ask for a password. Nothing is stored; the password only lives in memory. */
object PasswordDialog {

    fun field(ctx: Context, hint: String) = EditText(ctx).apply {
        this.hint = hint
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    /** Asks for the password of a locked file. [onOk] gets the password, [onCancel] runs on cancel. */
    fun ask(ctx: Context, fileName: String, wrong: Boolean, onCancel: () -> Unit = {}, onOk: (String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val input = field(ctx, ctx.getString(R.string.password))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt()
            setPadding(p, p / 2, p, 0)
            if (wrong) addView(TextView(ctx).apply { text = ctx.getString(R.string.wrong_password); setTextColor(0xFFFF3B30.toInt()) })
            addView(input)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.password_needed, fileName))
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    /** Name + optional password (for creating an archive). */
    fun nameAndPassword(ctx: Context, title: Int, name: String, askPassword: Boolean, onOk: (String, String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val n = EditText(ctx).apply { setText(name); setSingleLine(); selectAll() }
        val pw = field(ctx, ctx.getString(R.string.password_optional))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt()
            setPadding(p, p / 2, p, 0)
            addView(n)
            if (askPassword) addView(pw)
        }
        MaterialAlertDialogBuilder(ctx).setTitle(title).setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val nm = n.text.toString().trim()
                if (nm.isNotEmpty() && !nm.contains('/')) onOk(nm, pw.text.toString())
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}

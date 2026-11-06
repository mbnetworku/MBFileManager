package com.mbnetwork.filemanager

import android.content.Context
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** A window to pick a destination folder anywhere: phone storage, SD card or USB drive. */
object FolderPicker {

    fun show(ctx: Context, start: String, title: String, onPick: (String) -> Unit) {
        var cur = File(start).let { if (it.isDirectory) it else Environment.getExternalStorageDirectory() }
        val d = ctx.resources.displayMetrics.density
        val head = TextView(ctx).apply {
            textSize = 13f; setPadding((20 * d).toInt(), (14 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.START
        }
        val list = ListView(ctx)
        val adapter = ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        var dirs: List<File> = emptyList()
        fun load() {
            dirs = (cur.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
            adapter.clear(); adapter.add("↑  ..")
            dirs.forEach { adapter.add(it.name) }
            head.text = cur.path
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos == 0) cur = cur.parentFile ?: cur else cur = dirs[pos - 1]
            load()
        }
        load()
        val box = FrameLayout(ctx).apply {
            addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (340 * d).toInt()))
        }
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(head); addView(box)
            })
            .setPositiveButton(R.string.pick_here) { _, _ -> onPick(cur.path) }
            .setNeutralButton(R.string.storage_roots) { _, _ -> roots(ctx, cur, title, onPick) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
    }

    /** Jump to another storage, or make a new folder in the current one. */
    private fun roots(ctx: Context, cur: File, title: String, onPick: (String) -> Unit) {
        val vols = StorageVolumes.list(ctx)
        val names = vols.map { it.label } + ctx.getString(R.string.new_folder_here)
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.storage_roots)
            .setItems(names.toTypedArray()) { _, i ->
                if (i < vols.size) show(ctx, vols[i].path, title, onPick)
                else {
                    val et = EditText(ctx).apply { setSingleLine() }
                    val b = FrameLayout(ctx).apply { val p = (20 * ctx.resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0); addView(et) }
                    MaterialAlertDialogBuilder(ctx).setTitle(R.string.new_folder_here).setView(b)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            val n = et.text.toString().trim()
                            val f = File(cur, n)
                            if (n.isNotEmpty() && !n.contains('/') && (f.isDirectory || f.mkdirs())) show(ctx, f.path, title, onPick)
                            else { Toast.makeText(ctx, R.string.failed_short, Toast.LENGTH_SHORT).show(); show(ctx, cur.path, title, onPick) }
                        }.setNegativeButton(R.string.cancel) { _, _ -> show(ctx, cur.path, title, onPick) }.show()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> show(ctx, cur.path, title, onPick) }
            .show()
    }
}

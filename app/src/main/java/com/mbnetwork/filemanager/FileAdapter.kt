package com.mbnetwork.filemanager

import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class FileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem, View) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    var items: List<FileItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.info)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        val ctx = h.itemView.context
        h.name.text = f.name
        h.name.alpha = if (f.isHidden) 0.6f else 1f
        h.icon.setImageResource(
            when {
                f.isDir -> R.drawable.ic_folder
                f.name.substringAfterLast('.', "").lowercase() in IMAGE_EXT -> R.drawable.ic_image
                else -> R.drawable.ic_file
            }
        )
        val date = if (f.lastModified > 0)
            DateFormat.getDateFormat(ctx).format(Date(f.lastModified)) + " " +
                DateFormat.getTimeFormat(ctx).format(Date(f.lastModified)) else ""
        h.info.text = if (f.isDir) date else listOf(Formatter.formatFileSize(ctx, f.size), date)
            .filter { it.isNotEmpty() }.joinToString("  •  ")
        h.itemView.setOnClickListener { onClick(f) }
        h.itemView.setOnLongClickListener { onLongClick(f, it); true }
    }

    companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    }
}

package com.mbnetwork.filemanager

import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class FileAdapter(
    private val grid: Boolean,
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem, View) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    var items: List<FileItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.info)
        val chevron: View? = v.findViewById(R.id.chevron)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(if (grid) R.layout.item_file_grid else R.layout.item_file, parent, false)
        DeviceUi.applyFocusEffect(v)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        val ctx = h.itemView.context
        h.name.text = f.name
        h.itemView.alpha = if (f.isHidden) 0.6f else 1f
        val (icon, color) = typeOf(f)
        h.icon.setImageResource(icon)
        ImageViewCompat.setImageTintList(h.icon, ColorStateList.valueOf(0xFFFFFFFF.toInt()))
        ViewCompat.setBackgroundTintList(h.icon, ColorStateList.valueOf(ContextCompat.getColor(ctx, color)))
        h.chevron?.visibility = if (f.isDir) View.VISIBLE else View.INVISIBLE
        val date = if (f.lastModified > 0) DateFormat.getDateFormat(ctx).format(Date(f.lastModified)) +
            (if (grid) "" else " " + DateFormat.getTimeFormat(ctx).format(Date(f.lastModified))) else ""
        h.info.text = if (f.isDir) date else listOf(Formatter.formatShortFileSize(ctx, f.size), date)
            .filter { it.isNotEmpty() }.joinToString("  •  ")
        h.itemView.setOnClickListener { onClick(f) }
        h.itemView.setOnLongClickListener { onLongClick(f, it); true }
    }

    companion object {
        private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
        private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts", "m4v")
        private val AUDIO = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "amr")
        private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2", "obb")
        private val DOC = setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "csv", "md", "json", "xml", "html", "log")

        fun typeOf(f: FileItem): Pair<Int, Int> {
            if (f.isDir) return R.drawable.ic_folder to R.color.t_folder
            return when (f.name.substringAfterLast('.', "").lowercase()) {
                in IMAGE -> R.drawable.ic_image to R.color.t_image
                in VIDEO -> R.drawable.ic_video to R.color.t_video
                in AUDIO -> R.drawable.ic_audio to R.color.t_audio
                "apk" -> R.drawable.ic_android to R.color.t_apk
                in ARCHIVE -> R.drawable.ic_archive to R.color.t_archive
                in DOC -> R.drawable.ic_doc to R.color.t_doc
                else -> R.drawable.ic_file to R.color.t_file
            }
        }
    }
}

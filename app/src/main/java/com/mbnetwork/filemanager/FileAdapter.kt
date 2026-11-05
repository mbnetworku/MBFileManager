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
import com.google.android.material.card.MaterialCardView
import java.util.Date

/**
 * Three looks: ICONS (big icons + photo thumbnails), LIST (cards), GRID (cards for tablet/TV).
 * Supports multi-select with check marks.
 */
class FileAdapter(
    private val mode: Int,
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem, View) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    var items: List<FileItem> = emptyList()
        set(v) { field = v; selected.retainAll(v.map { it.path }.toSet()); notifyDataSetChanged() }

    var selectionMode = false
        set(v) { field = v; if (!v) selected.clear(); notifyDataSetChanged() }
    val selected = LinkedHashSet<String>()
    var glassColor: Int? = null
    /** Icon view: number of columns (thumbnail size follows the column width). */
    var columns = 3

    fun toggle(f: FileItem) {
        if (!selected.remove(f.path)) selected += f.path
        notifyItemChanged(items.indexOf(f))
    }

    fun clearSelection() { selected.clear(); notifyDataSetChanged() }

    fun selectAll() { selected.clear(); items.forEach { selected += it.path }; notifyDataSetChanged() }
    fun selectedItems() = items.filter { it.path in selected }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.info)
        val check: ImageView? = v.findViewById(R.id.check)
        val chevron: View? = v.findViewById(R.id.chevron)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (mode) { ICONS -> R.layout.item_file_icon; GRID -> R.layout.item_file_grid; else -> R.layout.item_file }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        DeviceUi.applyFocusEffect(v)
        DeviceUi.liquidTouch(v)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        val ctx = h.itemView.context
        h.name.text = f.name
        val tagColor = Tags.color(ctx, f.path)
        if (tagColor != null) {
            val dd = ctx.resources.displayMetrics.density
            val dot = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(tagColor); setSize((9 * dd).toInt(), (9 * dd).toInt())
            }
            h.name.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            h.name.compoundDrawablePadding = (6 * dd).toInt()
        } else h.name.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        h.itemView.alpha = if (f.isHidden) 0.6f else 1f
        (h.itemView as? MaterialCardView)?.let { DeviceUi.styleCard(it) }

        // Windows-style file icon (yellow folder / white page with a type label); real preview if available
        if (mode == ICONS) sizeIconMode(h)
        bindFileIcon(h.icon, f)

        h.chevron?.visibility = if (f.isDir && !selectionMode) View.VISIBLE else View.GONE
        val isSel = f.path in selected
        h.check?.visibility = if (selectionMode) View.VISIBLE else View.GONE
        h.check?.setImageResource(if (isSel) R.drawable.ic_check_circle else R.drawable.ic_circle)
        h.check?.let { ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(
            if (isSel) ContextCompat.getColor(ctx, R.color.icon_folder) else 0xFFB0B0B0.toInt())) }
        h.itemView.isActivated = isSel

        val date = if (f.lastModified > 0) DateFormat.getDateFormat(ctx).format(Date(f.lastModified)) +
            (if (mode == LIST) " " + DateFormat.getTimeFormat(ctx).format(Date(f.lastModified)) else "") else ""
        val size = if (f.isDir) "" else Formatter.formatShortFileSize(ctx, f.size)
        h.info.text = if (mode == ICONS) (if (columns >= 6) size.ifEmpty { date } else listOf(date, size).filter { it.isNotEmpty() }.joinToString("\n"))
            else listOf(size, date).filter { it.isNotEmpty() }.joinToString("  •  ")
        h.itemView.setOnClickListener { onClick(f) }
        h.itemView.setOnLongClickListener { onLongClick(f, it); true }
    }

    /** Thumbnail size follows the column width so icons are never squeezed. */
    private fun sizeIconMode(h: VH) {
        val ctx = h.itemView.context
        val d = ctx.resources.displayMetrics.density
        val parentW = (h.itemView.parent as? View)?.width?.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val size = ((parentW / columns) - (18 * d)).toInt().coerceIn((40 * d).toInt(), (64 * d).toInt())
        (h.icon.parent as View).layoutParams = (h.icon.parent as View).layoutParams.apply { width = size; height = size }
        h.name.maxLines = 2
        h.name.ellipsize = android.text.TextUtils.TruncateAt.END
        h.name.textSize = if (columns >= 6) 11f else 12.5f
        h.info.textSize = 10f
    }

    private fun bindFileIcon(img: ImageView, f: FileItem) {
        val ctx = img.context
        val d = ctx.resources.displayMetrics.density
        img.tag = null
        img.setImageDrawable(FileIcons.drawable(f))
        img.background = null
        ViewCompat.setBackgroundTintList(img, null)
        ImageViewCompat.setImageTintList(img, null)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setPadding(0, 0, 0, 0)
        img.clipToOutline = false
        if (!f.isDir && ThumbLoader.canThumb(f.name)) ThumbLoader.load(ctx, f.path, img) {
            if (f.name.lowercase().endsWith(".apk")) {
                // app icon of the APK
                val q = (3 * d).toInt()
                img.setPadding(q, q, q, q)
            } else {
                // photo / video frame / album art / PDF page inside a thin rounded frame (like Windows thumbnails)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setBackgroundResource(R.drawable.bg_thumb_win)
                val q = d.toInt().coerceAtLeast(1)
                img.setPadding(q, q, q, q)
                img.clipToOutline = true
            }
        }
    }

    companion object {
        const val ICONS = 0
        const val LIST = 1
        const val GRID = 2

        private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
        private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts", "m4v")
        private val AUDIO = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "amr")
        private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2", "obb")
        private val DOC = setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "csv", "md", "json", "xml", "html", "log")

        fun typeOf(f: FileItem): Pair<Int, Int> {
            if (f.isDir) return R.drawable.ic_folder to R.color.t_folder
            if (OfficeDoc.isOffice(f.name)) return when (OfficeDoc.kind(f.name)) {
                "xlsx" -> R.drawable.ic_doc to R.color.t_excel
                "pptx" -> R.drawable.ic_doc to R.color.t_ppt
                else -> R.drawable.ic_doc to R.color.t_doc
            }
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

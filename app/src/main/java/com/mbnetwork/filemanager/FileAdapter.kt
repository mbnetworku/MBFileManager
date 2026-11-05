package com.mbnetwork.filemanager

import android.content.res.ColorStateList
import android.graphics.Outline
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
        h.itemView.alpha = if (f.isHidden) 0.6f else 1f
        (h.itemView as? MaterialCardView)?.let { DeviceUi.styleCard(it) }

        val (icon, color) = typeOf(f)
        h.icon.setImageResource(icon)
        h.icon.tag = null
        if (mode == ICONS) bindIconMode(h, f, icon, color) else {
            // glossy gradient tile with white symbol; real thumbnail when available
            val dd = ctx.resources.displayMetrics.density
            h.icon.background = DeviceUi.tile(ctx, ContextCompat.getColor(ctx, color), 15f)
            ViewCompat.setBackgroundTintList(h.icon, null)
            ImageViewCompat.setImageTintList(h.icon, ColorStateList.valueOf(0xFFFFFFFF.toInt()))
            h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = ((if (mode == GRID) 16 else 11) * dd).toInt(); h.icon.setPadding(p, p, p, p)
            h.icon.clipToOutline = true
            h.icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 15 * dd)
            }
            if (!f.isDir && ThumbLoader.canThumb(f.name)) ThumbLoader.load(ctx, f.path, h.icon) {
                ImageViewCompat.setImageTintList(h.icon, null)
                val apk = f.name.lowercase().endsWith(".apk")
                h.icon.setPadding(if (apk) p / 3 else 0, if (apk) p / 3 else 0, if (apk) p / 3 else 0, if (apk) p / 3 else 0)
                h.icon.scaleType = if (apk) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            }
        }

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
        h.info.text = if (mode == ICONS) (if (columns >= 5) size.ifEmpty { date } else listOf(date, size).filter { it.isNotEmpty() }.joinToString("\n"))
            else listOf(size, date).filter { it.isNotEmpty() }.joinToString("  •  ")
        h.itemView.setOnClickListener { onClick(f) }
        h.itemView.setOnLongClickListener { onLongClick(f, it); true }
    }

    /** Big icons: folders without background, photos/videos/APKs show a real preview. */
    private fun bindIconMode(h: VH, f: FileItem, icon: Int, color: Int) {
        val ctx = h.itemView.context
        val d = ctx.resources.displayMetrics.density
        // square thumbnail that fits the column (never squeezed)
        val parentW = (h.itemView.parent as? View)?.width?.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val size = ((parentW / columns) - (18 * d)).toInt().coerceIn((40 * d).toInt(), (64 * d).toInt())
        (h.icon.parent as View).layoutParams = (h.icon.parent as View).layoutParams.apply { width = size; height = size }
        h.name.maxLines = 2
        h.name.ellipsize = android.text.TextUtils.TruncateAt.END
        h.name.textSize = if (columns >= 6) 11f else 12.5f
        h.info.textSize = 10f
        val radiusDp = size / d * 0.26f // soft "squircle" corners
        h.icon.clipToOutline = true
        h.icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radiusDp * d)
        }
        h.icon.background = DeviceUi.tile(ctx, ContextCompat.getColor(ctx, color), radiusDp)
        ViewCompat.setBackgroundTintList(h.icon, null)
        ImageViewCompat.setImageTintList(h.icon, ColorStateList.valueOf(0xFFFFFFFF.toInt()))
        h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val p = (size * 0.24f).toInt(); h.icon.setPadding(p, p, p, p)
        if (ThumbLoader.canThumb(f.name)) {
            ThumbLoader.load(ctx, f.path, h.icon) {
                ImageViewCompat.setImageTintList(h.icon, null)
                h.icon.setPadding(0, 0, 0, 0)
                val apk = f.name.lowercase().endsWith(".apk")
                h.icon.scaleType = if (apk) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                if (apk) { val q = (10 * d).toInt(); h.icon.setPadding(q, q, q, q); h.icon.background = DeviceUi.tile(ctx, 0xFF607D8B.toInt(), radiusDp) }
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

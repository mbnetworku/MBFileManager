package com.mbnetwork.filemanager

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.text.format.Formatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Date

/** "Photo info": resolution, file, camera and shooting details read from the picture's EXIF data. */
object ExifInfo {

    fun show(ctx: Context, path: String) {
        val f = File(path)
        val msg = StringBuilder()
        fun line(k: String, v: String?) { if (!v.isNullOrBlank()) msg.append(k).append(": ").append(v.trim()).append('\n') }
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            line("Resolution", if (o.outWidth > 0) "${o.outWidth} × ${o.outHeight}  (${"%.1f".format(o.outWidth * o.outHeight / 1_000_000.0)} MP)" else null)
        } catch (e: Throwable) { }
        line("File", f.name)
        line("Path", f.parent)
        line("Size", Formatter.formatFileSize(ctx, f.length()) + " (${f.length()} bytes)")
        line("Modified", Date(f.lastModified()).toString())
        try {
            val e = ExifInterface(path)
            line("Camera", listOfNotNull(e.getAttribute("Make"), e.getAttribute("Model")).joinToString(" ").ifBlank { null })
            line("Taken", e.getAttribute("DateTime"))
            line("Exposure", e.getAttribute("ExposureTime")?.let { t -> t.toDoubleOrNull()?.let { d -> if (d in 0.0001..0.99) "1/${Math.round(1 / d)} s" else "$t s" } })
            line("Aperture", e.getAttribute("FNumber")?.let { "f/$it" })
            line("ISO", e.getAttribute("ISOSpeedRatings"))
            line("Focal length", e.getAttribute("FocalLength"))
            line("Flash", e.getAttribute("Flash"))
            line("Orientation", e.getAttribute("Orientation"))
            line("Software", e.getAttribute("Software"))
            val ll = FloatArray(2)
            if (e.getLatLong(ll)) line("Location", "%.5f, %.5f".format(ll[0], ll[1]))
        } catch (t: Throwable) { }
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.photo_info).setMessage(msg.toString().trim())
            .setPositiveButton(R.string.ok, null).show()
    }
}

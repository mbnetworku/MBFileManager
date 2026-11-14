package com.mbnetwork.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Loads photo / video thumbnails and APK icons in the background, with a memory cache. */
object ThumbLoader {
    private val cache = object : LruCache<String, Any>(120) {}
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv")
    private val AUDIO = setOf("mp3", "m4a", "flac", "ogg", "aac", "wav", "opus", "wma")

    fun canThumb(name: String): Boolean {
        val e = name.substringAfterLast('.', "").lowercase()
        return e in IMAGE || e in VIDEO || e in AUDIO || e == "apk" || e == "pdf"
    }

    /** Loads into [view]; [onLoaded] is called only if a thumbnail was found. */
    fun load(ctx: Context, path: String, view: ImageView, onLoaded: () -> Unit) {
        view.tag = path
        cache.get(path)?.let { set(view, it); onLoaded(); return }
        val app = ctx.applicationContext
        pool.execute {
            if (view.tag != path) return@execute   // scrolled away before this task started: skip the work
            val r: Any? = try { make(app, path) } catch (e: Throwable) { null }
            if (r != null) cache.put(path, r)
            main.post { if (view.tag == path && r != null) { set(view, r); onLoaded() } }
        }
    }

    private fun set(v: ImageView, r: Any) {
        when (r) { is Bitmap -> v.setImageBitmap(r); is Drawable -> v.setImageDrawable(r) }
    }

    @Suppress("DEPRECATION")
    private fun make(ctx: Context, path: String): Any? {
        val e = path.substringAfterLast('.', "").lowercase()
        return when {
            e in IMAGE -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 200 && o.outHeight / (s * 2) >= 200) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
            }
            e in VIDEO -> ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
            e in AUDIO -> android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(path)
                    embeddedPicture?.let { b ->
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(b, 0, b.size, o)
                        var s = 1
                        while (o.outWidth / (s * 2) >= 200) s *= 2
                        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
                    }
                } finally { release() }
            }
            e == "pdf" -> android.os.ParcelFileDescriptor.open(java.io.File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { r ->
                    r.openPage(0).use { p ->
                        val w = 240; val h = (w * p.height / p.width.coerceAtLeast(1)).coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(0xFFFFFFFF.toInt())
                            p.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            e == "apk" -> ctx.packageManager.getPackageArchiveInfo(path, 0)?.applicationInfo?.let {
                it.sourceDir = path; it.publicSourceDir = path
                it.loadIcon(ctx.packageManager)
            }
            else -> null
        }
    }
}

package com.mbnetwork.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever

/**
 * Look of the music player: a background taken from the song's cover colour, liquid-glass panels and small animations.
 * Only colours and shapes are set here; the controls and their positions are the same as before.
 */
object PlayerDesign {

    const val DEFAULT_TOP = 0xFF1C2433.toInt()
    const val DEFAULT_BOTTOM = 0xFF05070C.toInt()

    fun gradient(top: Int, bottom: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))

    /** Frosted glass: translucent white with a bright rim, like iOS liquid glass. */
    fun glassPanel(ctx: Context, radiusDp: Float) = GradientDrawable().apply {
        val d = ctx.resources.displayMetrics.density
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * d
        setColor(0x2EFFFFFF)
        setStroke(maxOf(1, d.toInt()), 0x59FFFFFF)
    }

    fun glassCircle(ctx: Context) = GradientDrawable().apply {
        val d = ctx.resources.displayMetrics.density
        shape = GradientDrawable.OVAL
        setColor(0x2EFFFFFF)
        setStroke(maxOf(1, d.toInt()), 0x55FFFFFF)
    }

    fun whiteCircle() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFFFFFFFF.toInt())
    }

    /** Mixes the colour with black by [f] (0 = same colour, 1 = black). */
    fun shade(c: Int, f: Float): Int {
        val k = 1f - f
        val r = (((c shr 16) and 0xFF) * k).toInt()
        val g = (((c shr 8) and 0xFF) * k).toInt()
        val b = ((c and 0xFF) * k).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /** Average colour of the song's embedded cover, or null when there is none. Call on a background thread. */
    fun dominant(path: String): Int? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            val bytes = mmr.embeddedPicture ?: return null
            val opt = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) ?: return null
            val one = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
            bmp.recycle()
            return one or 0xFF000000.toInt()
        } catch (e: Throwable) {
            return null
        } finally {
            try { mmr.release() } catch (e: Throwable) { }
        }
    }
}

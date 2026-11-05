package com.mbnetwork.filemanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface

object ImageUtil {
    /** Decodes [path] scaled down so neither side is bigger than [maxDim], and applies the EXIF rotation. */
    fun decode(path: String, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxDim || o.outHeight / (s * 2) >= maxDim) s *= 2
        val bm = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val deg = exifDegrees(path)
        return if (deg == 0) bm else rotate(bm, deg.toFloat())
    }

    fun exifDegrees(path: String): Int = try {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) { 0 }

    fun rotate(bm: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(degrees) }, true)

    fun flip(bm: Bitmap, horizontal: Boolean): Bitmap =
        Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height,
            Matrix().apply { if (horizontal) postScale(-1f, 1f) else postScale(1f, -1f) }, true)
}

package com.mbnetwork.filemanager

import android.webkit.MimeTypeMap

object MediaTypes {
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isImage(name: String) = ext(name) in IMAGE
    fun isVideo(name: String) = ext(name) in VIDEO
    fun mime(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name)) ?: "*/*"
}

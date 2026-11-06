package com.mbnetwork.filemanager

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Finds files by category / name using Android's media index (fast, no full scan). */
object FileIndex {

    data class Category(val key: String, val title: Int, val icon: Int, val color: Int, val exts: List<String>)

    val categories = listOf(
        Category("img", R.string.cat_images, R.drawable.ic_image, R.color.t_image, listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")),
        Category("vid", R.string.cat_videos, R.drawable.ic_video, R.color.t_video, listOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")),
        Category("aud", R.string.cat_music, R.drawable.ic_audio, R.color.t_audio, listOf("mp3", "m4a", "flac", "ogg", "wav", "aac", "opus", "amr")),
        Category("doc", R.string.cat_docs, R.drawable.ic_doc, R.color.t_doc, listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md", "odt", "rtf")),
        Category("apk", R.string.cat_apks, R.drawable.ic_android, R.color.t_apk, listOf("apk")),
        Category("zip", R.string.cat_archives, R.drawable.ic_zip, R.color.t_archive, listOf("zip", "rar", "7z", "tar", "tgz", "gz", "obb")),
        Category("dl", R.string.downloads, R.drawable.ic_archive, R.color.grad_start, emptyList()),
        Category("png", R.string.cat_png, R.drawable.ic_image, R.color.t_image, listOf("png")),
        Category("raw", R.string.cat_raw, R.drawable.ic_image, R.color.t_audio, listOf("dng", "cr2", "nef", "arw", "raf", "orf", "rw2")),
        Category("dup", R.string.cat_dup, R.drawable.ic_copy, R.color.danger, emptyList()),
        Category("big", R.string.cat_large, R.drawable.ic_storage, R.color.danger, emptyList())
    )

    private val uri get() = MediaStore.Files.getContentUri("external")

    @Suppress("DEPRECATION")
    private fun query(ctx: Context, sel: String?, args: Array<String>?, order: String, limit: Int): List<FileItem> {
        val out = mutableListOf<FileItem>()
        val proj = arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED)
        try {
            ctx.contentResolver.query(uri, proj, sel, args, order)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val path = c.getString(0) ?: continue
                    val f = File(path)
                    if (!f.isFile) continue
                    if (path.contains("/${Trash.DIR_NAME}/")) continue
                    out += FileItem(f.name, path, false, c.getLong(1), c.getLong(2) * 1000)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun extSel(exts: List<String>): Pair<String, Array<String>> =
        exts.joinToString(" OR ", "(", ")") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" } to exts.map { "%.$it" }.toTypedArray()

    fun list(ctx: Context, key: String, limit: Int = 2000): List<FileItem> {
        val c = categories.first { it.key == key }
        return when (key) {
            "dl" -> {
                val dir = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)
                (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
                    .map { FileItem(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
                    .sortedByDescending { it.lastModified }
            }
            "dup" -> {
                val media = (categories.first { it.key == "img" }.exts + categories.first { it.key == "vid" }.exts)
                val (s, a) = extSel(media)
                DuplicateFinder.find(query(ctx, s, a, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", 100000))
            }
            "big" -> query(ctx, "${MediaStore.Files.FileColumns.SIZE} > ?", arrayOf("${50L * 1024 * 1024}"),
                "${MediaStore.Files.FileColumns.SIZE} DESC", limit)
            else -> { val (s, a) = extSel(c.exts); query(ctx, s, a, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit) }
        }
    }

    fun count(ctx: Context, key: String): Int = list(ctx, key, 100000).size

    fun recent(ctx: Context, limit: Int = 20) = query(ctx,
        "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL", null,
        "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit)

    fun search(ctx: Context, text: String, limit: Int = 500) = query(ctx,
        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("%$text%"),
        "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit)
}

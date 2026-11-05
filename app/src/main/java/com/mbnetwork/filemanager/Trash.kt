package com.mbnetwork.filemanager

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/** Recycle Bin: deleted files are moved into a hidden folder on the same storage so they can be restored. */
object Trash {
    private const val DIR = ".MBFileManagerTrash"
    const val DIR_NAME = DIR
    private const val META = ".origin"

    data class Entry(val file: File, val originalPath: String, val deletedAt: Long)

    fun isTrashDir(name: String) = name == DIR

    /** Internal storage + SD cards / USB drives. */
    fun volumes(ctx: Context): List<File> {
        val list = mutableListOf(Environment.getExternalStorageDirectory())
        ContextCompat.getExternalFilesDirs(ctx, null).drop(1).filterNotNull()
            .forEach { list += File(it.absolutePath.substringBefore("/Android/data")) }
        return list
    }

    private fun volumeOf(ctx: Context, path: String) =
        volumes(ctx).firstOrNull { path.startsWith(it.absolutePath + "/") }

    /** True when this file can go to the Recycle Bin (it is on internal storage / SD card). */
    fun canTrash(ctx: Context, path: String) = volumeOf(ctx, path) != null

    fun moveToTrash(ctx: Context, path: String) {
        val vol = volumeOf(ctx, path) ?: throw IOException("Not on a storage volume")
        val src = File(path)
        val dir = File(vol, DIR).apply { mkdirs() }
        File(dir, ".nomedia").createNewFile()
        val id = "${System.currentTimeMillis()}_${src.name}"
        val target = File(dir, id)
        if (!src.renameTo(target)) {
            if (!src.copyRecursively(target)) throw IOException("Cannot move to Recycle Bin")
            src.deleteRecursively()
        }
        File(dir, "$id$META").writeText(path)
    }

    fun list(ctx: Context): List<Entry> = volumes(ctx).flatMap { vol ->
        val dir = File(vol, DIR)
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.endsWith(META) && it.name != ".nomedia" }
            .map { f ->
                val meta = File(dir, f.name + META)
                val orig = if (meta.exists()) meta.readText() else File(vol, f.name.substringAfter('_')).path
                Entry(f, orig, f.name.substringBefore('_').toLongOrNull() ?: f.lastModified())
            }
    }.sortedByDescending { it.deletedAt }

    fun restore(e: Entry): String {
        var target = File(e.originalPath)
        target.parentFile?.mkdirs()
        var i = 1
        while (target.exists()) {
            val o = File(e.originalPath)
            target = File(o.parentFile, "${o.nameWithoutExtension} (restored $i)" + if (o.extension.isNotEmpty()) ".${o.extension}" else "")
            i++
        }
        if (!e.file.renameTo(target)) {
            if (!e.file.copyRecursively(target)) throw IOException("Cannot restore")
            e.file.deleteRecursively()
        }
        File(e.file.parentFile, e.file.name + META).delete()
        return target.path
    }

    fun deleteForever(e: Entry) {
        e.file.deleteRecursively()
        File(e.file.parentFile, e.file.name + META).delete()
    }

    fun empty(ctx: Context) = list(ctx).forEach { deleteForever(it) }
}

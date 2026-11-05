package com.mbnetwork.filemanager

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Compress files/folders into a .zip and extract .zip files (works on Android 5+). */
object ZipUtil {

    fun isZip(name: String) = name.lowercase().endsWith(".zip")

    /** Zips [sources] into [out]. Folders are added recursively. */
    fun zip(sources: List<File>, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            sources.forEach { add(zos, it, it.name) }
        }
    }

    private fun add(zos: ZipOutputStream, f: File, name: String) {
        if (f.isDirectory) {
            zos.putNextEntry(ZipEntry("$name/")); zos.closeEntry()
            f.listFiles()?.forEach { add(zos, it, "$name/${it.name}") }
        } else {
            zos.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
            BufferedInputStream(FileInputStream(f)).use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    /** Extracts [zip] into [destDir]. Blocks "../" paths (zip-slip). */
    fun unzip(zip: File, destDir: File) {
        destDir.mkdirs()
        val base = destDir.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val target = File(destDir, e.name)
                if (!target.canonicalPath.startsWith(base) && target.canonicalPath != destDir.canonicalPath)
                    throw IOException("Unsafe entry: ${e.name}")
                if (e.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(target)).use { zis.copyTo(it) }
                    if (e.time > 0) target.setLastModified(e.time)
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }

    /** Returns a file name that does not exist yet in [dir]. */
    fun uniqueName(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base$ext"); var i = 2
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }
}

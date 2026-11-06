package com.mbnetwork.filemanager

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Finds duplicate photos and videos: same size first, then the same SHA-256 fingerprint.
 * (Files over 64 MB use the first 8 MB + the last 8 MB + the size, which is fast and practically as safe.)
 * Returns every extra copy; the oldest file of each group is kept out of the list.
 */
object DuplicateFinder {
    private const val BIG = 64L * 1024 * 1024
    private const val EDGE = 8 * 1024 * 1024

    fun find(files: List<FileItem>): List<FileItem> {
        val bySize = files.filter { it.size > 0 }.groupBy { it.size }.filter { it.value.size > 1 }
        val out = ArrayList<FileItem>()
        for ((_, same) in bySize) {
            val byHash = HashMap<String, MutableList<FileItem>>()
            for (f in same) {
                val h = try { fingerprint(File(f.path)) } catch (e: Throwable) { null } ?: continue
                byHash.getOrPut(h) { ArrayList() } += f
            }
            for ((_, group) in byHash) if (group.size > 1) {
                val sorted = group.sortedBy { it.lastModified }
                out += sorted.drop(1)
            }
        }
        return out.sortedByDescending { it.size }
    }

    fun fingerprint(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        if (f.length() <= BIG) {
            f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        } else {
            RandomAccessFile(f, "r").use { r ->
                for (start in longArrayOf(0L, f.length() - EDGE)) {
                    r.seek(start); var left = EDGE
                    while (left > 0) { val n = r.read(buf, 0, minOf(buf.size, left)); if (n < 0) break; md.update(buf, 0, n); left -= n }
                }
            }
            md.update(f.length().toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

package com.mbnetwork.filemanager

import com.mbnetwork.filemanager.RootShell.q
import java.io.File
import java.io.IOException

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long
) {
    val isHidden get() = name.startsWith(".")
}

/**
 * File operations. Normal mode uses java.io.File; root mode runs shell commands through su,
 * which lets you browse / and protected folders such as /data and /system.
 */
class FileOps(private val root: Boolean) {

    fun list(path: String): List<FileItem> = if (root) listRoot(path) else listNormal(path)

    private fun listNormal(path: String): List<FileItem> {
        val files = File(path).listFiles() ?: throw IOException("Cannot read $path")
        return files.map { FileItem(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
    }

    private fun listRoot(path: String): List<FileItem> {
        // Works with toolbox (Android 5), toybox and busybox: uses only shell built-ins + optional stat.
        val cmd = "cd ${q(path)} || exit 1\n" +
            "for f in * .*; do\n" +
            "  [ \"\$f\" = . ] && continue; [ \"\$f\" = .. ] && continue\n" +
            "  [ -e \"\$f\" ] || [ -L \"\$f\" ] || continue\n" +
            "  if [ -d \"\$f\" ]; then t=d; else t=f; fi\n" +
            "  echo \"\$t/\$(stat -c '%s %Y' \"\$f\" 2>/dev/null)/\$f\"\n" +
            "done"
        val r = RootShell.run(cmd)
        if (!r.ok) throw IOException(r.err.ifBlank { "Cannot read $path" })
        val base = if (path.endsWith("/")) path else "$path/"
        return r.out.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("/", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val st = parts[1].trim().split(" ")
            FileItem(
                name = parts[2],
                path = base + parts[2],
                isDir = parts[0] == "d",
                size = st.getOrNull(0)?.toLongOrNull() ?: 0L,
                lastModified = (st.getOrNull(1)?.toLongOrNull() ?: 0L) * 1000
            )
        }.toList()
    }

    fun mkdir(parent: String, name: String) {
        val target = File(parent, name)
        if (root) sh("mkdir -p ${q(target.path)}")
        else if (!target.mkdirs()) throw IOException("Cannot create folder")
    }

    fun createFile(parent: String, name: String) {
        val target = File(parent, name)
        if (root) sh("touch ${q(target.path)} || echo -n > ${q(target.path)}")
        else if (!target.createNewFile()) throw IOException("Cannot create file")
    }

    fun delete(path: String) {
        if (root) sh("rm -rf ${q(path)}")
        else if (!File(path).deleteRecursively()) throw IOException("Cannot delete")
    }

    fun rename(path: String, newName: String) {
        val target = File(File(path).parentFile, newName)
        if (root) sh("mv ${q(path)} ${q(target.path)}")
        else if (!File(path).renameTo(target)) throw IOException("Cannot rename")
    }

    fun copy(src: String, destDir: String) {
        val target = uniqueTarget(File(destDir, File(src).name))
        if (root) sh("cp -r ${q(src)} ${q(target.path)} || cp -R ${q(src)} ${q(target.path)}")
        else if (!File(src).copyRecursively(target)) throw IOException("Cannot copy")
    }

    fun move(src: String, destDir: String) {
        val target = uniqueTarget(File(destDir, File(src).name))
        if (root) { sh("mv ${q(src)} ${q(target.path)}"); return }
        if (!File(src).renameTo(target)) {
            if (!File(src).copyRecursively(target)) throw IOException("Cannot move")
            File(src).deleteRecursively()
        }
    }

    private fun uniqueTarget(f: File): File {
        if (!exists(f)) return f
        var i = 1
        while (true) {
            val c = File(f.parentFile, "${f.nameWithoutExtension} ($i)" + if (f.extension.isNotEmpty() && !f.isDirectory) ".${f.extension}" else "")
            if (!exists(c)) return c
            i++
        }
    }

    private fun exists(f: File) = if (root) RootShell.run("[ -e ${q(f.path)} ]").ok else f.exists()

    private fun sh(cmd: String) {
        val r = RootShell.run(cmd)
        if (!r.ok) throw IOException(r.err.ifBlank { "Command failed" })
    }
}

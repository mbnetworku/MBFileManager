package com.mbnetwork.filemanager

import com.github.junrar.Archive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * ZIP, TAR (.tar, .tar.gz, .tgz) and RAR support, all on the phone.
 *  - extract: ZIP, TAR, TAR.GZ, RAR (RAR 4 and older; RAR 5 is not supported by the library)
 *  - create : ZIP, TAR, TAR.GZ (RAR can not be created: the format is closed / licensed)
 */
object ArchiveUtil {
    const val ZIP = 0
    const val TAR = 1
    const val TGZ = 2

    private fun n(f: String) = f.lowercase()
    fun isRar(name: String) = n(name).endsWith(".rar")
    fun isTar(name: String) = n(name).let { it.endsWith(".tar") || it.endsWith(".tar.gz") || it.endsWith(".tgz") }
    fun isArchive(name: String) = ZipUtil.isZip(name) || isRar(name) || isTar(name)

    fun baseName(name: String): String {
        val l = n(name)
        for (e in listOf(".tar.gz", ".tgz", ".tar", ".zip", ".rar")) if (l.endsWith(e)) return name.dropLast(e.length)
        return name.substringBeforeLast('.')
    }

    fun ext(format: Int) = when (format) { TAR -> ".tar"; TGZ -> ".tar.gz"; else -> ".zip" }

    fun extract(src: File, dest: File) {
        val name = src.name
        when {
            ZipUtil.isZip(name) -> ZipUtil.unzip(src, dest)
            isRar(name) -> unrar(src, dest)
            isTar(name) -> untar(src, dest)
            else -> throw IOException("Unknown archive")
        }
    }

    fun create(sources: List<File>, out: File, format: Int) {
        when (format) {
            TAR -> BufferedOutputStream(FileOutputStream(out)).use { tar(sources, it) }
            TGZ -> GZIPOutputStream(BufferedOutputStream(FileOutputStream(out))).use { tar(sources, it) }
            else -> ZipUtil.zip(sources, out)
        }
    }

    private fun safe(dest: File, name: String): File {
        val base = dest.canonicalPath + File.separator
        val t = File(dest, name)
        if (!t.canonicalPath.startsWith(base) && t.canonicalPath != dest.canonicalPath) throw IOException("Unsafe entry: $name")
        return t
    }

    // ------------------------------------------------------------------ RAR

    private fun unrar(src: File, dest: File) {
        dest.mkdirs()
        Archive(src).use { a ->
            if (a.isEncrypted) throw IOException("Password protected RAR")
            var fh = a.nextFileHeader()
            while (fh != null) {
                val target = safe(dest, fh.fileName.replace('\\', '/'))
                if (fh.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(target)).use { a.extractFile(fh, it) }
                }
                fh = a.nextFileHeader()
            }
        }
    }

    // ------------------------------------------------------------------ TAR

    private fun tar(sources: List<File>, out: OutputStream) {
        sources.forEach { put(out, it, it.name) }
        out.write(ByteArray(1024))
    }

    private fun put(out: OutputStream, f: File, name: String) {
        if (f.isDirectory) {
            header(out, "$name/", 0, f.lastModified(), '5')
            f.listFiles()?.forEach { put(out, it, "$name/${it.name}") }
        } else {
            header(out, name, f.length(), f.lastModified(), '0')
            var left = f.length()
            BufferedInputStream(FileInputStream(f)).use { ins ->
                val buf = ByteArray(32 * 1024)
                while (left > 0) {
                    val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) break
                    out.write(buf, 0, r); left -= r
                }
            }
            if (left > 0) out.write(ByteArray(left.toInt()))        // file shrank while reading
            val pad = ((512 - f.length() % 512) % 512).toInt()
            if (pad > 0) out.write(ByteArray(pad))
        }
    }

    private fun header(out: OutputStream, name: String, size: Long, mtime: Long, type: Char) {
        val nb = name.toByteArray(Charsets.UTF_8)
        if (nb.size > 100) {                                           // GNU long name entry
            val data = nb + 0
            rawHeader(out, "././@LongLink".toByteArray(), data.size.toLong(), 0, 'L')
            out.write(data)
            val pad = ((512 - data.size % 512) % 512)
            if (pad > 0) out.write(ByteArray(pad))
            rawHeader(out, nb.copyOf(100), size, mtime / 1000, type)
        } else rawHeader(out, nb, size, mtime / 1000, type)
    }

    private fun rawHeader(out: OutputStream, name: ByteArray, size: Long, mtime: Long, type: Char) {
        val h = ByteArray(512)
        System.arraycopy(name, 0, h, 0, minOf(name.size, 100))
        octal(h, 100, 8, if (type == '5') 493 else 420)
        octal(h, 108, 8, 0); octal(h, 116, 8, 0)
        octal(h, 124, 12, size)
        octal(h, 136, 12, mtime)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(h, 257); h[262] = 0
        h[263] = '0'.code.toByte(); h[264] = '0'.code.toByte()
        var sum = 0
        for (b in h) sum += b.toInt() and 0xFF
        val c = String.format("%06o", sum).toByteArray()
        c.copyInto(h, 148); h[154] = 0; h[155] = ' '.code.toByte()
        out.write(h)
    }

    private fun octal(h: ByteArray, off: Int, len: Int, v: Long) {
        val s = java.lang.Long.toOctalString(v).padStart(len - 1, '0').toByteArray()
        s.copyInto(h, off); h[off + len - 1] = 0
    }

    private fun untar(src: File, dest: File) {
        dest.mkdirs()
        val raw = BufferedInputStream(FileInputStream(src))
        val ins: InputStream = if (src.name.lowercase().let { it.endsWith(".gz") || it.endsWith(".tgz") }) GZIPInputStream(raw) else raw
        ins.use { s ->
            val h = ByteArray(512)
            var longName: String? = null
            while (readFully(s, h, 512)) {
                if (h.all { it == 0.toByte() }) break
                var name = str(h, 0, 100)
                val prefix = str(h, 345, 155)
                if (prefix.isNotEmpty() && String(h, 257, 5) == "ustar") name = "$prefix/$name"
                val size = num(h, 124, 12)
                val type = h[156].toInt().toChar()
                val padded = (size + 511) / 512 * 512
                when (type) {
                    'L' -> { longName = String(readBytes(s, size.toInt()), Charsets.UTF_8).trimEnd('\u0000'); skip(s, padded - size) }
                    'x' -> {                                                        // pax header: "len key=value\n"
                        val txt = String(readBytes(s, size.toInt()), Charsets.UTF_8); skip(s, padded - size)
                        txt.lineSequence().forEach { l -> val i = l.indexOf("path="); if (i >= 0) longName = l.substring(i + 5) }
                    }
                    'g' -> skip(s, padded)
                    '5' -> { safe(dest, (longName ?: name).trimEnd('/')).mkdirs(); longName = null }
                    '0', '\u0000' -> {
                        val t = safe(dest, longName ?: name); longName = null
                        t.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(t)).use { o ->
                            var left = size; val buf = ByteArray(32 * 1024)
                            while (left > 0) {
                                val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (r < 0) throw IOException("Truncated tar")
                                o.write(buf, 0, r); left -= r
                            }
                        }
                        skip(s, padded - size)
                        val mt = num(h, 136, 12)
                        if (mt > 0) t.setLastModified(mt * 1000)
                    }
                    else -> { skip(s, padded); longName = null }                    // links, devices: ignored
                }
            }
        }
    }

    private fun str(h: ByteArray, off: Int, len: Int): String {
        var e = off
        while (e < off + len && h[e] != 0.toByte()) e++
        return String(h, off, e - off, Charsets.UTF_8)
    }

    private fun num(h: ByteArray, off: Int, len: Int): Long {
        if (h[off].toInt() and 0x80 != 0) {                                         // base-256 for huge files
            var v = (h[off].toInt() and 0x7F).toLong()
            for (i in 1 until len) v = (v shl 8) or (h[off + i].toLong() and 0xFF)
            return v
        }
        val s = str(h, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun readFully(s: InputStream, b: ByteArray, n: Int): Boolean {
        var r = 0
        while (r < n) { val k = s.read(b, r, n - r); if (k < 0) return false; r += k }
        return true
    }

    private fun readBytes(s: InputStream, n: Int): ByteArray { val b = ByteArray(n); if (!readFully(s, b, n)) throw IOException("Truncated tar"); return b }

    private fun skip(s: InputStream, n: Long) {
        var left = n
        val b = ByteArray(8192)
        while (left > 0) { val k = s.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (k < 0) return; left -= k }
    }
}

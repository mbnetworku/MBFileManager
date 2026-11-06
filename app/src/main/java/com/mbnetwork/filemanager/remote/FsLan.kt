package com.mbnetwork.filemanager.remote

import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Properties

// ---------------------------------------------------------------------------------------- SMB (Windows share)

class FsSmb(private val c: Conn, pass: String) : RemoteFs {
    private val ctx: CIFSContext
    private val hostPart = if (c.port in 1..65535 && c.port != 445) "${c.host}:${c.port}" else c.host
    override val root = "/" + c.path.trim('/').let { if (it.isEmpty()) "" else it }

    init {
        val p = Properties()
        p.setProperty("jcifs.smb.client.connTimeout", "10000")
        p.setProperty("jcifs.smb.client.responseTimeout", "20000")
        val base = BaseContext(PropertyConfiguration(p))
        ctx = if (c.user.isEmpty()) base.withGuestCredentials() else {
            val u = c.user
            val domain = if (u.contains('\\')) u.substringBefore('\\') else ""
            val name = if (u.contains('\\')) u.substringAfter('\\') else u
            base.withCredentials(NtlmPasswordAuthenticator(domain, name, pass))
        }
    }

    private fun url(path: String, dir: Boolean) = "smb://$hostPart" + path.trimEnd('/') + (if (dir) "/" else "")

    override fun list(dir: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        for (f in SmbFile(url(dir, true), ctx).listFiles()) {
            val isDir = f.isDirectory
            val name = f.name.trimEnd('/')
            if (name.isEmpty() || (dir.trim('/').isEmpty() && name.endsWith("$"))) continue
            out += RemoteEntry(name, Fs.join(dir.trimEnd('/'), name).let { if (it.startsWith("/")) it else "/$it" }, isDir,
                if (isDir) 0 else f.length(), f.lastModified())
        }
        return out
    }

    override fun download(e: RemoteEntry, out: OutputStream) {
        SmbFile(url(e.path, false), ctx).inputStream.use { it.copyTo(out) }
    }

    override fun upload(dir: String, name: String, file: File) {
        SmbFile(url(Fs.join(dir.trimEnd('/'), name), false), ctx).outputStream.use { o -> FileInputStream(file).use { it.copyTo(o) } }
    }

    override fun mkdir(dir: String, name: String) { SmbFile(url(Fs.join(dir.trimEnd('/'), name), true), ctx).mkdir() }

    override fun delete(e: RemoteEntry) { SmbFile(url(e.path, e.isDir), ctx).delete() }

    override fun rename(e: RemoteEntry, newName: String) {
        val parent = e.path.trimEnd('/').substringBeforeLast('/')
        SmbFile(url(e.path, e.isDir), ctx).renameTo(SmbFile(url("$parent/$newName", e.isDir), ctx))
    }

    override fun close() { try { ctx.close() } catch (t: Throwable) { } }
}

// ---------------------------------------------------------------------------------------- FTP / FTPS

class FsFtp(c: Conn, pass: String, tls: Boolean) : RemoteFs {
    private val ftp: FTPClient = if (tls) FTPSClient("TLS") else FTPClient()
    override val root = c.path.ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }

    init {
        ftp.controlEncoding = "UTF-8"
        ftp.connectTimeout = 15000
        ftp.defaultTimeout = 30000
        ftp.connect(c.host, if (c.port > 0) c.port else 21)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) { ftp.disconnect(); throw IOException("FTP server refused the connection") }
        if (!ftp.login(c.user.ifEmpty { "anonymous" }, if (c.user.isEmpty()) "anonymous@" else pass)) {
            ftp.disconnect(); throw IOException("Login failed (wrong user or password)")
        }
        if (tls) { (ftp as FTPSClient).execPBSZ(0); ftp.execPROT("P") }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        ftp.setDataTimeout(60000)
    }

    override fun list(dir: String): List<RemoteEntry> =
        (ftp.listFiles(dir) ?: emptyArray()).filter { it.name != "." && it.name != ".." }.map {
            RemoteEntry(it.name, Fs.join(dir, it.name), it.isDirectory, if (it.isDirectory) 0 else it.size, it.timestamp?.timeInMillis ?: 0)
        }

    override fun download(e: RemoteEntry, out: OutputStream) {
        if (!ftp.retrieveFile(e.path, out)) throw IOException("Download failed: ${ftp.replyString.trim()}")
    }

    override fun upload(dir: String, name: String, file: File) {
        FileInputStream(file).use { if (!ftp.storeFile(Fs.join(dir, name), it)) throw IOException("Upload failed: ${ftp.replyString.trim()}") }
    }

    override fun mkdir(dir: String, name: String) {
        if (!ftp.makeDirectory(Fs.join(dir, name))) throw IOException(ftp.replyString.trim())
    }

    override fun delete(e: RemoteEntry) {
        val ok = if (e.isDir) ftp.removeDirectory(e.path) else ftp.deleteFile(e.path)
        if (!ok) throw IOException(ftp.replyString.trim())
    }

    override fun rename(e: RemoteEntry, newName: String) {
        if (!ftp.rename(e.path, e.path.trimEnd('/').substringBeforeLast('/') + "/" + newName)) throw IOException(ftp.replyString.trim())
    }

    override fun close() { try { ftp.logout() } catch (t: Throwable) { }; try { ftp.disconnect() } catch (t: Throwable) { } }
}

// ---------------------------------------------------------------------------------------- SFTP (SSH)

class FsSftp(ctx: Context, c: Conn, pass: String) : RemoteFs {
    private val jsch = JSch()
    private val session: Session
    private val ch: ChannelSftp
    override val root: String

    init {
        val port = if (c.port > 0) c.port else 22
        session = jsch.getSession(c.user, c.host, port)
        session.setPassword(pass)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.timeout = 20000
        session.connect(15000)
        // trust on first use: remember the server's fingerprint and refuse if it ever changes
        val fp = session.hostKey.getFingerPrint(jsch)
        val key = "${c.host}:$port"
        val known = ConnStore.hostKey(ctx, key)
        if (known == null) ConnStore.setHostKey(ctx, key, fp)
        else if (known != fp) {
            session.disconnect()
            throw IOException("The server's security fingerprint changed. Someone may be intercepting the connection.")
        }
        ch = session.openChannel("sftp") as ChannelSftp
        ch.connect(15000)
        root = c.path.ifEmpty { ch.pwd() }.let { if (it.startsWith("/")) it else "/$it" }
    }

    override fun list(dir: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        for (o in ch.ls(dir)) {
            val e = o as ChannelSftp.LsEntry
            if (e.filename == "." || e.filename == "..") continue
            out += RemoteEntry(e.filename, Fs.join(dir, e.filename), e.attrs.isDir, if (e.attrs.isDir) 0 else e.attrs.size, e.attrs.mTime * 1000L)
        }
        return out
    }

    override fun download(e: RemoteEntry, out: OutputStream) { ch.get(e.path, out) }

    override fun upload(dir: String, name: String, file: File) { FileInputStream(file).use { ch.put(it, Fs.join(dir, name)) } }

    override fun mkdir(dir: String, name: String) { ch.mkdir(Fs.join(dir, name)) }

    override fun delete(e: RemoteEntry) { if (e.isDir) ch.rmdir(e.path) else ch.rm(e.path) }

    override fun rename(e: RemoteEntry, newName: String) { ch.rename(e.path, e.path.trimEnd('/').substringBeforeLast('/') + "/" + newName) }

    override fun close() { try { ch.disconnect() } catch (t: Throwable) { }; try { session.disconnect() } catch (t: Throwable) { } }
}

// ---------------------------------------------------------------------------------------- WebDAV (NAS, Nextcloud, ownCloud ...)

class FsWebDav(private val c: Conn, pass: String) : RemoteFs {
    private val auth = if (c.user.isEmpty()) null else Credentials.basic(c.user, pass)
    override val root = (if (c.host.startsWith("http")) c.host else "https://${c.host}").let { if (it.endsWith("/")) it else "$it/" }

    private fun req(url: String) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }

    override fun list(dir: String): List<RemoteEntry> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>"""
        val r = Http.run(req(dir).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build())
        val text = r.use { it.body?.string() ?: "" }
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance(); f.isNamespaceAware = true
        val doc = f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(text.toByteArray()))
        val resp = doc.getElementsByTagNameNS("DAV:", "response")
        val self = dir.trimEnd('/')
        val out = ArrayList<RemoteEntry>()
        for (i in 0 until resp.length) {
            val el = resp.item(i) as org.w3c.dom.Element
            val href = el.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
            val abs = (dir.toHttpUrlOrNull()?.resolve(href) ?: continue).toString()
            if (abs.trimEnd('/') == self) continue
            val isDir = el.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = el.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
            val mod = el.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.let { parseHttpDate(it) } ?: 0L
            val name = android.net.Uri.decode(abs.trimEnd('/').substringAfterLast('/'))
            out += RemoteEntry(name, if (isDir && !abs.endsWith("/")) "$abs/" else abs, isDir, size, mod)
        }
        return out
    }

    private fun parseHttpDate(s: String): Long = try {
        val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US); f.parse(s.trim())?.time ?: 0L
    } catch (t: Throwable) { 0L }

    private fun child(dir: String, name: String) = dir.trimEnd('/') + "/" + android.net.Uri.encode(name)

    override fun download(e: RemoteEntry, out: OutputStream) {
        Http.run(req(e.path).get().build()).use { r -> r.body!!.byteStream().use { it.copyTo(out) } }
    }

    override fun upload(dir: String, name: String, file: File) {
        Http.run(req(child(dir, name)).put(file.asRequestBody(null)).build()).close()
    }

    override fun mkdir(dir: String, name: String) { Http.run(req(child(dir, name)).method("MKCOL", Http.empty()).build()).close() }

    override fun delete(e: RemoteEntry) { Http.run(req(e.path).delete().build()).close() }

    override fun rename(e: RemoteEntry, newName: String) {
        val parent = e.path.trimEnd('/').substringBeforeLast('/')
        val dest = child(parent, newName) + (if (e.isDir) "/" else "")
        Http.run(req(e.path).header("Destination", dest).header("Overwrite", "F").method("MOVE", Http.empty()).build()).close()
    }

    override fun close() { }
}

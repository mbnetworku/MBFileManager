package com.mbnetwork.filemanager.remote

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One file or folder on a remote server / cloud drive. [path] is what the server needs (a path, a URL or an id). */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val extra: String = ""
)

/** What every kind of server (SMB, FTP, SFTP, WebDAV, Google Drive, OneDrive, Dropbox) can do. */
interface RemoteFs {
    /** The folder to start in. */
    val root: String
    fun list(dir: String): List<RemoteEntry>
    fun download(e: RemoteEntry, out: OutputStream)
    fun upload(dir: String, name: String, file: File)
    /** Saves an edited copy back over [e] (same name). Most servers simply overwrite; Dropbox / Google Drive override this. */
    fun replace(dir: String, e: RemoteEntry, file: File) = upload(dir, e.name, file)
    fun mkdir(dir: String, name: String)
    fun delete(e: RemoteEntry)
    fun rename(e: RemoteEntry, newName: String)
    fun close()
}

/** A saved connection. [pass] is stored encrypted (Android 6+). For cloud accounts it holds the refresh token. */
data class Conn(
    val id: String,
    val type: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val path: String
) {
    val isCloud get() = type == "dropbox" || type == "onedrive" || type == "gdrive"
}

object ConnType {
    const val SMB = "smb"
    const val FTP = "ftp"
    const val FTPS = "ftps"
    const val SFTP = "sftp"
    const val WEBDAV = "webdav"

    fun title(t: String) = when (t) {
        SMB -> "Windows share (SMB)"; FTP -> "FTP"; FTPS -> "FTPS (secure FTP)"; SFTP -> "SFTP (SSH)"
        WEBDAV -> "WebDAV"; "dropbox" -> "Dropbox"; "onedrive" -> "OneDrive"; "gdrive" -> "Google Drive"; else -> t
    }

    fun defaultPort(t: String) = when (t) { SMB -> 445; FTP, FTPS -> 21; SFTP -> 22; WEBDAV -> 443; else -> 0 }
}

/** Saves the list of connections in the app's private storage. */
object ConnStore {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Conn> = try {
        val a = JSONArray(sp(ctx).getString("conns", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Conn(o.getString("id"), o.getString("type"), o.getString("name"), o.optString("host"), o.optInt("port"),
                o.optString("user"), o.optString("pass"), o.optString("path"))
        }
    } catch (e: Throwable) { emptyList() }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, c: Conn) {
        val list = all(ctx).filter { it.id != c.id } + c
        write(ctx, list)
    }

    fun remove(ctx: Context, id: String) = write(ctx, all(ctx).filter { it.id != id })

    private fun write(ctx: Context, list: List<Conn>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("host", it.host)
                .put("port", it.port).put("user", it.user).put("pass", it.pass).put("path", it.path))
        }
        sp(ctx).edit().putString("conns", a.toString()).apply()
    }

    /** Remembers the fingerprint of an SFTP server the first time we connect (trust on first use). */
    fun hostKey(ctx: Context, hostPort: String): String? = sp(ctx).getString("hk_$hostPort", null)
    fun setHostKey(ctx: Context, hostPort: String, fp: String) { sp(ctx).edit().putString("hk_$hostPort", fp).apply() }
}

/** Encrypts saved passwords with a key that never leaves the phone's secure hardware (Android 6+). */
object Secrets {
    private const val ALIAS = "mbfm_remote_secret"

    fun available() = Build.VERSION.SDK_INT >= 23

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    fun enc(plain: String): String {
        if (!available() || plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        } catch (e: Throwable) { "" }
    }

    fun dec(s: String): String {
        if (!available() || s.isEmpty()) return ""
        return try {
            val raw = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Throwable) { "" }
    }
}

object Fs {
    /** Opens a connection. Call this on a background thread. */
    fun open(ctx: Context, c: Conn, password: String): RemoteFs = when (c.type) {
        ConnType.SMB -> FsSmb(c, password)
        ConnType.FTP -> FsFtp(c, password, false)
        ConnType.FTPS -> FsFtp(c, password, true)
        ConnType.SFTP -> FsSftp(ctx, c, password)
        ConnType.WEBDAV -> FsWebDav(c, password)
        "dropbox" -> FsDropbox(TokenSource(ctx, OAuth.DROPBOX, c))
        "onedrive" -> FsOneDrive(TokenSource(ctx, OAuth.ONEDRIVE, c))
        "gdrive" -> FsGoogle(TokenSource(ctx, OAuth.GDRIVE, c))
        else -> throw IOException("Unknown connection type")
    }

    fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
}

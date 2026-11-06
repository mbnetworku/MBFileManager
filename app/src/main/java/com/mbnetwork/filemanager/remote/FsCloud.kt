package com.mbnetwork.filemanager.remote

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private fun isoToMillis(s: String?): Long = try {
    if (s.isNullOrEmpty()) 0L else {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
        f.parse(s.substring(0, 19))?.time ?: 0L
    }
} catch (t: Throwable) { 0L }

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

// ---------------------------------------------------------------------------------------- Dropbox

class FsDropbox(private val t: TokenSource) : RemoteFs {
    override val root = ""

    private fun api(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${t.get()}")

    /** Header values must be plain ASCII, so accented letters are written as \uXXXX. */
    private fun arg(j: JSONObject): String {
        val s = j.toString(); val sb = StringBuilder()
        for (ch in s) if (ch.code > 126) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        return sb.toString()
    }

    override fun list(dir: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var j = Http.json(Http.run(api("https://api.dropboxapi.com/2/files/list_folder")
            .post(Http.jsonBody(JSONObject().put("path", dir).put("limit", 2000).toString())).build()))
        while (true) {
            for (e in j.getJSONArray("entries").objects()) {
                val dirTag = e.optString(".tag") == "folder"
                out += RemoteEntry(e.getString("name"), e.getString("path_display"), dirTag, e.optLong("size"), isoToMillis(e.optString("server_modified")))
            }
            if (!j.optBoolean("has_more")) break
            j = Http.json(Http.run(api("https://api.dropboxapi.com/2/files/list_folder/continue")
                .post(Http.jsonBody(JSONObject().put("cursor", j.getString("cursor")).toString())).build()))
        }
        return out
    }

    override fun download(e: RemoteEntry, out: OutputStream) {
        Http.run(api("https://content.dropboxapi.com/2/files/download").header("Dropbox-API-Arg", arg(JSONObject().put("path", e.path)))
            .post(Http.empty()).build()).use { r -> r.body!!.byteStream().use { it.copyTo(out) } }
    }

    override fun upload(dir: String, name: String, file: File) {
        if (file.length() > 150L * 1024 * 1024) throw IOException("Dropbox uploads here are limited to 150 MB per file.")
        val a = JSONObject().put("path", "$dir/$name").put("mode", "add").put("autorename", true)
        Http.run(api("https://content.dropboxapi.com/2/files/upload").header("Dropbox-API-Arg", arg(a))
            .post(file.asRequestBody("application/octet-stream".toMediaType())).build()).close()
    }

    override fun replace(dir: String, e: RemoteEntry, file: File) {
        if (file.length() > 150L * 1024 * 1024) throw IOException("Dropbox uploads here are limited to 150 MB per file.")
        val a = JSONObject().put("path", e.path).put("mode", "overwrite")
        Http.run(api("https://content.dropboxapi.com/2/files/upload").header("Dropbox-API-Arg", arg(a))
            .post(file.asRequestBody("application/octet-stream".toMediaType())).build()).close()
    }

    override fun mkdir(dir: String, name: String) {
        Http.run(api("https://api.dropboxapi.com/2/files/create_folder_v2")
            .post(Http.jsonBody(JSONObject().put("path", "$dir/$name").toString())).build()).close()
    }

    override fun delete(e: RemoteEntry) {
        Http.run(api("https://api.dropboxapi.com/2/files/delete_v2").post(Http.jsonBody(JSONObject().put("path", e.path).toString())).build()).close()
    }

    override fun rename(e: RemoteEntry, newName: String) {
        val to = e.path.substringBeforeLast('/') + "/" + newName
        Http.run(api("https://api.dropboxapi.com/2/files/move_v2")
            .post(Http.jsonBody(JSONObject().put("from_path", e.path).put("to_path", to).toString())).build()).close()
    }

    override fun close() { }
}

// ---------------------------------------------------------------------------------------- OneDrive

class FsOneDrive(private val t: TokenSource) : RemoteFs {
    override val root = "root"
    private val base = "https://graph.microsoft.com/v1.0/me/drive"

    private fun item(id: String) = if (id == "root") "$base/root" else "$base/items/$id"
    private fun api(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${t.get()}")

    override fun list(dir: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var url: String? = item(dir) + "/children?\$top=200"
        while (url != null) {
            val j = Http.json(Http.run(api(url).get().build()))
            for (e in j.getJSONArray("value").objects()) {
                out += RemoteEntry(e.getString("name"), e.getString("id"), e.has("folder"), e.optLong("size"), isoToMillis(e.optString("lastModifiedDateTime")))
            }
            url = if (j.has("@odata.nextLink")) j.getString("@odata.nextLink") else null
        }
        return out
    }

    override fun download(e: RemoteEntry, out: OutputStream) {
        Http.run(api(item(e.path) + "/content").get().build()).use { r -> r.body!!.byteStream().use { it.copyTo(out) } }
    }

    override fun upload(dir: String, name: String, file: File) {
        val enc = Uri.encode(name)
        if (file.length() <= 4L * 1024 * 1024) {
            Http.run(api(item(dir) + ":/$enc:/content").put(file.asRequestBody("application/octet-stream".toMediaType())).build()).close()
            return
        }
        val s = Http.json(Http.run(api(item(dir) + ":/$enc:/createUploadSession")
            .post(Http.jsonBody("""{"item":{"@microsoft.graph.conflictBehavior":"rename"}}""")).build()))
        val upUrl = s.getString("uploadUrl")
        val total = file.length()
        val chunk = 3_276_800L                               // must be a multiple of 320 KiB
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            while (pos < total) {
                val len = minOf(chunk, total - pos).toInt()
                val buf = ByteArray(len); raf.seek(pos); raf.readFully(buf)
                Http.run(Request.Builder().url(upUrl)                   // no sign-in header on the upload address
                    .header("Content-Range", "bytes $pos-${pos + len - 1}/$total")
                    .put(buf.toRequestBody("application/octet-stream".toMediaType())).build()).close()
                pos += len
            }
        }
    }

    override fun mkdir(dir: String, name: String) {
        Http.run(api(item(dir) + "/children").post(Http.jsonBody(
            JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "rename").toString())).build()).close()
    }

    override fun delete(e: RemoteEntry) { Http.run(api(item(e.path)).delete().build()).close() }

    override fun rename(e: RemoteEntry, newName: String) {
        Http.run(api(item(e.path)).patch(Http.jsonBody(JSONObject().put("name", newName).toString())).build()).close()
    }

    override fun close() { }
}

// ---------------------------------------------------------------------------------------- Google Drive

class FsGoogle(private val t: TokenSource) : RemoteFs {
    override val root = "root"
    private val files = "https://www.googleapis.com/drive/v3/files"
    private val folderMime = "application/vnd.google-apps.folder"

    private fun api(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${t.get()}")

    /** Google Docs / Sheets / Slides are not real files; they are downloaded as Word / Excel / PowerPoint. */
    private fun export(mime: String): Pair<String, String>? = when (mime) {
        "application/vnd.google-apps.document" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ".docx"
        "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ".xlsx"
        "application/vnd.google-apps.presentation" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ".pptx"
        "application/vnd.google-apps.drawing" -> "application/pdf" to ".pdf"
        else -> null
    }

    override fun list(dir: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var token: String? = null
        do {
            val u = okhttp3.HttpUrl.Builder().scheme("https").host("www.googleapis.com").addPathSegments("drive/v3/files")
                .addQueryParameter("q", "'$dir' in parents and trashed=false")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)")
                .addQueryParameter("pageSize", "200").addQueryParameter("orderBy", "folder,name")
            token?.let { u.addQueryParameter("pageToken", it) }
            val j = Http.json(Http.run(api(u.build().toString()).get().build()))
            for (e in j.getJSONArray("files").objects()) {
                val mime = e.optString("mimeType")
                val isDir = mime == folderMime
                val ex = export(mime)
                val name = e.getString("name") + (ex?.second ?: "")
                out += RemoteEntry(name, e.getString("id"), isDir, e.optLong("size"), isoToMillis(e.optString("modifiedTime")), ex?.first ?: "")
            }
            token = if (j.has("nextPageToken")) j.getString("nextPageToken") else null
        } while (token != null)
        return out
    }

    override fun download(e: RemoteEntry, out: OutputStream) {
        val url = if (e.extra.isNotEmpty()) "$files/${e.path}/export?mimeType=${Uri.encode(e.extra)}" else "$files/${e.path}?alt=media"
        Http.run(api(url).get().build()).use { r -> r.body!!.byteStream().use { it.copyTo(out) } }
    }

    override fun upload(dir: String, name: String, file: File) {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(dir)).toString()
        val start = Http.run(api("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
            .header("X-Upload-Content-Length", file.length().toString())
            .post(Http.jsonBody(meta)).build())
        val loc = start.use { it.header("Location") } ?: throw IOException("Google Drive did not accept the upload.")
        Http.run(Request.Builder().url(loc).put(file.asRequestBody("application/octet-stream".toMediaType())).build()).close()
    }

    override fun replace(dir: String, e: RemoteEntry, file: File) {
        if (e.extra.isNotEmpty()) { upload(dir, e.name, file); return }        // a Google Doc / Sheet / Slide: save as a normal file next to it
        Http.run(Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/${e.path}?uploadType=media")
            .header("Authorization", "Bearer ${t.get()}")
            .patch(file.asRequestBody("application/octet-stream".toMediaType())).build()).close()
    }

    override fun mkdir(dir: String, name: String) {
        Http.run(api(files).post(Http.jsonBody(JSONObject().put("name", name).put("mimeType", folderMime)
            .put("parents", JSONArray().put(dir)).toString())).build()).close()
    }

    /** Goes to the Drive bin (not deleted forever). */
    override fun delete(e: RemoteEntry) {
        Http.run(api("$files/${e.path}").patch(Http.jsonBody("""{"trashed":true}""")).build()).close()
    }

    override fun rename(e: RemoteEntry, newName: String) {
        Http.run(api("$files/${e.path}").patch(Http.jsonBody(JSONObject().put("name", newName).toString())).build()).close()
    }

    override fun close() { }
}

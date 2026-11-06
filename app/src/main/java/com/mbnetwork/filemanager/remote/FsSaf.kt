package com.mbnetwork.filemanager.remote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Cloud storage through the phone's own cloud apps (Google Drive, OneDrive, Dropbox ...).
 * The user signs in inside the official app the normal way (personal accounts, Microsoft 365 work / school accounts);
 * Android then lets this app browse and edit those files. No developer keys are needed.
 */
class FsSaf(private val ctx: Context, private val tree: Uri) : RemoteFs {
    private val cr = ctx.contentResolver

    override val root: String = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    override fun list(dir: String): List<RemoteEntry> {
        val docId = DocumentsContract.getDocumentId(Uri.parse(dir))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val out = ArrayList<RemoteEntry>()
        cr.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val mime = c.getString(2) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                out += RemoteEntry(c.getString(1) ?: id, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), isDir,
                    if (isDir || c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
            }
        } ?: throw IOException("The cloud app did not answer. Open it once and check that you are signed in.")
        return out
    }

    override fun download(e: RemoteEntry, out: OutputStream) {
        val inp = cr.openInputStream(Uri.parse(e.path)) ?: throw IOException("Cannot read this file")
        inp.use { it.copyTo(out) }
    }

    override fun upload(dir: String, name: String, file: File) {
        val doc = DocumentsContract.createDocument(cr, Uri.parse(dir), mimeOf(name), name) ?: throw IOException("Cannot create the file")
        (cr.openOutputStream(doc, "w") ?: throw IOException("Cannot write the file")).use { o -> FileInputStream(file).use { it.copyTo(o) } }
    }

    override fun replace(dir: String, e: RemoteEntry, file: File) {
        (cr.openOutputStream(Uri.parse(e.path), "wt") ?: throw IOException("Cannot write the file")).use { o -> FileInputStream(file).use { it.copyTo(o) } }
    }

    override fun mkdir(dir: String, name: String) {
        DocumentsContract.createDocument(cr, Uri.parse(dir), DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException("Cannot create the folder")
    }

    override fun delete(e: RemoteEntry) {
        if (!DocumentsContract.deleteDocument(cr, Uri.parse(e.path))) throw IOException("Cannot delete")
    }

    override fun rename(e: RemoteEntry, newName: String) {
        DocumentsContract.renameDocument(cr, Uri.parse(e.path), newName) ?: throw IOException("Cannot rename")
    }

    override fun close() { }

    companion object {
        /** Which cloud this folder belongs to, from the provider's address. */
        fun providerOf(uri: Uri): String {
            val a = (uri.authority ?: "").lowercase()
            return when {
                a.contains("dropbox") -> "dropbox"
                a.contains("skydrive") || a.contains("microsoft") || a.contains("onedrive") -> "onedrive"
                a.contains("google") || a.contains("docs") -> "gdrive"
                else -> "other"
            }
        }
    }
}

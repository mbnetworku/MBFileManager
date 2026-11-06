package com.mbnetwork.filemanager

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opens password-protected Word / Excel / PowerPoint files by making an unlocked copy. */
object OfficeUnlock {

    fun open(a: AppCompatActivity, f: File, onReady: (File) -> Unit) {
        if (!OfficeCrypt.isEncrypted(f)) { onReady(f); return }
        ask(a, f, false, onReady)
    }

    private fun ask(a: AppCompatActivity, f: File, wrong: Boolean, onReady: (File) -> Unit) {
        PasswordDialog.ask(a, f.name, wrong) { pw ->
            a.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    try {
                        val bytes = OfficeCrypt.decrypt(f, pw)
                        var dir = f.parentFile
                        if (dir == null || !dir.canWrite()) dir = a.getExternalFilesDir(null) ?: a.cacheDir
                        val ext = f.extension
                        val out = ZipUtil.uniqueName(dir, f.nameWithoutExtension + " (unlocked)", if (ext.isEmpty()) "" else ".$ext")
                        out.writeBytes(bytes)
                        out
                    } catch (e: OfficeCrypt.WrongPassword) { null }
                    catch (e: Throwable) { e }
                }
                when (r) {
                    is File -> {
                        android.media.MediaScannerConnection.scanFile(a, arrayOf(r.path), null, null)
                        Toast.makeText(a, a.getString(R.string.unlocked_copy, r.name), Toast.LENGTH_LONG).show()
                        onReady(r)
                    }
                    null -> ask(a, f, true, onReady)
                    else -> Toast.makeText(a, a.getString(R.string.failed, (r as Throwable).message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

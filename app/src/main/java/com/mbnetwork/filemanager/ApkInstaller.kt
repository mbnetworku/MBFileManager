package com.mbnetwork.filemanager

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Installs APK files, checking the "Install unknown apps" permission only on versions that need it. */
object ApkInstaller {

    private const val APK_MIME = "application/vnd.android.package-archive"

    fun isApk(name: String) = name.lowercase().endsWith(".apk")

    /** Android 8.0+ needs the per-app "Install unknown apps" permission; older versions do not. */
    fun needsInstallPermission(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()

    /** Opens the standard Android installer for [file]. */
    fun install(activity: Activity, file: File) {
        if (needsInstallPermission(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.install_perm_title)
                .setMessage(R.string.install_perm_message)
                .setPositiveButton(R.string.allow) { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                        )
                    }
                }
                .setNegativeButton(R.string.not_now, null)
                .show()
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7+: must share through a content:// URI
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            // Android 5-6: the system installer only accepts file:// URIs of a world-readable file
            var f = file
            if (file.absolutePath.startsWith(activity.cacheDir.absolutePath)) {
                val ext = activity.externalCacheDir
                if (ext != null) f = file.copyTo(File(ext, file.name), overwrite = true)
            }
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), APK_MIME)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    /** Root: install without the system dialog. Call off the main thread. */
    fun installSilently(path: String): RootShell.Result {
        val tmp = "/data/local/tmp/mbfm_install.apk"
        return RootShell.run(
            "cp ${RootShell.q(path)} $tmp && chmod 644 $tmp && pm install -r $tmp; r=\$?; rm -f $tmp; exit \$r"
        )
    }

    /** Finds APK files on the given storage roots (skips Android/data & obb). Call off the main thread. */
    fun findApks(roots: List<File>, maxResults: Int = 500): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || result.size >= maxResults) return
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) {
                    if (c.name == "data" && dir.name == "Android" || c.name == "obb" && dir.name == "Android") continue
                    walk(c, depth + 1)
                } else if (isApk(c.name)) result += c
            }
        }
        roots.forEach { walk(it, 0) }
        return result.sortedByDescending { it.lastModified() }
    }
}

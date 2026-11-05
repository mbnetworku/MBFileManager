package com.mbnetwork.filemanager

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Decides, based on the Android version, whether a storage permission is needed at all.
 *
 *  - Android 5.0 – 5.1 (API 21-22): permissions are granted at install time -> nothing to ask.
 *  - Android 6.0 – 10  (API 23-29): runtime READ/WRITE_EXTERNAL_STORAGE popup, only if not granted.
 *  - Android 11+       (API 30+)  : "All files access" (MANAGE_EXTERNAL_STORAGE), only if not granted.
 */
object PermissionHelper {

    const val REQUEST_CODE = 1001

    private val legacyPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    /** True when this Android version has a runtime storage permission that the user must grant. */
    fun versionNeedsPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** True when the app can already access storage (no dialog needed). */
    fun hasStoragePermission(context: Context): Boolean = when {
        !versionNeedsPermission() -> true // Android 5.x: granted at install
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> legacyPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Only shows a popup when the version needs a permission AND it is not granted yet. */
    fun requestIfNeeded(activity: Activity) {
        if (hasStoragePermission(activity)) return
        val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) R.string.perm_message_r else R.string.perm_message
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.perm_title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(R.string.allow) { _, _ -> launchSystemRequest(activity) }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun launchSystemRequest(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${activity.packageName}"))
                )
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(activity, legacyPermissions, REQUEST_CODE)
        }
    }
}

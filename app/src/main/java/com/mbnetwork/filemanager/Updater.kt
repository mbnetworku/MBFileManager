package com.mbnetwork.filemanager

import android.app.Activity
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer version, downloads the APK and opens the installer. */
object Updater {
    private const val API = "https://api.github.com/repos/mbnetworku/MBFileManager/releases/latest"
    private const val DAY = 24 * 60 * 60 * 1000L

    data class Release(val version: String, val apkUrl: String, val notes: String)

    /** Compares "1.10" > "1.9" correctly. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun fetch(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000; c.readTimeout = 10000
        return c.inputStream.bufferedReader().use { r ->
            val j = JSONObject(r.readText())
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); if (a.getString("name") == "MBFileManager.apk") break }
            }
            url?.let { Release(j.getString("tag_name").trimStart('v'), it, j.optString("body")) }
        }
    }

    /** [manual] = user pressed "Check for updates" (shows "up to date" message). Updates are never blocked. */
    fun check(activity: Activity, scope: LifecycleCoroutineScope, manual: Boolean) {
        val prefs = activity.getSharedPreferences("settings", Activity.MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) < DAY) return
        if (manual) Toast.makeText(activity, R.string.checking_updates, Toast.LENGTH_SHORT).show()
        scope.launch {
            val rel = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }
            prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
            if (activity.isFinishing) return@launch
            when {
                rel == null -> if (manual) Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                isNewer(rel.version, BuildConfig.VERSION_NAME) -> MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.update_available, rel.version))
                    .setMessage(activity.getString(R.string.update_message, BuildConfig.VERSION_NAME, rel.version) +
                        WhatsNew.forRelease(rel.notes, rel.version).let { if (it.isBlank()) "" else "\n\n" + activity.getString(R.string.whats_new_label) + "\n" + it })
                    .setPositiveButton(R.string.update_now) { _, _ -> download(activity, scope, rel) }
                    .setNegativeButton(R.string.later, null).show()
                manual -> Toast.makeText(activity, activity.getString(R.string.up_to_date, BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, rel: Release) {
        val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = true }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(bar) }
        val dlg = MaterialAlertDialogBuilder(activity).setTitle(R.string.downloading_update).setView(box).setCancelable(false).show()
        scope.launch {
            val dir = activity.externalCacheDir ?: activity.cacheDir
            val out = File(dir, "MBFileManager-update.apk")
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    var url = URL(rel.apkUrl)
                    var c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true
                    // follow cross-host redirects (GitHub -> CDN)
                    var hops = 0
                    while (c.responseCode in 300..399 && hops++ < 5) {
                        url = URL(c.getHeaderField("Location")); c = url.openConnection() as HttpURLConnection
                    }
                    val total = c.contentLength
                    c.inputStream.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                            while (input.read(buf).also { n = it } >= 0) {
                                o.write(buf, 0, n); done += n
                                if (total > 0) { val p = (done * 100 / total).toInt(); bar.post { bar.isIndeterminate = false; bar.progress = p } }
                            }
                        }
                    }
                }
            }
            dlg.dismiss()
            r.onSuccess { ApkInstaller.install(activity, out) }
                .onFailure { Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show() }
        }
    }
}

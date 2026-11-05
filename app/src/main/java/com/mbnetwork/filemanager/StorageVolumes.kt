package com.mbnetwork.filemanager

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Finds ALL storage: internal, SD cards and USB OTG drives / card readers.
 * Uses 4 methods because phones differ: StorageManager volumes, app dirs, /proc/mounts and /storage.
 */
object StorageVolumes {

    data class Vol(val path: String, val label: String, val removable: Boolean, val usb: Boolean)

    fun list(ctx: Context): List<Vol> {
        val internal = Environment.getExternalStorageDirectory().absolutePath
        val found = LinkedHashMap<String, Vol>()
        found[internal] = Vol(internal, ctx.getString(R.string.internal_storage), false, false)

        // 1) StorageManager (gives real names like "USB drive" / "SD card")
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= 24) {
                for (v in sm.storageVolumes) {
                    if (v.isPrimary) continue
                    val state = v.state
                    if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                    val path = when {
                        Build.VERSION.SDK_INT >= 30 -> v.directory?.absolutePath
                        else -> try { v.javaClass.getMethod("getPath").invoke(v) as? String } catch (e: Throwable) { null }
                    } ?: v.uuid?.let { "/storage/$it" } ?: continue
                    val desc = v.getDescription(ctx) ?: File(path).name
                    add(found, path, desc)
                }
            } else {
                // Android 5-6: hidden getVolumeList()
                val arr = sm.javaClass.getMethod("getVolumeList").invoke(sm) as Array<*>
                for (v in arr) {
                    v ?: continue
                    val path = v.javaClass.getMethod("getPath").invoke(v) as? String ?: continue
                    val state = try { v.javaClass.getMethod("getState").invoke(v) as? String } catch (e: Throwable) { Environment.MEDIA_MOUNTED }
                    if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                    val desc = try { v.javaClass.getMethod("getDescription", Context::class.java).invoke(v, ctx) as? String } catch (e: Throwable) { null }
                    add(found, path, desc ?: File(path).name)
                }
            }
        } catch (e: Throwable) { }

        // 2) app-specific dirs on every mounted volume
        try {
            ContextCompat.getExternalFilesDirs(ctx, null).drop(1).filterNotNull().forEach {
                add(found, it.absolutePath.substringBefore("/Android/data"), null)
            }
        } catch (e: Throwable) { }

        // 3) /proc/mounts: anything mounted under /storage or /mnt/media_rw (USB OTG, card readers)
        try {
            File("/proc/mounts").readLines().forEach { line ->
                val mp = line.split(" ").getOrNull(1)?.replace("\\040", " ") ?: return@forEach
                val name = mp.substringAfterLast('/')
                when {
                    mp.startsWith("/storage/") && mp.count { it == '/' } == 2 && isVolumeName(name) -> add(found, mp, null)
                    mp.startsWith("/mnt/media_rw/") && isVolumeName(name) -> add(found, "/storage/$name", null)
                }
            }
        } catch (e: Throwable) { }

        // 4) /storage folder scan
        try {
            File("/storage").listFiles()?.forEach { if (isVolumeName(it.name)) add(found, it.absolutePath, null) }
        } catch (e: Throwable) { }

        return found.values.filter { it.path == internal || File(it.path).let { f -> f.exists() && f.isDirectory } }
    }

    private fun isVolumeName(n: String) =
        n.isNotEmpty() && n !in setOf("emulated", "self", "sdcard0", "knox-emulated", "container") && !n.startsWith(".")

    private fun add(map: LinkedHashMap<String, Vol>, rawPath: String, desc: String?) {
        val path = rawPath.trimEnd('/')
        val internal = Environment.getExternalStorageDirectory().absolutePath
        if (path == internal || path.startsWith("/storage/emulated")) return
        val existing = map[path]
        val usbByName = desc?.contains("usb", true) == true || path.contains("usb", true) || path.contains("udisk", true)
        val label = desc ?: existing?.label ?: if (usbByName) "USB drive" else "SD card (${File(path).name})"
        map[path] = Vol(path, label, true, usbByName || (existing?.usb == true) || label.contains("usb", true))
    }
}

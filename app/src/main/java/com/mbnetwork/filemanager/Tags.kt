package com.mbnetwork.filemanager

import android.content.Context
import java.io.File

/** Colour tags (Red, Orange, Blue, Green) like the iOS Files app. Stored on the phone by file path. */
object Tags {
    data class Tag(val key: String, val label: Int, val color: Int)

    val all = listOf(
        Tag("red", R.string.tag_red, 0xFFFF3B30.toInt()),
        Tag("orange", R.string.tag_orange, 0xFFFF9500.toInt()),
        Tag("blue", R.string.tag_blue, 0xFF007AFF.toInt()),
        Tag("green", R.string.tag_green, 0xFF34C759.toInt())
    )

    private var cache: MutableMap<String, String>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("tags", Context.MODE_PRIVATE)

    private fun map(ctx: Context): MutableMap<String, String> {
        cache?.let { return it }
        val m = HashMap<String, String>()
        prefs(ctx).all.forEach { (k, v) -> if (v is String) m[k] = v }
        cache = m
        return m
    }

    fun get(ctx: Context, path: String): String? = map(ctx)[path]

    fun color(ctx: Context, path: String): Int? =
        get(ctx, path)?.let { key -> all.firstOrNull { it.key == key }?.color }

    /** [key] = null removes the tag. */
    fun set(ctx: Context, path: String, key: String?) {
        val m = map(ctx)
        val e = prefs(ctx).edit()
        if (key == null) { m.remove(path); e.remove(path) } else { m[path] = key; e.putString(path, key) }
        e.apply()
    }

    fun items(ctx: Context, key: String): List<FileItem> =
        map(ctx).filter { it.value == key }.keys.mapNotNull { p ->
            val f = File(p)
            if (!f.exists()) null
            else FileItem(f.name, p, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified())
        }.sortedBy { it.name.lowercase() }

    fun count(ctx: Context, key: String): Int = map(ctx).count { it.value == key && File(it.key).exists() }
}

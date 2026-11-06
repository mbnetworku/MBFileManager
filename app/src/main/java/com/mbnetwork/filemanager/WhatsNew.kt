package com.mbnetwork.filemanager

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The short "What's new" message the user sees after an update (new features and fixes).
 * One list for everything: assets/whatsnew.txt  ("## version" followed by "- item" lines).
 * The same file is the text of the GitHub release, so the update dialog can show it too.
 */
object WhatsNew {
    data class Section(val version: String, val items: List<String>)

    fun parse(text: String): List<Section> {
        val out = ArrayList<Section>()
        var v: String? = null
        var items = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("## ")) {
                v?.let { out += Section(it, items) }
                v = line.removePrefix("## ").trim(); items = ArrayList()
            } else if (line.startsWith("- ") && v != null) items += line.removePrefix("- ").trim()
        }
        v?.let { out += Section(it, items) }
        return out
    }

    fun format(sections: List<Section>) = sections.joinToString("\n\n") { s ->
        (if (sections.size > 1) "v${s.version}\n" else "") + s.items.joinToString("\n") { "• $it" }
    }

    private fun load(activity: Activity): List<Section> = try {
        parse(activity.assets.open("whatsnew.txt").bufferedReader().use { it.readText() })
    } catch (e: Throwable) { emptyList() }

    /** Shows the changes once after the app was updated (not on a fresh install). */
    fun showIfUpdated(activity: Activity) {
        val sp = activity.getSharedPreferences("settings", Activity.MODE_PRIVATE)
        val current = BuildConfig.VERSION_NAME
        val last = sp.getString("whatsnewVer", null)
        if (last == current) return
        val fresh = try {
            val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
            pi.firstInstallTime == pi.lastUpdateTime
        } catch (e: Throwable) { false }
        sp.edit().putString("whatsnewVer", current).apply()
        if (fresh && last == null) return
        val all = load(activity)
        val news = (if (last == null) all.take(3) else all.filter { Updater.isNewer(it.version, last) }).take(6)
        if (news.isEmpty() || activity.isFinishing) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.whats_new_title, current))
            .setMessage(format(news))
            .setPositiveButton(R.string.ok, null).show()
    }

    /** Picks the part of the release text that belongs to [version] (for the update dialog). */
    fun forRelease(body: String, version: String): String {
        val secs = parse(body)
        val s = secs.firstOrNull { it.version == version } ?: secs.firstOrNull() ?: return ""
        return s.items.joinToString("\n") { "• $it" }
    }
}

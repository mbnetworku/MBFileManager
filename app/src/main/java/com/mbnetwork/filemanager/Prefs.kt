package com.mbnetwork.filemanager

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var rootMode: Boolean
        get() = sp.getBoolean("root", false)
        set(v) = sp.edit().putBoolean("root", v).apply()
    var showHidden: Boolean
        get() = sp.getBoolean("hidden", false)
        set(v) = sp.edit().putBoolean("hidden", v).apply()
    var sort: Int
        get() = sp.getInt("sort", 0)
        set(v) = sp.edit().putInt("sort", v).apply()
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()
    /** 0 = Icons (thumbnails), 1 = List */
    var viewMode: Int
        get() = sp.getInt("view", 0)
        set(v) = sp.edit().putInt("view", v).apply()
    /** 0..70 percent see-through */
    var transparency: Int
        get() = sp.getInt("transparency", 15)
        set(v) = sp.edit().putInt("transparency", v).apply()
    var liquidGlass: Boolean
        get() = sp.getBoolean("liquid2", true)
        set(v) = sp.edit().putBoolean("liquid2", v).apply()
    /** Icon view columns: 0 = auto */
    var iconColumns: Int
        get() = sp.getInt("iconCols", 0)
        set(v) = sp.edit().putInt("iconCols", v).apply()
    /** Show the "now playing" notification while music plays. */
    var musicNotification: Boolean
        get() = sp.getBoolean("music_notif", true)
        set(v) = sp.edit().putBoolean("music_notif", v).apply()
    /** Screen / list / bar animations. */
    var animations: Boolean
        get() = sp.getBoolean("anim", true)
        set(v) = sp.edit().putBoolean("anim", v).apply()
    /** Equalizer follows the real sound (needs the microphone permission). */
    var realEqualizer: Boolean
        get() = sp.getBoolean("real_eq", true)
        set(v) = sp.edit().putBoolean("real_eq", v).apply()
    var micAsked: Boolean
        get() = sp.getBoolean("mic_asked", false)
        set(v) = sp.edit().putBoolean("mic_asked", v).apply()
    /** Offline mode: the app never connects to the internet by itself. */
    var offlineMode: Boolean
        get() = sp.getBoolean("offline", true)
        set(v) = sp.edit().putBoolean("offline", v).apply()
    var showSystemApps: Boolean
        get() = sp.getBoolean("sysapps", false)
        set(v) = sp.edit().putBoolean("sysapps", v).apply()
}

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
    var showSystemApps: Boolean
        get() = sp.getBoolean("sysapps", false)
        set(v) = sp.edit().putBoolean("sysapps", v).apply()
}

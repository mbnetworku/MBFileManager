package com.mbnetwork.filemanager

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Progress of one copy / move / delete started from the file list. Shared by the in-app bar and the notification. */
object OpProgress {
    @Volatile var title: String = ""
    @Volatile var total: Int = 0
    @Volatile var done: Int = 0
    @Volatile var active: Boolean = false
    /** The user tapped Hide on the in-app bar. The operation keeps running and stays in the notification. */
    @Volatile var hidden: Boolean = false
    @Volatile var result: String? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { main.post { listeners.forEach { it() } } }

    fun start(title: String, total: Int) {
        this.title = title; this.total = total; done = 0; hidden = false; result = null; active = true
        fire()
    }

    fun step() { done++; fire() }

    fun finish(message: String) { result = message; active = false; fire() }

    fun hide() { hidden = true; fire() }
}

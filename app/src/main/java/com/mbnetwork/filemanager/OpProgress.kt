package com.mbnetwork.filemanager

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Progress of one copy / move / delete started from the file list. Shared by the in-app bar and the notification. */
object OpProgress {
    @Volatile var title: String = ""
    @Volatile var total: Int = 0
    @Volatile var done: Int = 0
    @Volatile var active: Boolean = false
    /** The user tapped Hide on the in-app bar. The operation keeps running and stays in the notification. */
    @Volatile var hidden: Boolean = false
    @Volatile var result: String? = null
    /** The user tapped Cancel in the notification. File work checks this between chunks / items. */
    @Volatile var cancelled: Boolean = false
    /** Progress (0..100) of the item that is being copied right now. */
    @Volatile var itemPct: Int = 0

    /** Lives as long as the app process, so an operation finishes (and reports) even if the screen is closed. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { main.post { listeners.forEach { it() } } }

    @Synchronized fun start(title: String, total: Int) {
        this.title = title; this.total = total; done = 0; itemPct = 0; hidden = false; cancelled = false; result = null; active = true
        fire()
    }

    @Synchronized fun step() { done++; itemPct = 0; fire() }

    /** Called by the copy code with the bytes copied so far for the current item. */
    fun setItem(copied: Long, size: Long) {
        if (!active) return
        val p = if (size > 0) (copied * 100 / size).toInt().coerceIn(0, 100) else 0
        if (p != itemPct) { itemPct = p; fire() }
    }

    fun cancel() { if (active) { cancelled = true; fire() } }

    @Synchronized fun finish(message: String) { result = message; active = false; itemPct = 0; fire() }

    fun hide() { hidden = true; fire() }
}

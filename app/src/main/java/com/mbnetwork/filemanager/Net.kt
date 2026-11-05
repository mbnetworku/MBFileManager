package com.mbnetwork.filemanager

import android.content.Context

/**
 * The single "Block internet" switch.
 * ON (default): the app never connects to the internet. OFF: connecting is allowed (for example to update).
 * "Allow once" opens the door for a short time only.
 */
object Net {
    @Volatile private var allowUntil = 0L

    fun allowFor(ms: Long) { allowUntil = System.currentTimeMillis() + ms }

    fun blocked(ctx: Context): Boolean = Prefs(ctx).offlineMode && System.currentTimeMillis() >= allowUntil
}

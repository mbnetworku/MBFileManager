package com.mbnetwork.filemanager

import android.content.Context

/**
 * The "Block internet for AI" switch (on by default).
 * ON: AI features stay 100 % on the phone. OFF: AI features may use online services.
 * Updates are NOT affected: the app can always check for and download updates.
 *
 * Any AI feature that wants the internet must check [aiBlocked] first. (The AI tools in the app today are all on-device.)
 */
object Net {
    fun aiBlocked(ctx: Context): Boolean = Prefs(ctx).blockAiInternet
}

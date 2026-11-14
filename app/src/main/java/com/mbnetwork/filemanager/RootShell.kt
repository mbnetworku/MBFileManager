package com.mbnetwork.filemanager

import java.io.File

/** Runs commands through `su`. Works with Magisk, SuperSU, KernelSU etc. */
object RootShell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    @Volatile var granted: Boolean = false
        private set

    /** True only if this device actually has root (an su binary). Root features are hidden otherwise. */
    val deviceRooted: Boolean by lazy { suBinaryExists() }

    fun suBinaryExists(): Boolean {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su")
        if (paths.any { File(it).exists() }) return true
        return System.getenv("PATH")?.split(":")?.any { File(it, "su").exists() } == true
    }

    /** Asks the root manager for access (shows its popup). Call off the main thread. */
    fun requestRoot(): Boolean {
        if (!deviceRooted) { granted = false; return false }
        granted = try { run("id").out.contains("uid=0") } catch (e: Exception) { false }
        return granted
    }

    /** Executes [cmd] as root. Call off the main thread. */
    fun run(cmd: String, timeoutSec: Long = 120): Result {
        val p = Runtime.getRuntime().exec("su")
        var err = ""
        var out = ""
        val errThread = Thread { try { err = p.errorStream.bufferedReader().readText() } catch (e: Throwable) { } }.apply { start() }
        val outThread = Thread { try { out = p.inputStream.bufferedReader().readText() } catch (e: Throwable) { } }.apply { start() }
        try {
            p.outputStream.bufferedWriter().use {
                it.write(cmd); it.write("\nexit\n"); it.flush()
            }
        } catch (e: Throwable) { }
        // Wait with a deadline so a stuck command (or an unanswered root prompt) cannot block the thread forever.
        val end = System.currentTimeMillis() + timeoutSec * 1000
        var code = -1
        var finished = false
        while (!finished) {
            try { code = p.exitValue(); finished = true } catch (e: IllegalThreadStateException) {
                if (System.currentTimeMillis() > end) break
                try { Thread.sleep(50) } catch (ie: InterruptedException) { break }
            }
        }
        if (!finished) {
            p.destroy()
            outThread.join(2000); errThread.join(2000)
            return Result(124, out, "Timed out")
        }
        outThread.join(5000); errThread.join(5000)
        return Result(code, out, err)
    }

    /** Single-quote a string for the shell. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

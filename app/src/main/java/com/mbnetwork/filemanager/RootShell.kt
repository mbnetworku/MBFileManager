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
    fun run(cmd: String): Result {
        val p = Runtime.getRuntime().exec("su")
        var err = ""
        val errThread = Thread { err = p.errorStream.bufferedReader().readText() }.apply { start() }
        p.outputStream.bufferedWriter().use {
            it.write(cmd); it.write("\nexit\n"); it.flush()
        }
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        errThread.join(5000)
        return Result(code, out, err)
    }

    /** Single-quote a string for the shell. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

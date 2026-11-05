package com.mbnetwork.filemanager

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Simple app-wide music player. Keeps playing while you browse files (and, with the notification, in the background). */
object MusicPlayer {
    val AUDIO_EXT = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "amr", "wma", "mid", "midi")
    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    var queue: List<File> = emptyList(); private set
    var index = 0; private set
    var shuffle = false
    var repeatOne = false
    private var player: MediaPlayer? = null

    /** The player screen listens here. */
    var listener: (() -> Unit)? = null
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    fun addObserver(o: () -> Unit) { observers.addIfAbsent(o) }
    fun removeObserver(o: () -> Unit) { observers.remove(o) }
    private fun changed() { listener?.invoke(); observers.forEach { it() } }

    /** Needed to start / stop the notification service. */
    var appContext: Context? = null

    val active get() = player != null
    /** Needed by the equalizer to read the loudness of this player. */
    val audioSessionId get() = try { player?.audioSessionId ?: 0 } catch (e: Exception) { 0 }
    val current get() = queue.getOrNull(index)
    val isPlaying get() = try { player?.isPlaying == true } catch (e: Exception) { false }
    val position get() = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    val duration get() = try { player?.duration ?: 0 } catch (e: Exception) { 0 }

    /** Plays [file]; every audio file in the same folder becomes the playlist. */
    fun playFolder(file: File) {
        val songs = file.parentFile?.listFiles()?.filter { it.isFile && isAudio(it.name) }
            ?.sortedBy { it.name.lowercase() } ?: listOf(file)
        queue = songs.ifEmpty { listOf(file) }
        index = queue.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
        start()
    }

    private fun start() {
        val f = current ?: return
        player?.release()
        player = MediaPlayer().apply {
            @Suppress("DEPRECATION") setAudioStreamType(AudioManager.STREAM_MUSIC)
            appContext?.let { setWakeMode(it, PowerManager.PARTIAL_WAKE_LOCK) }   // keep playing with the screen off
            setOnCompletionListener { if (repeatOne) start() else next() }
            setOnErrorListener { _, _, _ -> changed(); true }
            try {
                setDataSource(f.absolutePath)
                prepare()
                start()
            } catch (e: Exception) { /* unsupported file */ }
        }
        refreshNotification()
        changed()
    }

    fun toggle() {
        val p = player ?: return start()
        if (p.isPlaying) p.pause() else p.start()
        changed()
    }

    fun next() {
        if (queue.isEmpty()) return
        index = if (shuffle) queue.indices.random() else (index + 1) % queue.size
        start()
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (position > 3000) { seek(0); return }
        index = if (index - 1 < 0) queue.size - 1 else index - 1
        start()
    }

    fun seek(ms: Int) { try { player?.seekTo(ms) } catch (e: Exception) {} }

    /** Plays the song at [i] in the list. */
    fun playAt(i: Int) {
        if (i !in queue.indices) return
        index = i
        start()
    }

    /** Removes a song from the list (the file stays). If it is the playing song, the next one plays. */
    fun removeAt(i: Int) {
        if (i !in queue.indices) return
        val wasCurrent = i == index
        queue = queue.toMutableList().also { it.removeAt(i) }
        when {
            queue.isEmpty() -> { stop(); return }
            wasCurrent -> { index = i.coerceAtMost(queue.size - 1); start(); return }
            i < index -> index--
        }
        changed()
    }

    /** Deletes the file (Recycle Bin when possible) and removes it from the list. */
    fun deleteFileAt(i: Int): Boolean {
        val f = queue.getOrNull(i) ?: return false
        val c = appContext
        val ok = try {
            if (c != null && Trash.canTrash(c, f.absolutePath) && f.canWrite()) { Trash.moveToTrash(c, f.absolutePath); true }
            else f.delete()
        } catch (e: Exception) { false }
        if (ok) removeAt(i)
        return ok
    }

    fun stop() {
        player?.release(); player = null
        changed()
    }

    /** Shows / updates the notification (if the user allows it in the settings). */
    fun refreshNotification() {
        val c = appContext ?: return
        if (!active || !Prefs(c).musicNotification) return
        try { ContextCompat.startForegroundService(c, Intent(c, MusicService::class.java)) } catch (e: Exception) { }
    }

    /** Called when the user switches the setting. */
    fun applyNotificationSetting() {
        val c = appContext ?: return
        if (Prefs(c).musicNotification) refreshNotification() else c.stopService(Intent(c, MusicService::class.java))
    }
}

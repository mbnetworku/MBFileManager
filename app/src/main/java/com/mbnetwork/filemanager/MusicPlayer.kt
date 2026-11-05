package com.mbnetwork.filemanager

import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

/** Simple app-wide music player. Keeps playing while you browse files. */
object MusicPlayer {
    val AUDIO_EXT = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "amr", "wma", "mid", "midi")
    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    var queue: List<File> = emptyList(); private set
    var index = 0; private set
    var shuffle = false
    var repeatOne = false
    private var player: MediaPlayer? = null
    var listener: (() -> Unit)? = null

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
            setOnCompletionListener { if (repeatOne) start() else next() }
            setOnErrorListener { _, _, _ -> listener?.invoke(); true }
            try {
                setDataSource(f.absolutePath)
                prepare()
                start()
            } catch (e: Exception) { /* unsupported file */ }
        }
        listener?.invoke()
    }

    fun toggle() {
        val p = player ?: return start()
        if (p.isPlaying) p.pause() else p.start()
        listener?.invoke()
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

    fun stop() {
        player?.release(); player = null
        listener?.invoke()
    }
}

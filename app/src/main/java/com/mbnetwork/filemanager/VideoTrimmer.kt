package com.mbnetwork.filemanager

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Cuts a video without re-encoding (fast, no quality loss): copies the audio / video samples between
 * [startMs] and [endMs] into a new MP4. Can drop the audio and set the rotation.
 * Works with H.264 / H.265 / AAC videos (MP4, MOV, 3GP).
 */
object VideoTrimmer {

    fun trim(src: String, dst: String, startMs: Long, endMs: Long, mute: Boolean, extraRotation: Int, progress: (Int) -> Unit) {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(src)
            var rot = 0
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(src)
                rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally { r.release() }

            val mx = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            mx.setOrientationHint((rot + extraRotation) % 360)

            val map = HashMap<Int, Int>()
            var maxSize = 1 shl 21
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || (mime.startsWith("audio/") && !mute)) {
                    ex.selectTrack(i)
                    map[i] = mx.addTrack(f)
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (map.isEmpty()) throw IllegalStateException("No video track")
            mx.start()

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            var base = -1L
            var lastPct = -1
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                if (base < 0) base = t
                val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, maxOf(0L, t - base), flags)
                map[ex.sampleTrackIndex]?.let { mx.writeSampleData(it, buf, info) }
                ex.advance()
                val pct = (((t - startUs).coerceAtLeast(0L)) * 100 / (endUs - startUs).coerceAtLeast(1L)).toInt().coerceIn(0, 99)
                if (pct != lastPct) { lastPct = pct; progress(pct) }
            }
            mx.stop()
            mx.release()
            muxer = null
            progress(100)
        } finally {
            try { ex.release() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
        }
    }
}

package com.mbnetwork.filemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Shows the "now playing" notification (cover, title, Previous / Play-Pause / Next / Stop)
 * and keeps the music alive in the background. Swipe it away or press ✕ to stop the music.
 */
class MusicService : Service() {

    companion object {
        private const val CHANNEL = "music"
        private const val NOTIF_ID = 7
        const val ACTION_PREV = "com.mbnetwork.filemanager.PREV"
        const val ACTION_TOGGLE = "com.mbnetwork.filemanager.TOGGLE"
        const val ACTION_NEXT = "com.mbnetwork.filemanager.NEXT"
        const val ACTION_STOP = "com.mbnetwork.filemanager.STOP"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var art: Bitmap? = null
    private var artPath: String? = null
    private var foreground = false
    private val observer: () -> Unit = { handler.post { update() } }

    override fun onCreate() {
        super.onCreate()
        MusicPlayer.appContext = applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, getString(R.string.music_notification), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        MusicPlayer.addObserver(observer)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> MusicPlayer.previous()
            ACTION_TOGGLE -> MusicPlayer.toggle()
            ACTION_NEXT -> MusicPlayer.next()
            ACTION_STOP -> MusicPlayer.stop()
        }
        // A service started with startForegroundService must call startForeground quickly.
        val song = MusicPlayer.current
        startForeground(NOTIF_ID, build(song?.nameWithoutExtension ?: getString(R.string.app_name), song?.parentFile?.name ?: ""))
        foreground = true
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MusicPlayer.removeObserver(observer)
        super.onDestroy()
    }

    private fun shutdown() {
        @Suppress("DEPRECATION") stopForeground(true)
        foreground = false
        stopSelf()
    }

    private fun update() {
        val song = MusicPlayer.current
        if (!MusicPlayer.active || song == null || !Prefs(this).musicNotification) { shutdown(); return }
        loadArt(song.absolutePath)
        val n = build(song.nameWithoutExtension, song.parentFile?.name ?: "")
        if (MusicPlayer.isPlaying) {
            startForeground(NOTIF_ID, n)
            foreground = true
        } else {
            // paused: the notification stays, but can be swiped away
            if (foreground) { @Suppress("DEPRECATION") stopForeground(false); foreground = false }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        }
    }

    private fun loadArt(path: String) {
        if (path == artPath) return
        artPath = path
        art = null
        Thread {
            var bmp: Bitmap? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                r.embeddedPicture?.let { b ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(b, 0, b.size, o)
                    var s = 1
                    while (o.outWidth / (s * 2) >= 256) s *= 2
                    bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
                }
            } catch (e: Exception) { } finally { try { r.release() } catch (e: Exception) { } }
            handler.post { if (artPath == path && bmp != null) { art = bmp; update() } }
        }.start()
    }

    private fun action(act: String, code: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(this, code, Intent(this, MusicService::class.java).setAction(act), flags)
    }

    @Suppress("DEPRECATION")
    private fun build(title: String, text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val playing = MusicPlayer.isPlaying
        b.setSmallIcon(R.drawable.ic_music_big)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP, 4))      // swiped away -> stop the music
            .setOngoing(playing)
            .setShowWhen(false)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        art?.let { b.setLargeIcon(it) }
        b.addAction(R.drawable.ic_prev, getString(R.string.previous), action(ACTION_PREV, 1))
        b.addAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, getString(R.string.play), action(ACTION_TOGGLE, 2))
        b.addAction(R.drawable.ic_next, getString(R.string.next), action(ACTION_NEXT, 3))
        b.addAction(R.drawable.ic_close, getString(R.string.stop), action(ACTION_STOP, 4))
        b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        return b.build()
    }
}

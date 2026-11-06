package com.mbnetwork.filemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps a copy / move / delete running in the background and shows its progress in the notification
 * (with a progress bar). When it finishes, the progress notification goes and a short "Done" notice is shown.
 */
class OpService : Service() {

    private val listener: () -> Unit = { update() }
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.op_channel), NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        OpProgress.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (OpProgress.active) {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, progress())
            foregroundStarted = true
        } else {
            update()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        OpProgress.removeListener(listener)
        super.onDestroy()
    }

    private fun progress(): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_archive)
            .setContentTitle(OpProgress.title)
            .setContentText(getString(R.string.op_count, OpProgress.done, OpProgress.total))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (OpProgress.total > 0) b.setProgress(OpProgress.total, OpProgress.done, false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun update() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (OpProgress.active) {
            if (foregroundStarted) nm.notify(NOTIF_ID, progress())
            return
        }
        // finished: remove the progress notification, show the result for a few seconds, then stop
        @Suppress("DEPRECATION") stopForeground(true)
        foregroundStarted = false
        nm.cancel(NOTIF_ID)
        val done = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_archive)
            .setContentTitle(OpProgress.title)
            .setContentText(OpProgress.result ?: getString(R.string.done))
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .build()
        nm.notify(DONE_ID, done)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "file_ops"
        private const val NOTIF_ID = 4217
        private const val DONE_ID = 4218
    }
}

package org.membraneframework.rtc.media.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

const val TAG = "SCREENCAST"

internal class ScreencastService : Service() {
    private var binder: IBinder = ScreencastBinder()
    private var bindCount = 0

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        bindCount++
        return binder
    }

    fun start(
        notificationId: Int?,
        notification: Notification?
    ) {
        Log.d(TAG, "start")

        val properNotification =
            if (notification != null) {
                notification
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannel()
                }

                NotificationCompat.Builder(this, DEFAULT_CHANNEL_ID)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            }

        startForeground(notificationId ?: DEFAULT_NOTIFICATION_ID, properNotification)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")

        stopForeground(true)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")

        super.onTaskRemoved(rootIntent)
        stopForeground(true)
        stopSelf()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        Log.d(TAG, "createNotificationChannel")

        val channel =
            NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        service.createNotificationChannel(channel)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")

        bindCount--

        if (bindCount == 0) {
            stopForeground(true)
            stopSelf()
        }

        return false
    }

    inner class ScreencastBinder : Binder() {
        val service: ScreencastService
            get() = this@ScreencastService
    }

    companion object {
        const val DEFAULT_NOTIFICATION_ID = 7777
        const val DEFAULT_CHANNEL_ID = "membrane_screen_capture"
    }
}

package com.akash.jarvismini

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class JarvisService : Service() {
    companion object {
        const val CHANNEL_ID = "jarvis_listening"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.akash.jarvismini.STOP"
        const val DEFAULT_INACTIVITY_MS = 60L * 60L * 1000L  // 1 hour
        private const val TAG = "JarvisService"

        private val _running = mutableStateOf(false)

        // Compose-readable state — flips when the service starts/stops.
        val running: State<Boolean> = _running

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, JarvisService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoOff = Runnable {
        Log.d(TAG, "auto-off timer fired, stopping")
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        _running.value = true
        rescheduleAutoOff()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoOff)
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun rescheduleAutoOff() {
        mainHandler.removeCallbacks(autoOff)
        mainHandler.postDelayed(autoOff, DEFAULT_INACTIVITY_MS)
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis listening",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification while Jarvis is active."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis is listening")
            .setContentText("Tap to open. Auto-stops after 1 hour of inactivity.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent,
            )
            .build()
    }
}

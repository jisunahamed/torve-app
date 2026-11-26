package com.example.feature.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.TorveApplication

/**
 * Keeps on-device sandbox commands and localhost dev servers alive in the background
 * with an Android 14+ compliant `dataSync` foreground service and bounded partial WakeLock.
 */
class SandboxForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            (application as? TorveApplication)?.container?.devServerManager?.stopAllServers()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val serverCount = intent?.getIntExtra(EXTRA_SERVER_COUNT, 0) ?: 0
        val statusText = when {
            serverCount == 1 -> "Torve Sandbox Running • 1 active server"
            serverCount > 1 -> "Torve Sandbox Running • $serverCount active servers"
            else -> "Torve Sandbox Running • Executing task"
        }

        acquireWakeLock()
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TorveAI::SandboxWakeLock").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // Safe 30-minute cap
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sandbox_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sandbox_notification_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SandboxForegroundService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_torve_logo)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Stop All", stopPending)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "torve_sandbox_runtime"
        const val NOTIFICATION_ID = 4091
        const val ACTION_UPDATE = "com.example.torve.ACTION_UPDATE_SANDBOX"
        const val ACTION_STOP_ALL = "com.example.torve.ACTION_STOP_ALL"
        const val EXTRA_SERVER_COUNT = "extra_server_count"

        fun syncRunningState(context: Context, activeServerCount: Int) {
            runCatching {
                val intent = Intent(context, SandboxForegroundService::class.java).apply {
                    action = if (activeServerCount > 0) ACTION_UPDATE else ACTION_STOP_ALL
                    putExtra(EXTRA_SERVER_COUNT, activeServerCount)
                }
                if (activeServerCount > 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                }
            }
        }
    }
}

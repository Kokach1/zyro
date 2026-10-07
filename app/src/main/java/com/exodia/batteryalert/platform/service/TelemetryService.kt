package com.exodia.batteryalert.platform.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.exodia.batteryalert.BatteryAlertApp
import com.exodia.batteryalert.MainActivity
import com.exodia.batteryalert.platform.audio.AndroidAlertOutput
import kotlinx.coroutines.*

/**
 * Foreground service ensuring uninterrupted monitoring across activity recreation and backgrounding.
 *
 * Enforces (FIX-18):
 *  - Single session holding transport, audio outputs, and lifecycle.
 *  - Ongoing notification with honest source and Stop action.
 *  - Professional wording without decorative pictograms.
 *  - Complete resource cancellation on Stop.
 */
class TelemetryService : Service() {

    companion object {
        const val CHANNEL_ID = "zyro_telemetry_monitor"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.exodia.batteryalert.action.START_MONITORING"
        const val ACTION_STOP = "com.exodia.batteryalert.action.STOP_MONITORING"

        fun start(context: Context) {
            val intent = Intent(context, TelemetryService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TelemetryService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var alertOutput: AndroidAlertOutput? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        alertOutput = AndroidAlertOutput(applicationContext, serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Monitoring active"))
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drone Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing telemetry and battery safety monitoring"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TelemetryService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zyro Battery Monitor")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }

    private fun stopMonitoring() {
        alertOutput?.release()
        alertOutput = null
        val app = application as? BatteryAlertApp
        serviceScope.launch {
            app?.container?.stopTransport()
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

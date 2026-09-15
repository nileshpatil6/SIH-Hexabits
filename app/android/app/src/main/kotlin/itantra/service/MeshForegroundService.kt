package itantra.service

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
import itantra.core.ItantraCore
import itantra.app.MainActivity

/** Keeps BLE / Wi-Fi Direct and the mic alive while the app is in the background. */
class MeshForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startInForeground()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "itantra:mesh").also { it.acquire(6 * 3600_000L) }
        }
        ItantraCore.get(this).startMesh()
        return START_STICKY
    }

    override fun onDestroy() {
        ItantraCore.get(this).stopMesh()
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "iTantra mesh", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the offline mesh radio running"
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("iTantra mesh active")
            .setContentText("Listening for nearby phones over Bluetooth and Wi-Fi Direct")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, n, type)
        } else startForeground(NOTIF_ID, n)
    }

    companion object {
        const val CHANNEL = "itantra_mesh"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "in.itantra.STOP"

        fun start(context: Context) {
            val i = Intent(context, MeshForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeshForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}


package app.lunex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps this phone's peer node alive when the user switches away from the screen.
 * Messages and room state remain in LocalNodeStore/SQLite if Android later kills
 * the process; START_STICKY lets Android recreate the live node when permitted.
 */
class LocalNodeService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Lunex — узел сети")
            .setContentText("Телефон участвует в синхронизации узлов")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        LocalNodeRuntime.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LocalNodeRuntime.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lunex node", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows when this phone's Lunex network node is running"
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "lunex_node"
        private const val NOTIFICATION_ID = 45873
    }
}

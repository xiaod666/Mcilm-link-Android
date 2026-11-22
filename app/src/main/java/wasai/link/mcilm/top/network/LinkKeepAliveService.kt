package wasai.link.mcilm.top.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import wasai.link.mcilm.top.MainActivity

class LinkKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "link_keep_alive"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "wasai.link.mcilm.top.STOP_KEEP_ALIVE"

        /** AppRoot 在进入/退出房间时发送：控制是否真正持有唤醒锁 */
        const val ACTION_SET_ACTIVE = "wasai.link.mcilm.top.SET_KEEP_ALIVE_ACTIVE"
        const val EXTRA_ACTIVE = "active"

        /** 唤醒锁最长持有时间：12 小时，防止异常情况下永久占用 */
        private const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseKeepAliveLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 【修复】此前服务只是挂了一条前台通知，没有任何真正阻止休眠的动作：
        // 锁屏后 CPU 很快休眠，联机心跳 / 语音会被系统挂起，保活名不副实。
        // 现在改为按「是否在房间中」精确持锁，避免只登录没开房时也白耗电。
        if (intent != null && intent.action == ACTION_SET_ACTIVE) {
            if (intent.getBooleanExtra(EXTRA_ACTIVE, false)) {
                acquireKeepAliveLocks()
            } else {
                releaseKeepAliveLocks()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseKeepAliveLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 持有 PARTIAL_WAKE_LOCK（保持 CPU 运行）+ Wi-Fi 高性能锁（保持无线网卡唤醒）。
     * 仅在联机会话期间存在，登出/手动停止时随服务一起释放。
     */
    private fun acquireKeepAliveLocks() {
        if (wakeLock?.isHeld == true) return

        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MciLm::LinkKeepAlive")?.apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
        } catch (t: Throwable) {
            android.util.Log.w("LinkKeepAlive", "acquire wake lock failed", t)
        }

        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MciLm::LinkWifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            android.util.Log.w("LinkKeepAlive", "acquire wifi lock failed", t)
        }
    }

    private fun releaseKeepAliveLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "联机保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持联机会话在后台稳定运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LinkKeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mcilm 联机助手")
            .setContentText("联机会话正在后台保持")
            // 【P2-8 修复】换成了通用的数据传输图标
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "停止保活", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
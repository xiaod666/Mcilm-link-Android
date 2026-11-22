package wasai.link.mcilm.top.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import wasai.link.mcilm.top.MainActivity

class LinkKeepAliveService : Service() {

    companion object {
        private const val TAG = "LinkKeepAlive"
        const val CHANNEL_ID = "link_keep_alive"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "wasai.link.mcilm.top.STOP_KEEP_ALIVE"

        /** AppRoot 在进入/退出房间时发送：控制是否真正持有唤醒锁 */
        const val ACTION_SET_ACTIVE = "wasai.link.mcilm.top.SET_KEEP_ALIVE_ACTIVE"
        const val EXTRA_ACTIVE = "active"

        /** 单次唤醒锁最长持有时间；到期前会自动续期 */
        private const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L

        /** 续期间隔：留足余量，长会话（>12h）也不会中途丢锁 */
        private const val RENEW_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val renewRunnable = Runnable {
        // 到期前强制重新申请一次；Wi-Fi 锁也在这里顺带重试（首次失败后没有其它重试入口）
        acquireWakeLock(force = true)
        acquireWifiLock()
        scheduleRenew()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 进程被系统回收后拉起：联机会话活在 App 进程里，此时已经不存在，
        // 再挂一条"正在后台保持"的通知只会误导用户。
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            releaseKeepAliveLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Android 12+ 允许从后台启动前台服务的场景有限，
            // 被拒时会抛 ForegroundServiceStartNotAllowedException —— 不能让它冒到主线程崩溃。
            Log.w(TAG, "startForeground failed", t)
            stopSelf()
            return START_NOT_STICKY
        }

        // 【修复】此前服务只是挂了一条前台通知，没有任何真正阻止休眠的动作：
        // 锁屏后 CPU 很快休眠，联机心跳 / 语音会被系统挂起，保活名不副实。
        // 现在改为按「是否在房间中」精确持锁，避免只登录没开房时也白耗电。
        if (intent.action == ACTION_SET_ACTIVE) {
            if (intent.getBooleanExtra(EXTRA_ACTIVE, false)) {
                acquireKeepAliveLocks()
            } else {
                releaseKeepAliveLocks()
            }
        }

        // 联机会话无法在进程死亡后存活，restart 只会留下"假保活"通知
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseKeepAliveLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 持有 PARTIAL_WAKE_LOCK（保持 CPU 运行）+ Wi-Fi 高性能锁（尽量保持无线网卡唤醒）。
     * 仅在联机会话期间存在，退房 / 停止保活 / 服务销毁时释放。
     */
    private fun acquireKeepAliveLocks() {
        acquireWakeLock(force = false)
        acquireWifiLock()
        scheduleRenew()
    }

    private fun acquireWakeLock(force: Boolean) {
        if (!force && wakeLock?.isHeld == true) return
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MciLm::LinkKeepAlive")?.apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "acquire wake lock failed", t)
        }
    }

    // HIGH_PERF 已被标记废弃，但它是唯一在息屏后仍尽量生效的模式；
    // 替代品 WIFI_MODE_FULL_LOW_LATENCY 只在"应用在前台且亮屏"时有效，不适合后台保活。
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MciLm::LinkWifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "acquire wifi lock failed", t)
        }
    }

    private fun scheduleRenew() {
        handler.removeCallbacks(renewRunnable)
        handler.postDelayed(renewRunnable, RENEW_INTERVAL_MS)
    }

    private fun releaseKeepAliveLocks() {
        handler.removeCallbacks(renewRunnable)
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

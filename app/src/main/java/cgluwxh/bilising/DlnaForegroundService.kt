package cgluwxh.bilising

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 前台 Service：
 *  - 显示持久通知，让系统知道 App 正在工作（不随意杀进程）
 *  - 持有 PARTIAL_WAKE_LOCK，屏幕关闭后 CPU 继续运行（Socket.IO + DLNA 轮询不中断）
 *
 * 由 DlnaPlayScreen 在进入时 startForegroundService，离开时 stopService。
 * 通过 Intent extra "status" 更新通知文字，反映当前播放状态。
 */
class DlnaForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "bilising_dlna_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_STATUS = "status"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("投屏模式运行中"))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bilising:dlna_wakelock").apply {
            // 最长持锁 6 小时，防止 KTV 整晚唱歌锁不释放
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra(EXTRA_STATUS)
        if (status != null) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(status))
        }
        // START_STICKY：Service 被杀后系统会尝试重建（但 WakeLock 会重新申请）
        return START_STICKY
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BiliSing 投屏",
                NotificationManager.IMPORTANCE_LOW  // LOW = 无声音，不打扰用户
            ).apply {
                description = "DLNA投屏连接保活"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BiliSing 投屏播放")
            .setContentText(status)
            .setOngoing(true)          // 用户无法手动划掉
            .setSilent(true)           // 不发出声音/震动
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

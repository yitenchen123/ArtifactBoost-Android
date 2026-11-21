package com.artifactboost.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.artifactboost.app.ArtifactBoostApp
import com.artifactboost.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 下载前台服务。
 *
 * 对应 iOS 的 `UIApplication.beginBackgroundTask`：下载期间挂一个前台通知，
 * 避免 App 切到后台后网络任务被系统立刻掐断。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(0, 0))

        // 每秒刷新一次通知里的进度
        if (ticker == null) {
            ticker = scope.launch {
                val manager = ArtifactBoostApp.instance.downloads
                while (true) {
                    val states = manager.states.value
                    val active = states.values.filterIsInstance<DownloadState.Downloading>()
                    if (active.isEmpty() && manager.activeCount == 0 && manager.queuedCount == 0) {
                        // 没有活跃任务了就撤下通知
                        stopSelf()
                        break
                    }
                    val progress = active.firstOrNull()?.progress
                    notify(
                        active.size,
                        progress?.fraction?.times(100)?.toInt() ?: 0,
                        progress?.speedBytesPerSecond ?: 0.0,
                    )
                    delay(1000)
                }
            }
        }
        return START_STICKY
    }

    private fun notify(count: Int, percent: Int, speed: Double) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(count, percent, speed))
    }

    private fun buildNotification(count: Int, percent: Int, speed: Double = 0.0) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ArtifactBoost 正在下载")
            .setContentText(
                if (count <= 1) "$percent% · ${com.artifactboost.app.util.formatSpeed(speed)}"
                else "共 $count 个任务进行中",
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "artifactboost_downloads"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

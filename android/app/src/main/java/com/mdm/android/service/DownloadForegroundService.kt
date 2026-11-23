package com.mdm.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mdm.android.MainActivity
import com.mdm.android.MdmApplication
import uniffi.mdm_android.DownloadEngine
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask
import uniffi.mdm_android.ProgressListener
import java.io.File

class DownloadForegroundService : Service(), ProgressListener {

    private var engine: DownloadEngine? = null

    override fun onCreate() {
        super.onCreate()
        val downloadDir = resolveDownloadDir(this)
        engine = DownloadEngine(downloadDir.absolutePath).apply {
            val prefs = getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)
            setDefaultConnections(prefs.getInt("max_connections", 8).toUInt())
            setSpeedLimitBps(prefs.getLong("speed_limit_bps", 0L).toULong())
            setUploadLimitBps(prefs.getLong("upload_limit_bps", 0L).toULong())
            setRetrySettings(
                prefs.getBoolean("retry_enabled", true),
                prefs.getInt("retry_max_attempts", 999999).toUInt(),
                prefs.getInt("retry_delay_secs", 5).toULong(),
                prefs.getBoolean("retry_always", false)
            )
            setRemoveAds(prefs.getBoolean("remove_ads", false))
            setHlsParallelSegments(prefs.getInt("hls_parallel_segments", 8).toUInt())
            setListener(this@DownloadForegroundService)
        }
        EngineRegistry.attach(engine!!)
        startForegroundCompat(buildNotification("MDM 下载引擎运行中", 0, false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD_HTTP -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    // 连接数传 0 = 用设置里的默认值
                    val filename = intent.getStringExtra(EXTRA_FILENAME)
                    runCatching { engine?.addHttpDownload(url, filename, 0u) }
                }
            }
            ACTION_ADD_MAGNET -> {
                val magnet = intent.getStringExtra(EXTRA_URL)
                if (!magnet.isNullOrBlank()) {
                    runCatching { engine?.addMagnetDownload(magnet) }
                }
            }
            ACTION_ADD_HLS -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    val filename = intent.getStringExtra(EXTRA_FILENAME)
                    runCatching { engine?.addHlsDownload(url, filename) }
                }
            }
            ACTION_EXIT -> {
                // 彻底退出：正在下载的任务会被停掉，通知栏随服务一起消失。
                engine?.setListener(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTasksUpdated(tasks: List<DownloadTask>) {
        EngineRegistry.publish(tasks)
        val active = tasks.filter { it.state == DownloadState.DOWNLOADING }
        if (active.isEmpty()) {
            updateNotification("暂无活动下载任务", 0, false)
            return
        }
        val total = active.sumOf { it.totalBytes.toLong() }
        val done = active.sumOf { it.downloadedBytes.toLong() }
        val progress = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val title = "正在下载 ${active.size} 个任务"
        updateNotification(title, progress, total == 0L)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String, progress: Int, indeterminate: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(content, progress, indeterminate))
    }

    private fun buildNotification(content: String, progress: Int, indeterminate: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val exitIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_EXIT
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MdmApplication.CHANNEL_DOWNLOADS)
            .setContentTitle("Mister Download Manager")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出", exitIntent)
            .build()
    }

    override fun onDestroy() {
        engine?.setListener(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_ADD_HTTP = "com.mdm.android.ADD_HTTP"
        const val ACTION_ADD_MAGNET = "com.mdm.android.ADD_MAGNET"
        const val ACTION_ADD_HLS = "com.mdm.android.ADD_HLS"
        const val ACTION_EXIT = "com.mdm.android.EXIT"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"

        /** 是否有“所有文件访问权限”（Android 11+）。 */
        fun hasAllFilesAccess(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                false
            }
        }

        /**
         * 下载保存目录：选了“手机存储”且有所有文件访问权限时用公共 Download/MDM
         * （文件管理器可见），否则回落到应用私有目录，保证下载不断。
         */
        fun resolveDownloadDir(context: Context): File {
            val prefs = context.getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)
            val wantPublic = prefs.getString("download_location", "public") != "private"
            val dir = if (wantPublic && hasAllFilesAccess(context)) {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MDM"
                )
            } else {
                File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
            }
            dir.mkdirs()
            return dir
        }
    }
}

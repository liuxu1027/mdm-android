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
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.mdm_android.DownloadEngine
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask
import uniffi.mdm_android.ProgressListener
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DownloadForegroundService : Service(), ProgressListener {

    private var engine: DownloadEngine? = null
    private var youtubeManager: YoutubeDownloadManager? = null
    private lateinit var taskStore: TaskStore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updatingYoutubeDL = AtomicBoolean(false)
    private var lastTaskCreatedAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        taskStore = TaskStore(this)
        val downloadDir = resolveDownloadDir(this)
        EngineRegistry.publishYoutube(emptyList())
        engine = DownloadEngine(downloadDir.absolutePath).apply {
            val prefs = getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)
            setDefaultConnections(prefs.getInt("max_connections", 8).toUInt())
            setMaxConcurrentTasks(prefs.getInt("max_concurrent_tasks", 3).coerceIn(1, 8).toUInt())
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
        }
        EngineRegistry.attach(engine!!)
        youtubeManager = if (YoutubeRuntime.youtubeEnabled) {
            YoutubeDownloadManager(this, downloadDir) { youtubeTasks ->
                EngineRegistry.publishYoutube(youtubeTasks)
                runCatching { taskStore.saveThrottled(EngineRegistry.tasks.value) }
                syncTaskConcurrencyLimits()
                refreshNotification()
            }
        } else null
        // 先从库里恢复任务，再接监听，避免空任务快照清空数据库。
        runCatching {
            val saved = taskStore.loadAll()
            val creationTimes = taskStore.creationTimes()
            EngineRegistry.restoreCreationTimes(creationTimes)
            lastTaskCreatedAtMs = creationTimes.values.maxOrNull() ?: 0L
            val externalTasks = saved.filter {
                it.kind != uniffi.mdm_android.DownloadKind.YOUTUBE && it.savePath.startsWith("content://")
            }
            val externalIds = externalTasks.mapTo(mutableSetOf()) { it.id }
            saved.filter {
                it.kind != uniffi.mdm_android.DownloadKind.YOUTUBE && !it.savePath.startsWith("content://")
            }
                .forEach { task -> runCatching { engine?.restoreTask(task) } }
            val nativeTasks = engine?.listTasks().orEmpty().filterNot { it.id in externalIds }
            EngineRegistry.publish(nativeTasks + externalTasks)
            youtubeManager?.restore(saved.filter { it.kind == uniffi.mdm_android.DownloadKind.YOUTUBE })
        }
        engine?.setListener(this@DownloadForegroundService)
        startForegroundCompat(buildNotification("MDM 下载引擎运行中", 0, false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD_HTTP -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    val filename = intent.getStringExtra(EXTRA_FILENAME)
                    runCatching { engine?.addHttpDownload(url, filename, 0u) }
                        .getOrNull()?.let(::noteTaskCreated)
                }
            }
            ACTION_ADD_MAGNET -> {
                val magnet = intent.getStringExtra(EXTRA_URL)
                if (!magnet.isNullOrBlank()) {
                    runCatching { engine?.addMagnetDownload(magnet) }.getOrNull()?.let(::noteTaskCreated)
                }
            }
            ACTION_ADD_HLS -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    val filename = intent.getStringExtra(EXTRA_FILENAME)
                    runCatching { engine?.addHlsDownload(url, filename) }
                        .getOrNull()?.let(::noteTaskCreated)
                }
            }
            ACTION_ADD_YOUTUBE -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (YoutubeRuntime.youtubeEnabled && !url.isNullOrBlank()) {
                    youtubeManager?.add(url, intent.getStringExtra(EXTRA_FILENAME))?.let(::noteTaskCreated)
                }
            }
            ACTION_ADD_YOUTUBE_BATCH -> {
                val urls = intent.getStringArrayListExtra(EXTRA_YOUTUBE_URLS).orEmpty()
                val names = intent.getStringArrayListExtra(EXTRA_YOUTUBE_NAMES).orEmpty()
                val formats = intent.getStringArrayListExtra(EXTRA_YOUTUBE_FORMATS).orEmpty()
                val subtitles = intent.getStringArrayListExtra(EXTRA_YOUTUBE_SUBTITLES).orEmpty()
                if (YoutubeRuntime.youtubeEnabled) {
                    urls.forEachIndexed { index, url ->
                        youtubeManager?.add(
                            url,
                            names.getOrNull(index),
                            formats.getOrNull(index),
                            subtitles.getOrNull(index)?.takeIf { it.isNotBlank() }
                        )?.let(::noteTaskCreated)
                    }
                }
            }
            ACTION_PAUSE_YOUTUBE -> intent.getStringExtra(EXTRA_TASK_ID)?.let { youtubeManager?.pause(it) }
            ACTION_RESUME_YOUTUBE -> intent.getStringExtra(EXTRA_TASK_ID)?.let { youtubeManager?.resume(it) }
            ACTION_REMOVE_YOUTUBE -> intent.getStringExtra(EXTRA_TASK_ID)?.let { id ->
                youtubeManager?.remove(id, intent.getBooleanExtra(EXTRA_DELETE_FILES, false))
            }
            ACTION_REMOVE_EXTERNAL_TASK -> intent.getStringExtra(EXTRA_TASK_ID)?.let { id ->
                val task = EngineRegistry.tasks.value.firstOrNull { it.id == id }
                if (task?.kind == uniffi.mdm_android.DownloadKind.YOUTUBE) {
                    youtubeManager?.remove(id, false)
                } else {
                    runCatching { engine?.removeTask(id, false) }
                    EngineRegistry.removeTask(id)
                }
                runCatching { taskStore.saveNow(EngineRegistry.tasks.value) }
            }
            ACTION_REDOWNLOAD_YOUTUBE -> intent.getStringExtra(EXTRA_TASK_ID)?.let { youtubeManager?.redownload(it) }
            ACTION_RENAME_YOUTUBE -> {
                val id = intent.getStringExtra(EXTRA_TASK_ID)
                val name = intent.getStringExtra(EXTRA_FILENAME)
                if (!id.isNullOrBlank() && !name.isNullOrBlank()) youtubeManager?.rename(id, name)
            }
            ACTION_UPDATE_TASK_LOCATION -> {
                val id = intent.getStringExtra(EXTRA_TASK_ID)
                val path = intent.getStringExtra(EXTRA_TASK_PATH)
                val title = intent.getStringExtra(EXTRA_FILENAME)
                if (!id.isNullOrBlank() && !path.isNullOrBlank() && !title.isNullOrBlank()) {
                    val task = EngineRegistry.tasks.value.firstOrNull { it.id == id }
                    if (task?.kind == uniffi.mdm_android.DownloadKind.YOUTUBE) {
                        youtubeManager?.updateLocation(id, path, title)
                    } else {
                        EngineRegistry.updateTask(id) { it.copy(savePath = path, title = title) }
                    }
                    runCatching { taskStore.saveNow(EngineRegistry.tasks.value) }
                }
            }
            ACTION_UPDATE_YTDLP -> if (YoutubeRuntime.youtubeEnabled) checkYoutubeDLUpdates()
                else publishYtDlpStatus("此版本不支持该更新操作", 0, false)
            ACTION_UPDATE_CONCURRENCY -> syncTaskConcurrencyLimits()
            ACTION_EXIT -> {
                youtubeManager?.shutdown()
                runCatching { taskStore.saveNow(EngineRegistry.tasks.value) }
                engine?.setListener(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTasksUpdated(tasks: List<DownloadTask>) {
        val previous = EngineRegistry.tasks.value.associateBy { it.id }
        val incomingIds = tasks.mapTo(mutableSetOf()) { it.id }
        val merged = tasks.map { task ->
            val external = previous[task.id]?.takeIf { it.savePath.startsWith("content://") }
            if (external == null) task else task.copy(savePath = external.savePath, title = external.title)
        } + previous.values.filter { it.savePath.startsWith("content://") && it.id !in incomingIds }
        EngineRegistry.publish(merged)
        syncTaskConcurrencyLimits()
        runCatching { taskStore.saveThrottled(EngineRegistry.tasks.value) }
        refreshNotification()
    }

    @Synchronized
    private fun noteTaskCreated(id: String) {
        val createdAt = maxOf(System.currentTimeMillis(), lastTaskCreatedAtMs + 1L)
        lastTaskCreatedAtMs = createdAt
        EngineRegistry.noteTaskCreated(id, createdAt)
        taskStore.noteTaskCreated(id, createdAt)
        runCatching { taskStore.saveNow(EngineRegistry.tasks.value) }
        syncTaskConcurrencyLimits()
    }

    /** The native engine and yt-dlp manager share one user-visible active-task budget. */
    @Synchronized
    private fun syncTaskConcurrencyLimits() {
        val maximum = getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)
            .getInt("max_concurrent_tasks", 3).coerceIn(1, 8)
        val snapshot = EngineRegistry.tasks.value
        val nativeActive = snapshot.count {
            it.kind != uniffi.mdm_android.DownloadKind.YOUTUBE &&
                it.state == DownloadState.DOWNLOADING
        }
        val youtubeActive = snapshot.count {
            it.kind == uniffi.mdm_android.DownloadKind.YOUTUBE &&
                it.state == DownloadState.DOWNLOADING
        }
        engine?.setMaxConcurrentTasks((maximum - youtubeActive).coerceAtLeast(0).toUInt())
        youtubeManager?.setMaxActiveDownloads((maximum - nativeActive).coerceAtLeast(0))
    }

    private fun refreshNotification() {
        val active = EngineRegistry.tasks.value.filter { it.state == DownloadState.DOWNLOADING }
        if (active.isEmpty()) {
            updateNotification("暂无活动下载任务", 0, false)
            return
        }
        val total = active.sumOf { it.totalBytes.toLong() }
        val done = active.sumOf { it.downloadedBytes.toLong() }
        val progress = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        updateNotification("正在下载 ${active.size} 个任务", progress, total == 0L)
    }

    private fun checkYoutubeDLUpdates() {
        if (!updatingYoutubeDL.compareAndSet(false, true)) {
            publishYtDlpStatus("yt-dlp 更新检查已在进行中", 0, true)
            return
        }
        if (youtubeManager?.hasActiveDownloads() == true) {
            publishYtDlpStatus("请先暂停 YouTube 下载，再检查 yt-dlp 更新", 0, false)
            updatingYoutubeDL.set(false)
            return
        }
        publishYtDlpStatus("正在使用 Stable 检查 yt-dlp 更新…", 0, true)
        serviceScope.launch {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    YoutubeDL.UpdateChannel.STABLE
                )
                val version = YoutubeDL.getInstance().versionName(applicationContext).orEmpty()
                val message = when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp 已更新${version.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp 已是最新 Stable 版本${version.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
                    null -> "yt-dlp 更新检查已完成"
                }
                publishYtDlpStatus(message, 0, false)
            } catch (error: Exception) {
                publishYtDlpStatus("yt-dlp 更新检查失败：${error.message?.take(160) ?: "未知错误"}", 0, false)
            } finally {
                updatingYoutubeDL.set(false)
            }
        }
    }

    private fun publishYtDlpStatus(message: String, progress: Int, indeterminate: Boolean) {
        updateNotification(message, progress, indeterminate)
        sendBroadcast(
            Intent(ACTION_YTDLP_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_YTDLP_STATUS, message)
        )
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
            .setContentTitle("MDM")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出", exitIntent)
            .build()
    }

    override fun onDestroy() {
        youtubeManager?.shutdown()
        if (::taskStore.isInitialized) {
            runCatching { taskStore.saveNow(EngineRegistry.tasks.value) }
        }
        engine?.setListener(null)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_ADD_HTTP = "com.mdm.android.ADD_HTTP"
        const val ACTION_ADD_MAGNET = "com.mdm.android.ADD_MAGNET"
        const val ACTION_ADD_HLS = "com.mdm.android.ADD_HLS"
        const val ACTION_ADD_YOUTUBE = "com.mdm.android.ADD_YOUTUBE"
        const val ACTION_ADD_YOUTUBE_BATCH = "com.mdm.android.ADD_YOUTUBE_BATCH"
        const val ACTION_PAUSE_YOUTUBE = "com.mdm.android.PAUSE_YOUTUBE"
        const val ACTION_RESUME_YOUTUBE = "com.mdm.android.RESUME_YOUTUBE"
        const val ACTION_REMOVE_YOUTUBE = "com.mdm.android.REMOVE_YOUTUBE"
        const val ACTION_REMOVE_EXTERNAL_TASK = "com.mdm.android.REMOVE_EXTERNAL_TASK"
        const val ACTION_REDOWNLOAD_YOUTUBE = "com.mdm.android.REDOWNLOAD_YOUTUBE"
        const val ACTION_RENAME_YOUTUBE = "com.mdm.android.RENAME_YOUTUBE"
        const val ACTION_UPDATE_TASK_LOCATION = "com.mdm.android.UPDATE_TASK_LOCATION"
        const val ACTION_UPDATE_YTDLP = "com.mdm.android.UPDATE_YTDLP"
        const val ACTION_YTDLP_STATUS = "com.mdm.android.YTDLP_STATUS"
        const val ACTION_UPDATE_CONCURRENCY = "com.mdm.android.UPDATE_CONCURRENCY"
        const val ACTION_EXIT = "com.mdm.android.EXIT"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_PATH = "extra_task_path"
        const val EXTRA_DELETE_FILES = "extra_delete_files"
        const val EXTRA_YOUTUBE_URLS = "extra_youtube_urls"
        const val EXTRA_YOUTUBE_NAMES = "extra_youtube_names"
        const val EXTRA_YOUTUBE_FORMATS = "extra_youtube_formats"
        const val EXTRA_YOUTUBE_SUBTITLES = "extra_youtube_subtitles"
        const val EXTRA_YTDLP_STATUS = "extra_ytdlp_status"

        /** 是否有“所有文件访问权限”（Android 11+）。 */
        fun hasAllFilesAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else false

        /** 下载保存目录；无权限时回落至应用私有目录。 */
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

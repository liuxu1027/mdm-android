package com.mdm.android.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.mdm.android.service.DownloadForegroundService
import com.mdm.android.service.EngineRegistry
import kotlinx.coroutines.flow.StateFlow
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val tasks: StateFlow<List<DownloadTask>> = EngineRegistry.tasks

    private val prefs =
        application.getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)

    /** 每任务默认连接数（1-16），新下载生效。 */
    var maxConnections by mutableStateOf(prefs.getInt("max_connections", 8))
        private set

    /** 全局限速 bytes/s，0 = 不限速。 */
    var speedLimitBps by mutableLongStateOf(prefs.getLong("speed_limit_bps", 0L))
        private set

    /** 下载保存目录（只读展示）。 */
    val downloadDir: String =
        DownloadForegroundService.resolveDownloadDir(application).absolutePath

    fun ensureServiceStarted() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, DownloadForegroundService::class.java)
        )
    }

    fun checkYtDlpUpdates() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_UPDATE_YTDLP
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun updateMaxConnections(value: Int) {
        val v = value.coerceIn(1, 16)
        maxConnections = v
        prefs.edit().putInt("max_connections", v).apply()
        EngineRegistry.engine?.setDefaultConnections(v.toUInt())
    }

    fun updateSpeedLimitBps(value: Long) {
        val v = value.coerceAtLeast(0L)
        speedLimitBps = v
        prefs.edit().putLong("speed_limit_bps", v).apply()
        EngineRegistry.engine?.setSpeedLimitBps(v.toULong())
    }

    /** 全局上传限速 bytes/s，0 = 不限速（只对磁力做种上传生效）。 */
    var uploadLimitBps by mutableLongStateOf(prefs.getLong("upload_limit_bps", 0L))
        private set

    fun updateUploadLimitBps(value: Long) {
        val v = value.coerceAtLeast(0L)
        uploadLimitBps = v
        prefs.edit().putLong("upload_limit_bps", v).apply()
        EngineRegistry.engine?.setUploadLimitBps(v.toULong())
    }

    /** m3u8 出错自动重试（对标 1DM 的重试设置）。 */
    var retryEnabled by mutableStateOf(prefs.getBoolean("retry_enabled", true))
        private set
    var retryMaxAttempts by mutableIntStateOf(prefs.getInt("retry_max_attempts", 999999))
        private set
    var retryDelaySecs by mutableIntStateOf(prefs.getInt("retry_delay_secs", 5))
        private set
    var retryAlways by mutableStateOf(prefs.getBoolean("retry_always", false))
        private set

    private fun applyRetrySettings() {
        EngineRegistry.engine?.setRetrySettings(
            retryEnabled,
            retryMaxAttempts.toUInt(),
            retryDelaySecs.toULong(),
            retryAlways
        )
    }

    fun updateRetryEnabled(value: Boolean) {
        retryEnabled = value
        prefs.edit().putBoolean("retry_enabled", value).apply()
        applyRetrySettings()
    }

    fun updateRetryMaxAttempts(value: Int) {
        val v = value.coerceAtLeast(0)
        retryMaxAttempts = v
        prefs.edit().putInt("retry_max_attempts", v).apply()
        applyRetrySettings()
    }

    fun updateRetryDelaySecs(value: Int) {
        val v = value.coerceAtLeast(0)
        retryDelaySecs = v
        prefs.edit().putInt("retry_delay_secs", v).apply()
        applyRetrySettings()
    }

    fun updateRetryAlways(value: Boolean) {
        retryAlways = value
        prefs.edit().putBoolean("retry_always", value).apply()
        applyRetrySettings()
    }

    /** m3u8 去广告：同一列表拉两次，不一样的分片就是动态插入的广告，跳过不下。 */
    var removeAds by mutableStateOf(prefs.getBoolean("remove_ads", false))
        private set

    fun updateRemoveAds(value: Boolean) {
        removeAds = value
        prefs.edit().putBoolean("remove_ads", value).apply()
        EngineRegistry.engine?.setRemoveAds(value)
    }

    /** m3u8 同时下载的分片数（4-16），默认 8，新下载生效。 */
    var hlsParallelSegments by mutableIntStateOf(prefs.getInt("hls_parallel_segments", 8))
        private set

    fun updateHlsParallelSegments(value: Int) {
        val v = value.coerceIn(4, 16)
        hlsParallelSegments = v
        prefs.edit().putInt("hls_parallel_segments", v).apply()
        EngineRegistry.engine?.setHlsParallelSegments(v.toUInt())
    }

    /** 下载位置选择：public = 手机存储 Download/MDM，private = 应用私有目录。 */
    var downloadLocation by mutableStateOf(prefs.getString("download_location", "public") ?: "public")
        private set

    fun updateDownloadLocation(value: String) {
        val v = if (value == "private") "private" else "public"
        downloadLocation = v
        prefs.edit().putString("download_location", v).apply()
    }

    fun addLink(rawInput: String, filename: String? = null) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val ctx = getApplication<Application>()
        val action = when {
            trimmed.startsWith("magnet:?") -> DownloadForegroundService.ACTION_ADD_MAGNET
            isYouTubePageLink(trimmed) -> DownloadForegroundService.ACTION_ADD_YOUTUBE
            trimmed.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ->
                DownloadForegroundService.ACTION_ADD_HLS
            else -> DownloadForegroundService.ACTION_ADD_HTTP
        }
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            this.action = action
            putExtra(DownloadForegroundService.EXTRA_URL, trimmed)
            if (!filename.isNullOrBlank()) {
                putExtra(DownloadForegroundService.EXTRA_FILENAME, filename.trim())
            }
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    /** 彻底退出：停掉下载服务、去掉通知栏，界面随后关闭。 */
    fun exit() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_EXIT
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun pause(id: String) {
        if (tasks.value.firstOrNull { it.id == id }?.kind == DownloadKind.YOUTUBE) {
            sendYoutubeTaskAction(DownloadForegroundService.ACTION_PAUSE_YOUTUBE, id)
        } else {
            runCatching { EngineRegistry.engine?.pauseTask(id) }
        }
    }

    fun resume(id: String) {
        if (tasks.value.firstOrNull { it.id == id }?.kind == DownloadKind.YOUTUBE) {
            sendYoutubeTaskAction(DownloadForegroundService.ACTION_RESUME_YOUTUBE, id)
        } else {
            runCatching { EngineRegistry.engine?.resumeTask(id) }
        }
    }

    fun remove(id: String, deleteFiles: Boolean) {
        if (tasks.value.firstOrNull { it.id == id }?.kind == DownloadKind.YOUTUBE) {
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_REMOVE_YOUTUBE
                putExtra(DownloadForegroundService.EXTRA_TASK_ID, id)
                putExtra(DownloadForegroundService.EXTRA_DELETE_FILES, deleteFiles)
            }
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            runCatching { EngineRegistry.engine?.removeTask(id, deleteFiles) }
        }
    }

    private fun sendYoutubeTaskAction(action: String, id: String) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            this.action = action
            putExtra(DownloadForegroundService.EXTRA_TASK_ID, id)
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun rename(id: String, newTitle: String): Result<Unit> = runCatching {
        require(newTitle.isNotBlank()) { "文件名不能为空" }
        val engine = EngineRegistry.engine ?: error("下载引擎尚未启动")
        engine.renameTask(id, newTitle.trim())
    }

    fun clearFinished() {
        tasks.value
            .filter { it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED }
            .forEach { remove(it.id, false) }
    }
}

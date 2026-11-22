package com.mdm.android.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.mdm.android.service.DownloadForegroundService
import com.mdm.android.service.EngineRegistry
import kotlinx.coroutines.flow.StateFlow
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

    fun addLink(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val ctx = getApplication<Application>()
        val action = when {
            trimmed.startsWith("magnet:?") -> DownloadForegroundService.ACTION_ADD_MAGNET
            trimmed.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ->
                DownloadForegroundService.ACTION_ADD_HLS
            else -> DownloadForegroundService.ACTION_ADD_HTTP
        }
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            this.action = action
            putExtra(DownloadForegroundService.EXTRA_URL, trimmed)
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
        runCatching { EngineRegistry.engine?.pauseTask(id) }
    }

    fun resume(id: String) {
        runCatching { EngineRegistry.engine?.resumeTask(id) }
    }

    fun remove(id: String) {
        runCatching { EngineRegistry.engine?.removeTask(id) }
    }

    fun clearFinished() {
        tasks.value
            .filter { it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED }
            .forEach { remove(it.id) }
    }
}

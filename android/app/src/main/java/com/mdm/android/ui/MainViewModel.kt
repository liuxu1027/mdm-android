package com.mdm.android.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mdm.android.service.DownloadForegroundService
import com.mdm.android.service.EngineRegistry
import com.mdm.android.service.YoutubeRuntime
import com.mdm.android.service.YoutubeDownloadSpec
import com.mdm.android.service.YoutubeMediaResolver
import com.mdm.android.service.YoutubeResolveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask
import java.io.File

sealed class YoutubeResolveUiState {
    object Idle : YoutubeResolveUiState()
    object Loading : YoutubeResolveUiState()
    data class Ready(
        val result: YoutubeResolveResult,
        val initialTargetHeight: Int? = null,
        val initialAudioCeiling: Int? = null
    ) : YoutubeResolveUiState()
    data class Failed(val message: String) : YoutubeResolveUiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val tasks: StateFlow<List<DownloadTask>> = EngineRegistry.tasks

    private val prefs =
        application.getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)

    var youtubeResolveState by mutableStateOf<YoutubeResolveUiState>(YoutubeResolveUiState.Idle)
        private set
    private var youtubeResolveJob: kotlinx.coroutines.Job? = null
    private var youtubeRequestedName: String? = null
    var ytDlpUpdateStatus by mutableStateOf<String?>(null)
        private set
    var checkingYtDlpUpdate by mutableStateOf(false)
        private set

    /** 每任务默认连接数（1-16），新下载生效。 */
    var maxConnections by mutableStateOf(prefs.getInt("max_connections", 8))
        private set

    /** 同时运行的下载任务总数（1-8），默认 3。 */
    var maxConcurrentTasks by mutableIntStateOf(prefs.getInt("max_concurrent_tasks", 3).coerceIn(1, 8))
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
        checkingYtDlpUpdate = true
        ytDlpUpdateStatus = "正在使用 Stable 检查 yt-dlp 更新…"
        runCatching {
            val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_UPDATE_YTDLP
            }
            ContextCompat.startForegroundService(ctx, intent)
        }.onFailure { error ->
            checkingYtDlpUpdate = false
            ytDlpUpdateStatus = "无法启动更新检查：${error.message ?: "未知错误"}"
        }
    }

    fun onYtDlpUpdateStatus(message: String) {
        ytDlpUpdateStatus = message
        checkingYtDlpUpdate = message.startsWith("正在") || message.contains("已在进行中")
    }

    fun updateMaxConnections(value: Int) {
        val v = value.coerceIn(1, 16)
        maxConnections = v
        prefs.edit().putInt("max_connections", v).apply()
        EngineRegistry.engine?.setDefaultConnections(v.toUInt())
    }

    fun updateMaxConcurrentTasks(value: Int) {
        val v = value.coerceIn(1, 8)
        maxConcurrentTasks = v
        prefs.edit().putInt("max_concurrent_tasks", v).apply()
        EngineRegistry.engine?.setMaxConcurrentTasks(v.toUInt())
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_UPDATE_CONCURRENCY
        }
        runCatching { ContextCompat.startForegroundService(ctx, intent) }
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

    fun addLink(
        rawInput: String,
        filename: String? = null,
        targetHeight: Int? = null,
        audioCeiling: Int? = null
    ) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        if (isYouTubePageLink(trimmed)) {
            if (!YoutubeRuntime.youtubeEnabled) {
                youtubeResolveState = YoutubeResolveUiState.Failed("此版本不支持该链接类型。")
                return
            }
            if (targetHeight != null && audioCeiling != null && !isYouTubePlaylistLink(trimmed)) {
                youtubeRequestedName = filename?.trim()?.takeIf { it.isNotBlank() }
                val spec = YoutubeMediaResolver.directSpec(
                    url = trimmed,
                    requestedName = youtubeRequestedName,
                    targetHeight = targetHeight,
                    audioCeiling = audioCeiling,
                    ffmpegEnabled = YoutubeRuntime.ffmpegEnabled
                )
                addYoutubeDownloads(listOf(spec))
            } else {
                resolveYoutubeLink(trimmed, filename, targetHeight, audioCeiling)
            }
            return
        }
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
            if (!filename.isNullOrBlank()) {
                putExtra(DownloadForegroundService.EXTRA_FILENAME, filename.trim())
            }
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun resolveYoutubeLink(
        url: String,
        filename: String? = null,
        targetHeight: Int? = null,
        audioCeiling: Int? = null
    ) {
        if (!YoutubeRuntime.youtubeEnabled) {
            youtubeResolveState = YoutubeResolveUiState.Failed("此版本不支持该链接类型。")
            return
        }
        youtubeResolveJob?.cancel()
        youtubeRequestedName = filename?.trim()?.takeIf { it.isNotBlank() }
        youtubeResolveState = YoutubeResolveUiState.Loading
        youtubeResolveJob = viewModelScope.launch {
            try {
                val result = YoutubeMediaResolver.resolve(getApplication(), url.trim())
                youtubeResolveState = YoutubeResolveUiState.Ready(result, targetHeight, audioCeiling)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                youtubeResolveState = YoutubeResolveUiState.Failed(
                    error.message?.take(220)?.ifBlank { null } ?: "YouTube 信息解析失败"
                )
            }
        }
    }

    fun dismissYoutubePicker() {
        youtubeResolveJob?.cancel()
        youtubeResolveJob = null
        youtubeRequestedName = null
        youtubeResolveState = YoutubeResolveUiState.Idle
    }

    fun addYoutubeDownloads(specs: List<YoutubeDownloadSpec>) {
        if (specs.isEmpty()) return
        val ctx = getApplication<Application>()
        val names = ArrayList(specs.mapIndexed { index, spec ->
            if (specs.size == 1 && index == 0) youtubeRequestedName ?: spec.title else spec.title
        })
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_ADD_YOUTUBE_BATCH
            putStringArrayListExtra(DownloadForegroundService.EXTRA_YOUTUBE_URLS, ArrayList(specs.map { it.url }))
            putStringArrayListExtra(DownloadForegroundService.EXTRA_YOUTUBE_NAMES, names)
            putStringArrayListExtra(DownloadForegroundService.EXTRA_YOUTUBE_FORMATS, ArrayList(specs.map { it.formatSelector }))
            putStringArrayListExtra(DownloadForegroundService.EXTRA_YOUTUBE_SUBTITLES, ArrayList(specs.map { it.subtitleLanguage.orEmpty() }))
        }
        ContextCompat.startForegroundService(ctx, intent)
        dismissYoutubePicker()
    }

    fun redownload(task: DownloadTask) {
        if (task.kind == DownloadKind.YOUTUBE) {
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_REDOWNLOAD_YOUTUBE
                putExtra(DownloadForegroundService.EXTRA_TASK_ID, task.id)
            }
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            addLink(task.sourceUrl, task.title)
        }
    }

    fun retryOptions(task: DownloadTask) {
        if (task.kind == DownloadKind.YOUTUBE) {
            resolveYoutubeLink(task.sourceUrl)
        } else {
            redownload(task)
        }
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
        val task = tasks.value.firstOrNull { it.id == id }
        if (task?.savePath?.startsWith("content://") == true) {
            if (deleteFiles) {
                runCatching { getApplication<Application>().contentResolver.delete(Uri.parse(task.savePath), null, null) }
            }
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_REMOVE_EXTERNAL_TASK
                putExtra(DownloadForegroundService.EXTRA_TASK_ID, id)
            }
            ContextCompat.startForegroundService(ctx, intent)
            return
        }
        if (task?.kind == DownloadKind.YOUTUBE) {
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
        val task = tasks.value.firstOrNull { it.id == id } ?: error("找不到下载任务")
        if (task.savePath.startsWith("content://")) {
            val extension = task.title.substringAfterLast('.', "")
                .takeIf { it.isNotBlank() && task.title.lastIndexOf('.') > 0 }
            val enteredName = newTitle.trim()
            val preservedName = if (extension == null) enteredName
            else "${enteredName.substringBeforeLast('.', enteredName)}.$extension"
            val renamed = DocumentsContract.renameDocument(
                getApplication<Application>().contentResolver,
                Uri.parse(task.savePath),
                preservedName
            ) ?: error("系统文件管理器无法重命名此文件")
            updateTaskLocation(id, renamed.toString(), preservedName)
            return@runCatching
        }
        if (task.kind == DownloadKind.YOUTUBE) {
            val source = File(task.savePath)
            require(source.isFile) { "找不到下载文件，无法重命名" }
            val extension = source.extension.takeIf { it.isNotBlank() }
            val enteredName = newTitle.trim()
            val baseName = if (extension == null) enteredName else enteredName.substringBeforeLast('.', enteredName)
            val destination = File(source.parentFile, buildString {
                append(baseName)
                if (extension != null) append(".$extension")
            })
            require(source.absolutePath == destination.absolutePath || !destination.exists()) { "同名文件已存在，请换一个名称" }
            if (source.absolutePath != destination.absolutePath) {
                check(source.renameTo(destination)) { "重命名失败" }
            }
            val cleanBase = destination.nameWithoutExtension
            getApplication<Application>()
                .getSharedPreferences("youtube_download_options", Context.MODE_PRIVATE)
                .edit()
                .putString("$id.name", cleanBase)
                .putString("$id.prefix", cleanBase)
                .apply()
            updateTaskLocation(id, destination.absolutePath, destination.name)
            return@runCatching
        }
        val engine = EngineRegistry.engine ?: error("下载引擎尚未启动")
        engine.renameTask(id, newTitle.trim())
    }

    fun updateTaskLocation(id: String, path: String, title: String) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_UPDATE_TASK_LOCATION
            putExtra(DownloadForegroundService.EXTRA_TASK_ID, id)
            putExtra(DownloadForegroundService.EXTRA_TASK_PATH, path)
            putExtra(DownloadForegroundService.EXTRA_FILENAME, title)
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun clearFinished() {
        tasks.value
            .filter { it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED }
            .forEach { remove(it.id, false) }
    }
}

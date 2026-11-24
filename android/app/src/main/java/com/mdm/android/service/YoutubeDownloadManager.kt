package com.mdm.android.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** MDM task-list adapter for yt-dlp's Android runtime. */
class YoutubeDownloadManager(
    context: Context,
    private val downloadDir: File,
    private val onTasksChanged: (List<DownloadTask>) -> Unit
) {
    private val appContext = context.applicationContext
    private val optionsPrefs = appContext.getSharedPreferences("youtube_download_options", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val tasks = linkedMapOf<String, DownloadTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processIds = ConcurrentHashMap<String, String>()
    private val pauseRequested = ConcurrentHashMap.newKeySet<String>()
    private val removedIds = ConcurrentHashMap.newKeySet<String>()

    fun restore(saved: List<DownloadTask>) {
        synchronized(lock) {
            tasks.clear()
            saved.filter { it.kind == DownloadKind.YOUTUBE }.forEach { old ->
                val restoredState = when (old.state) {
                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> DownloadState.PAUSED
                    else -> old.state
                }
                tasks[old.id] = old.copy(
                    state = restoredState,
                    speedBytesPerSec = 0u,
                    activeConnections = 0u
                )
            }
        }
        publish()
    }

    fun add(
        url: String,
        requestedName: String?,
        formatSelector: String? = null,
        subtitleLanguage: String? = null
    ) {
        val id = UUID.randomUUID().toString()
        optionsPrefs.edit()
            .putString("$id.format", formatSelector?.takeIf { it.isNotBlank() } ?: DEFAULT_FORMAT)
            .putString("$id.subtitle", subtitleLanguage?.takeIf { it.isNotBlank() })
            .apply()
        val pendingPath = File(downloadDir, ".yt-dlp-$id.pending").absolutePath
        val task = DownloadTask(
            id = id,
            title = requestedName?.takeIf { it.isNotBlank() }?.let(::sanitizeBaseName) ?: "正在解析 YouTube…",
            sourceUrl = url,
            savePath = pendingPath,
            kind = DownloadKind.YOUTUBE,
            state = DownloadState.QUEUED,
            totalBytes = 0u,
            downloadedBytes = 0u,
            speedBytesPerSec = 0u,
            uploadSpeedBytesPerSec = 0u,
            activeConnections = 0u,
            errorMessage = null
        )
        synchronized(lock) { tasks[id] = task }
        publish()
        startQueuedTasks()
    }

    fun pause(id: String) {
        if (!contains(id)) return
        pauseRequested.add(id)
        processIds[id]?.let { processId ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
        val current = get(id)
        val bytes = current?.takeIf { !it.savePath.endsWith(".pending") }?.let {
            observedBytes(File(it.savePath).parentFile ?: downloadDir, File(it.savePath).nameWithoutExtension)
        } ?: 0L
        update(id) {
            it.copy(
                state = DownloadState.PAUSED,
                downloadedBytes = maxOf(it.downloadedBytes, bytes.toULong()),
                speedBytesPerSec = 0u
            )
        }
    }

    fun resume(id: String) {
        val task = get(id) ?: return
        if (task.state == DownloadState.COMPLETED || task.state == DownloadState.DOWNLOADING) return
        pauseRequested.remove(id)
        update(id) { it.copy(state = DownloadState.QUEUED, errorMessage = null) }
        val previous = jobs[id]
        if (previous?.isActive == true) {
            scope.launch {
                previous.join()
                if (contains(id)) startQueuedTasks()
            }
        } else {
            startQueuedTasks()
        }
    }

    fun remove(id: String, deleteFiles: Boolean) {
        removedIds.add(id)
        pauseRequested.add(id)
        processIds.remove(id)?.let { processId ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
        jobs.remove(id)?.cancel()
        val removed = synchronized(lock) { tasks.remove(id) }
        publish()
        if (deleteFiles && removed != null) {
            val savedFile = File(removed.savePath)
            val generatedFiles = if (removed.savePath.endsWith(".pending")) {
                listOf(savedFile)
            } else {
                val basePrefix = "${savedFile.nameWithoutExtension}."
                savedFile.parentFile?.listFiles()?.filter { it.name.startsWith(basePrefix) }.orEmpty()
            }
            generatedFiles.forEach { runCatching { it.delete() } }
        }
        optionsPrefs.edit().remove("$id.format").remove("$id.subtitle").apply()
    }

    fun hasActiveDownloads(): Boolean = synchronized(lock) {
        tasks.values.any { it.state == DownloadState.DOWNLOADING }
    }

    fun shutdown() {
        val active = tasksSnapshot().filter { it.state == DownloadState.DOWNLOADING }
        active.forEach { task ->
            pauseRequested.add(task.id)
            processIds.remove(task.id)?.let { processId ->
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
        }
        active.forEach { task ->
            val file = File(task.savePath)
            val bytes = if (task.savePath.endsWith(".pending")) 0L else {
                observedBytes(file.parentFile ?: downloadDir, file.nameWithoutExtension)
            }
            update(task.id) {
                it.copy(
                    state = DownloadState.PAUSED,
                    downloadedBytes = maxOf(it.downloadedBytes, bytes.toULong()),
                    speedBytesPerSec = 0u
                )
            }
        }
        scope.cancel()
    }

    private fun start(id: String, requestedName: String?) {
        if (!contains(id)) return
        val oldJob = jobs[id]
        if (oldJob?.isActive == true) return
        removedIds.remove(id)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                YoutubeDL.getInstance().init(appContext)
                YoutubeRuntime.init(appContext)
                val source = get(id)?.sourceUrl ?: return@launch
                val formatSelector = optionsPrefs.getString("$id.format", null) ?: DEFAULT_FORMAT
                val subtitleLanguage = optionsPrefs.getString("$id.subtitle", null)
                val infoRequest = YoutubeDLRequest(source).apply {
                    addOption("--no-playlist")
                    addOption("-f", formatSelector)
                }
                val info = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().getInfo(infoRequest)
                }
                if (!contains(id) || id in removedIds) return@launch
                if (id in pauseRequested) {
                    update(id) { it.copy(state = DownloadState.PAUSED, speedBytesPerSec = 0u) }
                    return@launch
                }

                val ext = info.ext?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                    ?.lowercase() ?: "mp4"
                val current = get(id) ?: return@launch
                val isResuming = !current.savePath.endsWith(".pending")
                val base = if (isResuming) {
                    File(current.savePath).nameWithoutExtension
                } else {
                    requestedName?.takeIf { it.isNotBlank() }
                        ?: info.title?.takeIf { it.isNotBlank() }
                        ?: "youtube_video"
                }.let(::sanitizeBaseName)
                val outputBase = if (isResuming) {
                    File(current.savePath).parentFile ?: downloadDir
                } else {
                    downloadDir
                }
                val resolvedBase = if (isResuming) base else uniqueBaseName(outputBase, base, id)
                val savePath = File(outputBase, "$resolvedBase.$ext")
                val title = savePath.name
                val totalSize = max(info.fileSize, info.fileSizeApproximate).coerceAtLeast(0L).toULong()
                update(id) {
                    it.copy(
                        title = title,
                        savePath = savePath.absolutePath,
                        totalBytes = totalSize,
                        errorMessage = null,
                        state = DownloadState.DOWNLOADING
                    )
                }

                val request = YoutubeDLRequest(source).apply {
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--continue")
                    addOption("-f", formatSelector)
                    if (!subtitleLanguage.isNullOrBlank()) {
                        addOption("--write-subs")
                        addOption("--sub-langs", subtitleLanguage)
                        addOption("--sub-format", "vtt/best")
                    }
                    addOption("-o", File(outputBase, "${resolvedBase.replace("%", "%%")}.%(ext)s").absolutePath)
                }
                val processId = "mdm-ytdlp-${id}-${System.nanoTime()}"
                processIds[id] = processId
                var lastBytes = 0L
                var lastAt = System.nanoTime()
                YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
                    if (contains(id) && id !in removedIds && id !in pauseRequested) {
                        val partBytes = observedBytes(outputBase, resolvedBase)
                        val progressBytes = if (totalSize > 0uL) {
                            (totalSize.toDouble() * progress.coerceIn(0f, 100f) / 100.0).toLong()
                        } else partBytes
                        val downloaded = maxOf(partBytes, progressBytes).coerceAtLeast(0L)
                        val now = System.nanoTime()
                        val elapsed = (now - lastAt).coerceAtLeast(1L)
                        val speed = ((downloaded - lastBytes).coerceAtLeast(0L) * 1_000_000_000L / elapsed)
                            .coerceAtLeast(0L)
                        lastBytes = downloaded
                        lastAt = now
                        update(id) {
                            it.copy(
                                downloadedBytes = downloaded.toULong(),
                                speedBytesPerSec = speed.toULong(),
                                totalBytes = if (totalSize > 0uL) maxOf(it.totalBytes, totalSize) else it.totalBytes
                            )
                        }
                    }
                    Unit
                }
                if (!contains(id) || id in removedIds) return@launch
                val finalFile = findFinalOutput(outputBase, resolvedBase)
                val downloaded = finalFile?.length() ?: observedBytes(outputBase, resolvedBase)
                if (id in pauseRequested) {
                    update(id) {
                        it.copy(
                            state = DownloadState.PAUSED,
                            downloadedBytes = downloaded.toULong(),
                            speedBytesPerSec = 0u
                        )
                    }
                } else if (finalFile != null) {
                    update(id) {
                        it.copy(
                            state = DownloadState.COMPLETED,
                            title = finalFile.name,
                            savePath = finalFile.absolutePath,
                            downloadedBytes = finalFile.length().toULong(),
                            totalBytes = maxOf(it.totalBytes, finalFile.length().toULong()),
                            speedBytesPerSec = 0u,
                            errorMessage = null
                        )
                    }
                } else {
                    update(id) {
                        it.copy(
                            state = DownloadState.FAILED,
                            downloadedBytes = downloaded.toULong(),
                            speedBytesPerSec = 0u,
                            errorMessage = "yt-dlp 未生成预期的视频文件"
                        )
                    }
                }
            } catch (error: Exception) {
                if (!contains(id) || id in removedIds) return@launch
                if (id in pauseRequested) {
                    val task = get(id)
                    val currentPath = task?.savePath?.let(::File)
                    val downloaded = currentPath?.takeIf { !it.absolutePath.endsWith(".pending") }
                        ?.let { observedBytes(it.parentFile ?: downloadDir, it.nameWithoutExtension) } ?: 0L
                    update(id) {
                        it.copy(
                            state = DownloadState.PAUSED,
                            downloadedBytes = maxOf(it.downloadedBytes, downloaded.toULong()),
                            speedBytesPerSec = 0u,
                            errorMessage = null
                        )
                    }
                } else {
                    val message = error.message?.take(240)?.ifBlank { null } ?: "yt-dlp 下载失败"
                    update(id) {
                        it.copy(
                            state = DownloadState.FAILED,
                            speedBytesPerSec = 0u,
                            errorMessage = message
                        )
                    }
                }
            } finally {
                processIds.entries.removeIf { it.key == id }
                jobs.remove(id)
                startQueuedTasks()
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun sanitizeBaseName(value: String): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .filterNot { Character.isISOControl(it) }
            .trim()
            .trimEnd('.')
            .take(160)
            .trim()
        val suffix = cleaned.substringAfterLast('.', "").lowercase()
        val withoutExtension = if (suffix in VIDEO_EXTENSIONS) {
            cleaned.substringBeforeLast('.').trim()
        } else {
            cleaned
        }
        return withoutExtension.ifBlank { "youtube_video" }
    }

    private fun uniqueBaseName(directory: File, requested: String, id: String): String {
        val reserved = synchronized(lock) {
            tasks.values.asSequence()
                .filter { it.id != id }
                .mapNotNull {
                    if (it.savePath.endsWith(".pending")) {
                        it.title.takeUnless { title -> title == "正在解析 YouTube…" }
                            ?.let(::sanitizeBaseName)
                    } else {
                        File(it.savePath).nameWithoutExtension
                    }
                }
                .toSet()
        }
        fun conflicts(base: String): Boolean = base in reserved ||
            directory.listFiles()?.any { it.name.startsWith("$base.") } == true
        if (!conflicts(requested)) return requested
        val suffix = id.replace("-", "").take(6)
        var candidate = "${requested}_$suffix"
        var attempt = 2
        while (conflicts(candidate)) {
            candidate = "${requested}_${suffix}_$attempt"
            attempt++
        }
        return candidate
    }

    private fun observedBytes(directory: File, baseName: String): Long =
        (findFinalOutput(directory, baseName)?.length() ?: 0L).coerceAtLeast(
            directory.listFiles()
                ?.filter { it.name.startsWith("$baseName.") && it.name.endsWith(".part") }
                ?.maxOfOrNull { it.length() } ?: 0L
            )

    private fun startQueuedTasks() {
        while (true) {
            val next = synchronized(lock) {
                if (tasks.values.count { it.state == DownloadState.DOWNLOADING } >= MAX_ACTIVE_DOWNLOADS) {
                    return@synchronized null
                }
                val queued = tasks.values.firstOrNull { it.state == DownloadState.QUEUED }
                    ?: return@synchronized null
                tasks[queued.id] = queued.copy(state = DownloadState.DOWNLOADING)
                queued
            } ?: return
            publish()
            start(next.id, next.requestedOutputName())
        }
    }

    private fun findFinalOutput(directory: File, baseName: String): File? =
        directory.listFiles()
            ?.filter {
                it.isFile && it.name.startsWith("$baseName.") &&
                    !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                    !it.name.endsWith(".info.json") &&
                    it.extension.lowercase() in VIDEO_EXTENSIONS
            }
            ?.maxByOrNull { it.lastModified() }

    private fun get(id: String): DownloadTask? = synchronized(lock) { tasks[id] }

    private fun DownloadTask.requestedOutputName(): String? =
        if (savePath.endsWith(".pending") && title == "正在解析 YouTube…") null else title

    private fun contains(id: String): Boolean = synchronized(lock) { tasks.containsKey(id) }

    private fun tasksSnapshot(): List<DownloadTask> = synchronized(lock) { tasks.values.toList() }

    private fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        val snapshot = synchronized(lock) {
            val current = tasks[id] ?: return
            tasks[id] = transform(current)
            tasks.values.toList()
        }
        onTasksChanged(snapshot)
    }

    private fun publish() = onTasksChanged(tasksSnapshot())

    companion object {
        private const val DEFAULT_FORMAT = "best[ext=mp4]/best"
        private const val MAX_ACTIVE_DOWNLOADS = 2
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv")
    }
}

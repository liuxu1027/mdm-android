package com.mdm.android.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    @Volatile
    private var maxActiveDownloads = 2

    fun setMaxActiveDownloads(value: Int) {
        maxActiveDownloads = value.coerceIn(0, 8)
        startQueuedTasks()
    }

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
        subtitleLanguage: String? = null,
        audioOnly: Boolean = false
    ): String {
        val id = UUID.randomUUID().toString()
        val cleanRequestedName = requestedName?.takeIf { it.isNotBlank() }?.let(::sanitizeBaseName)
        val prefix = cleanRequestedName?.let { uniqueBaseName(downloadDir, it, id) }
            ?: "mdm-${id.replace("-", "").take(12)}-"
        optionsPrefs.edit()
            .putString("$id.format", formatSelector?.takeIf { it.isNotBlank() } ?: DEFAULT_FORMAT)
            .putString("$id.subtitle", subtitleLanguage?.takeIf { it.isNotBlank() })
            .putBoolean("$id.audio_only", audioOnly)
            .putString("$id.name", cleanRequestedName)
            .putString("$id.prefix", prefix)
            .apply()
        val pendingPath = File(downloadDir, "$prefix.pending").absolutePath
        val task = DownloadTask(
            id = id,
            title = cleanRequestedName ?: "YouTube 下载 · ${id.take(6)}",
            sourceUrl = url,
            savePath = pendingPath,
            kind = DownloadKind.YOUTUBE,
            state = DownloadState.QUEUED,
            totalBytes = 0u,
            downloadedBytes = 0u,
            speedBytesPerSec = 0u,
            uploadSpeedBytesPerSec = 0u,
            activeConnections = 0u,
            completedSegments = 0u,
            totalSegments = 0u,
            selectedFiles = null,
            errorMessage = null
        )
        synchronized(lock) { tasks[id] = task }
        publish()
        startQueuedTasks()
        return id
    }

    fun redownload(id: String) {
        val old = get(id) ?: return
        val name = optionsPrefs.getString("$id.name", null)
            ?: File(old.savePath).takeIf { !old.savePath.endsWith(".pending") }?.nameWithoutExtension
        val format = optionsPrefs.getString("$id.format", DEFAULT_FORMAT)
        val subtitle = optionsPrefs.getString("$id.subtitle", null)
        val audioOnly = optionsPrefs.getBoolean("$id.audio_only", false)
        add(old.sourceUrl, name, format, subtitle, audioOnly)
    }

    fun rename(id: String, requestedName: String): Result<Unit> = runCatching {
        val task = get(id) ?: error("找不到下载任务")
        require(task.state == DownloadState.COMPLETED) { "只能重命名已完成的文件" }
        val source = File(task.savePath)
        require(source.isFile) { "找不到下载文件，无法重命名" }
        val base = sanitizeBaseName(requestedName)
        val extension = source.extension.takeIf { it.isNotBlank() }
        val destination = File(source.parentFile ?: downloadDir, buildString {
            append(base)
            if (extension != null) append(".$extension")
        })
        require(source.absolutePath == destination.absolutePath || !destination.exists()) { "同名文件已存在，请换一个名称" }
        if (source.absolutePath != destination.absolutePath) {
            check(source.renameTo(destination)) { "重命名失败" }
        }
        val prefix = destination.nameWithoutExtension
        optionsPrefs.edit().putString("$id.name", prefix).putString("$id.prefix", prefix).apply()
        update(id) {
            it.copy(title = destination.name, savePath = destination.absolutePath)
        }
    }

    fun updateLocation(id: String, path: String, title: String) {
        optionsPrefs.edit().putString("$id.name", sanitizeBaseName(title)).apply()
        update(id) { it.copy(title = title, savePath = path) }
    }

    fun pause(id: String) {
        if (!contains(id)) return
        pauseRequested.add(id)
        processIds[id]?.let { processId ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
        val current = get(id)
        val bytes = current?.let { observedBytes(downloadDir, it.id, outputPrefix(it.id, it.savePath)) } ?: 0L
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
            val prefix = optionsPrefs.getString("$id.prefix", null)
                ?: File(removed.savePath).nameWithoutExtension
            downloadDir.listFiles()
                ?.filter { taskFileMatches(id, it.name, prefix) }
                ?.forEach { runCatching { it.delete() } }
        }
        optionsPrefs.edit()
            .remove("$id.format")
            .remove("$id.subtitle")
            .remove("$id.audio_only")
            .remove("$id.name")
            .remove("$id.prefix")
            .apply()
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
            val prefix = outputPrefix(task.id, task.savePath)
            val bytes = observedBytes(downloadDir, task.id, prefix)
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

    private fun start(id: String) {
        if (!contains(id)) return
        val oldJob = jobs[id]
        if (oldJob?.isActive == true) return
        removedIds.remove(id)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var temporaryCookieFile: File? = null
            try {
                YoutubeDL.getInstance().init(appContext)
                YoutubeRuntime.init(appContext)
                val current = get(id) ?: return@launch
                val source = current.sourceUrl
                val formatSelector = optionsPrefs.getString("$id.format", null) ?: DEFAULT_FORMAT
                val subtitleLanguage = optionsPrefs.getString("$id.subtitle", null)
                val audioOnly = optionsPrefs.getBoolean("$id.audio_only", false)
                val requestedName = optionsPrefs.getString("$id.name", null)?.takeIf { it.isNotBlank() }
                val prefix = optionsPrefs.getString("$id.prefix", null)
                    ?: File(current.savePath).nameWithoutExtension
                val outputBase = File(current.savePath).parentFile ?: downloadDir
                outputBase.mkdirs()
                if (!contains(id) || id in removedIds) return@launch
                if (id in pauseRequested) {
                    update(id) { it.copy(state = DownloadState.PAUSED, speedBytesPerSec = 0u) }
                    return@launch
                }

                val initialBytes = observedBytes(outputBase, id, prefix)
                update(id) {
                    it.copy(
                        title = requestedName ?: it.title,
                        totalBytes = 0u,
                        downloadedBytes = initialBytes.toULong(),
                        errorMessage = null,
                        state = DownloadState.DOWNLOADING
                    )
                }

                val outputTemplate = if (requestedName != null) {
                    File(outputBase, "${prefix.replace("%", "%%")}.%(ext)s").absolutePath
                } else {
                    File(outputBase, "${prefix.replace("%", "%%")}%(title).160B.%(ext)s").absolutePath
                }
                temporaryCookieFile = YoutubeCookieStore.createTemporaryCookieFile(appContext)
                val request = YoutubeDLRequest(source).apply {
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--continue")
                    addOption("--newline")
                    addOption("-f", formatSelector)
                    if (audioOnly) {
                        addOption("-x")
                        addOption("--audio-format", "m4a")
                    }
                    addOption("-o", outputTemplate)
                    temporaryCookieFile?.let { addOption("--cookies", it.absolutePath) }
                }
                val processId = "mdm-ytdlp-${id}-${System.nanoTime()}"
                processIds[id] = processId
                var lastBytes = initialBytes
                var lastAt = System.nanoTime()
                YoutubeDL.getInstance().execute(request, processId) { _, _, _ ->
                    if (contains(id) && id !in removedIds && id !in pauseRequested) {
                        // yt-dlp's percentage/file-size estimate may describe a single stream,
                        // not the merged output. Count the actual files instead of inventing bytes.
                        val actualBytes = observedBytes(outputBase, id, prefix)
                        val now = System.nanoTime()
                        val elapsed = (now - lastAt).coerceAtLeast(1L)
                        val speed = ((actualBytes - lastBytes).coerceAtLeast(0L) * 1_000_000_000L / elapsed)
                            .coerceAtLeast(0L)
                        lastBytes = actualBytes
                        lastAt = now
                        update(id) {
                            it.copy(
                                downloadedBytes = actualBytes.toULong(),
                                speedBytesPerSec = speed.toULong(),
                                totalBytes = 0u
                            )
                        }
                    }
                    Unit
                }
                if (!contains(id) || id in removedIds) return@launch
                val finalFile = findFinalOutput(outputBase, id, prefix)
                if (id in pauseRequested) {
                    val downloaded = observedBytes(outputBase, id, prefix)
                    update(id) {
                        it.copy(
                            state = DownloadState.PAUSED,
                            downloadedBytes = downloaded.toULong(),
                            speedBytesPerSec = 0u
                        )
                    }
                } else if (finalFile != null) {
                    val displayFile = if (requestedName == null) {
                        cleanGeneratedName(finalFile, prefix, id)
                    } else finalFile
                    val exactSize = displayFile.length().toULong()
                    update(id) {
                        it.copy(
                            state = DownloadState.COMPLETED,
                            title = displayFile.name,
                            savePath = displayFile.absolutePath,
                            downloadedBytes = exactSize,
                            totalBytes = exactSize,
                            speedBytesPerSec = 0u,
                            errorMessage = null
                        )
                    }
                    if (!subtitleLanguage.isNullOrBlank()) {
                        val subtitleTemplate = File(
                            displayFile.parentFile ?: outputBase,
                            "${displayFile.nameWithoutExtension}.%(ext)s"
                        ).absolutePath
                        scope.launch { downloadSubtitle(source, subtitleLanguage, subtitleTemplate) }
                    }
                } else {
                    val downloaded = observedBytes(outputBase, id, prefix)
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
                    val prefix = task?.let { outputPrefix(it.id, it.savePath) }.orEmpty()
                    val downloaded = observedBytes(downloadDir, id, prefix)
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
                    val task = get(id)
                    val prefix = task?.let { outputPrefix(it.id, it.savePath) }.orEmpty()
                    val downloaded = observedBytes(downloadDir, id, prefix)
                    update(id) {
                        it.copy(
                            state = DownloadState.FAILED,
                            downloadedBytes = maxOf(it.downloadedBytes, downloaded.toULong()),
                            speedBytesPerSec = 0u,
                            errorMessage = message
                        )
                    }
                }
            } finally {
                YoutubeCookieStore.deleteTemporaryCookieFile(temporaryCookieFile)
                processIds.entries.removeIf { it.key == id }
                jobs.remove(id)
                startQueuedTasks()
            }
        }
        jobs[id] = job
        job.start()
    }

    private suspend fun downloadSubtitle(source: String, language: String, outputTemplate: String) {
        runCatching {
            val cookieFile = YoutubeCookieStore.createTemporaryCookieFile(appContext)
            try {
                val request = YoutubeDLRequest(source).apply {
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--ignore-errors")
                    addOption("--skip-download")
                    addOption("--write-subs")
                    addOption("--write-auto-subs")
                    addOption("--sub-langs", language)
                    addOption("--sub-format", "vtt/best")
                    addOption("-o", outputTemplate)
                    cookieFile?.let { addOption("--cookies", it.absolutePath) }
                }
                YoutubeDL.getInstance().execute(request)
            } finally {
                YoutubeCookieStore.deleteTemporaryCookieFile(cookieFile)
            }
        }
    }

    private fun cleanGeneratedName(file: File, prefix: String, id: String): File {
        val guessed = sanitizeBaseName(file.name.removePrefix(prefix).substringBeforeLast('.'))
        var destination = File(file.parentFile ?: downloadDir, "$guessed.${file.extension}")
        if (destination.absolutePath != file.absolutePath && destination.exists()) {
            val suffix = id.replace("-", "").take(6)
            destination = File(file.parentFile ?: downloadDir, "${guessed}_$suffix.${file.extension}")
            var attempt = 2
            while (destination.exists()) {
                destination = File(file.parentFile ?: downloadDir, "${guessed}_${suffix}_$attempt.${file.extension}")
                attempt++
            }
        }
        if (destination.absolutePath != file.absolutePath && file.renameTo(destination)) return destination
        return file
    }

    private fun outputPrefix(id: String, savePath: String): String =
        optionsPrefs.getString("$id.prefix", null) ?: File(savePath).nameWithoutExtension

    private fun sanitizeBaseName(value: String): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .filterNot { Character.isISOControl(it) }
            .trim()
            .trimEnd('.')
            .take(160)
            .trim()
        val suffix = cleaned.substringAfterLast('.', "").lowercase()
        val withoutExtension = if (suffix in MEDIA_EXTENSIONS) cleaned.substringBeforeLast('.').trim() else cleaned
        return withoutExtension.ifBlank { "youtube_video" }
    }

    private fun uniqueBaseName(directory: File, requested: String, id: String): String {
        val reserved = synchronized(lock) {
            tasks.values.asSequence()
                .filter { it.id != id }
                .mapNotNull { task ->
                    optionsPrefs.getString("${task.id}.prefix", null)
                        ?: task.savePath.takeUnless { it.endsWith(".pending") }?.let { File(it).nameWithoutExtension }
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

    private fun taskFileMatches(id: String, name: String, prefix: String): Boolean {
        val hasCustomName = optionsPrefs.getString("$id.name", null) != null
        return if (hasCustomName) name == "$prefix.pending" || name.startsWith("$prefix.")
        else name.startsWith(prefix)
    }

    private fun observedBytes(directory: File, id: String, prefix: String): Long =
        directory.listFiles()
            ?.filter { file ->
                file.isFile && taskFileMatches(id, file.name, prefix) &&
                    (file.name.endsWith(".part") || file.extension.lowercase() in MEDIA_EXTENSIONS)
            }
            ?.sumOf { it.length() }
            ?.coerceAtLeast(0L) ?: 0L

    private fun startQueuedTasks() {
        while (true) {
            val next = synchronized(lock) {
                if (tasks.values.count { it.state == DownloadState.DOWNLOADING } >= maxActiveDownloads) {
                    return@synchronized null
                }
                val queued = tasks.values.firstOrNull { it.state == DownloadState.QUEUED }
                    ?: return@synchronized null
                tasks[queued.id] = queued.copy(state = DownloadState.DOWNLOADING)
                queued
            } ?: return
            publish()
            start(next.id)
        }
    }

    private fun findFinalOutput(directory: File, id: String, prefix: String): File? =
        directory.listFiles()
            ?.filter { file ->
                file.isFile && taskFileMatches(id, file.name, prefix) &&
                    !file.name.endsWith(".part") && !file.name.endsWith(".ytdl") &&
                    !file.name.endsWith(".info.json") && file.extension.lowercase() in MEDIA_EXTENSIONS
            }
            ?.maxByOrNull { it.lastModified() }

    private fun get(id: String): DownloadTask? = synchronized(lock) { tasks[id] }

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
        private val MEDIA_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv",
            "m4a", "mp3", "aac", "flac", "wav", "ogg", "opus"
        )
    }
}

package com.mdm.android.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mdm.android.service.DownloadForegroundService
import com.mdm.android.service.DownloadTaskTiming
import com.mdm.android.service.YoutubeRuntime
import com.mdm.android.service.YoutubeMediaResolver
import com.mdm.android.service.YoutubeCookieStore
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask
import uniffi.mdm_android.MagnetFileInfo
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val tasks by viewModel.tasks.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var selectedTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingBulkRemoveIds by remember { mutableStateOf<Set<String>?>(null) }
    var fileOpsTaskId by remember { mutableStateOf<String?>(null) }
    var propertiesTaskId by remember { mutableStateOf<String?>(null) }
    var retryOptionsTaskId by remember { mutableStateOf<String?>(null) }
    var pendingFileOperation by remember { mutableStateOf<String?>(null) }
    var renamingTaskId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var updateLinkTaskId by remember { mutableStateOf<String?>(null) }
    var updateLinkValue by remember { mutableStateOf("") }
    var updateLinkError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val youtubeResolveState = viewModel.youtubeResolveState
    val batchMessage = viewModel.batchMessage
    LaunchedEffect(batchMessage) {
        batchMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeBatchMessage()
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getStringExtra(DownloadForegroundService.EXTRA_YTDLP_STATUS)
                    ?.let(viewModel::onYtDlpUpdateStatus)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadForegroundService.ACTION_YTDLP_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val hasActiveDownload = tasks.any { it.state == DownloadState.DOWNLOADING }
    val torrentFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addTorrentFile(uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val operation = pendingFileOperation
        val task = tasks.firstOrNull { it.id == fileOpsTaskId }
        if (treeUri != null && operation != null && task != null) {
            val permissionGranted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.isSuccess
            if (!permissionGranted) {
                Toast.makeText(context, "所选文件提供方不支持保留访问权限，已取消文件操作", Toast.LENGTH_LONG).show()
                pendingFileOperation = null
                fileOpsTaskId = null
            } else {
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { copyTaskFileToTree(context, task, treeUri, operation == "move") }
                    }
                    result.onSuccess { (destinationUri, fileName) ->
                        if (operation == "move") {
                            viewModel.updateTaskLocation(task.id, destinationUri.toString(), fileName)
                        }
                        Toast.makeText(
                            context,
                            if (operation == "move") "已移动：$fileName" else "已复制：$fileName",
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { error ->
                        Toast.makeText(context, "文件操作失败：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                    }
                    pendingFileOperation = null
                    fileOpsTaskId = null
                }
            }
        } else {
            pendingFileOperation = null
            fileOpsTaskId = null
        }
    }

    // 打开“所有文件访问权限”的系统设置页（特殊应用权限，不在应用详情的常规权限里）。
    val openAllFilesAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent =
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            context.startActivity(intent)
        }
    }

    // 没开“所有文件访问权限”时提示一次：不开的话下载只能存在应用私有目录，文件管理器看不到。
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)
        if (!DownloadForegroundService.hasAllFilesAccess(context) &&
            !prefs.getBoolean("storage_prompt_dismissed", false)
        ) {
            showStorageDialog = true
        }
    }
    val doExit = {
        viewModel.exit()
        (context as? Activity)?.finish()
    }

    val filteredTasks = remember(tasks, selectedTab) {
        when (selectedTab) {
            1 -> tasks.filter {
                it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
            }
            2 -> tasks.filter { it.kind == DownloadKind.HLS || it.kind == DownloadKind.YOUTUBE }
            3 -> tasks.filter { it.kind != DownloadKind.HLS && it.kind != DownloadKind.YOUTUBE }
            else -> tasks
        }
    }
    LaunchedEffect(tasks) {
        val existingIds = tasks.map { it.id }.toSet()
        selectedTaskIds = selectedTaskIds.intersect(existingIds)
    }
    val selectedTasks = remember(tasks, selectedTaskIds) { tasks.filter { it.id in selectedTaskIds } }
    val allVisibleSelected = filteredTasks.isNotEmpty() && filteredTasks.all { it.id in selectedTaskIds }
    val hasFinished = tasks.any {
        it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTaskIds.isEmpty()) "MDM" else "已选 ${selectedTaskIds.size} 项") },
                actions = {
                    if (selectedTaskIds.isNotEmpty()) {
                        TextButton(onClick = {
                            selectedTaskIds = if (allVisibleSelected) emptySet() else filteredTasks.map { it.id }.toSet()
                        }) { Text(if (allVisibleSelected) "取消全选" else "全选") }
                        if (selectedTasks.any { it.state == DownloadState.DOWNLOADING }) {
                            IconButton(onClick = {
                                selectedTasks.filter { it.state == DownloadState.DOWNLOADING }
                                    .forEach { viewModel.pause(it.id) }
                            }) { Icon(Icons.Default.Pause, contentDescription = "暂停所选") }
                        }
                        if (selectedTasks.any {
                                it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED ||
                                    it.state == DownloadState.QUEUED
                            }) {
                            IconButton(onClick = {
                                selectedTasks.filter {
                                    it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED ||
                                        it.state == DownloadState.QUEUED
                                }.forEach { viewModel.resume(it.id) }
                            }) { Icon(Icons.Default.PlayArrow, contentDescription = "继续所选") }
                        }
                        IconButton(onClick = {
                            pendingBulkRemoveIds = selectedTasks.map { it.id }.toSet()
                            selectedTaskIds = emptySet()
                        }) { Icon(Icons.Default.Delete, contentDescription = "移除所选") }
                        IconButton(onClick = { selectedTaskIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    } else {
                        if (hasFinished) {
                            IconButton(onClick = { viewModel.clearFinished() }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除已完成")
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        IconButton(onClick = {
                            if (hasActiveDownload) showExitConfirm = true else doExit()
                        }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "退出")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加下载")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; selectedTaskIds = emptySet() }, text = { Text("全部") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; selectedTaskIds = emptySet() }, text = { Text("下载中") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2; selectedTaskIds = emptySet() }, text = { Text("视频") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3; selectedTaskIds = emptySet() }, text = { Text("其他") })
            }

            if (filteredTasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("点击右下角 + 添加 HTTP、磁力或 m3u8 视频链接", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 单列全宽窄行，底部为悬浮添加按钮留出空间。
                    contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(filteredTasks, key = { _, task -> task.id }) { index, task ->
                        DownloadTaskCard(
                            task = task,
                            position = index + 1,
                            taskCount = filteredTasks.size,
                            selected = task.id in selectedTaskIds,
                            selectionMode = selectedTaskIds.isNotEmpty(),
                            onClick = {
                                if (selectedTaskIds.isNotEmpty()) {
                                    selectedTaskIds = if (task.id in selectedTaskIds) selectedTaskIds - task.id
                                    else selectedTaskIds + task.id
                                } else selectedTaskId = task.id
                            },
                            onLongClick = { selectedTaskIds = selectedTaskIds + task.id },
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task.id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddLinkDialog(
            youtubeEnabled = YoutubeRuntime.youtubeEnabled,
            onDismiss = { showAddDialog = false },
            onPickTorrentFile = {
                showAddDialog = false
                torrentFilePicker.launch(arrayOf("application/x-bittorrent"))
            },
            onConfirm = { link, name, height, audio ->
                val lines = link.lines().map { it.trim() }.filter { it.isNotEmpty() }
                when {
                    lines.size == 1 && isYouTubePageLink(lines[0]) -> {
                        viewModel.addLink(link, name, height, audio)
                    }
                    lines.size == 1 && isPpnixSeriesPage(lines[0]) -> {
                        Toast.makeText(context, "正在读取剧集信息…", Toast.LENGTH_SHORT).show()
                        viewModel.resolveSeriesPage(lines[0])
                    }
                    else -> {
                        val result = viewModel.addLinks(link, name)
                        val skipped = result.skippedYoutube + result.skippedSeries
                        if (result.added > 0 || skipped > 0) {
                            val message = buildString {
                                append("已添加 ${result.added} 个任务")
                                if (skipped > 0) append("，跳过 $skipped 条（YouTube 链接和剧集页请逐条添加）")
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                showAddDialog = false
            }
        )
    }

    when (val state = youtubeResolveState) {
        YoutubeResolveUiState.Idle -> Unit
        YoutubeResolveUiState.Loading -> AlertDialog(
            onDismissRequest = { viewModel.dismissYoutubePicker() },
            title = { Text("正在解析 YouTube") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("正在读取视频/播放列表信息，请稍候…")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissYoutubePicker() }) { Text("取消") }
            }
        )
        is YoutubeResolveUiState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.dismissYoutubePicker() },
            title = { Text("解析失败") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissYoutubePicker() }) { Text("关闭") }
            }
        )
        is YoutubeResolveUiState.Ready -> YoutubePickerDialog(
            result = state.result,
            ffmpegEnabled = YoutubeRuntime.ffmpegEnabled,
            initialTargetHeight = state.initialTargetHeight,
            initialAudioCeiling = state.initialAudioCeiling,
            onDismiss = { viewModel.dismissYoutubePicker() },
            onDownload = { viewModel.addYoutubeDownloads(it) }
        )
    }

    // 磁力文件选择：单文件不弹框（直接下），多文件才弹列表让用户勾选。
    when (val picker = viewModel.magnetPickerState) {
        null -> Unit
        MagnetPickerUiState.Loading -> AlertDialog(
            onDismissRequest = { viewModel.dismissMagnetPicker() },
            title = { Text("正在获取文件列表") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("正在读取种子文件列表，请稍候…")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissMagnetPicker() }) { Text("取消") }
            }
        )
        is MagnetPickerUiState.Ready -> MagnetFilePickerDialog(
            title = picker.metadata.name,
            files = picker.metadata.files,
            onDismiss = { viewModel.dismissMagnetPicker() },
            onConfirm = {
                if (picker.isTorrentFile) viewModel.confirmTorrentDownload(picker.source, it)
                else viewModel.confirmMagnetDownload(picker.source, it)
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            youtubeEnabled = YoutubeRuntime.youtubeEnabled,
            themeStyle = viewModel.themeStyle,
            maxConnections = viewModel.maxConnections,
            speedLimitBps = viewModel.speedLimitBps,
            uploadLimitBps = viewModel.uploadLimitBps,
            retryEnabled = viewModel.retryEnabled,
            retryMaxAttempts = viewModel.retryMaxAttempts,
            retryDelaySecs = viewModel.retryDelaySecs,
            retryAlways = viewModel.retryAlways,
            removeAds = viewModel.removeAds,
            hlsParallelSegments = viewModel.hlsParallelSegments,
            maxConcurrentTasks = viewModel.maxConcurrentTasks,
            ytDlpUpdateStatus = viewModel.ytDlpUpdateStatus,
            checkingYtDlpUpdate = viewModel.checkingYtDlpUpdate,
            downloadDir = viewModel.downloadDir,
            downloadLocation = viewModel.downloadLocation,
            hasAllFilesAccess = DownloadForegroundService.hasAllFilesAccess(context),
            onThemeStyleChange = { viewModel.updateThemeStyle(it) },
            onConnectionsChange = { viewModel.updateMaxConnections(it) },
            onSpeedLimitChange = { viewModel.updateSpeedLimitBps(it) },
            onUploadLimitChange = { viewModel.updateUploadLimitBps(it) },
            onRetryEnabledChange = { viewModel.updateRetryEnabled(it) },
            onRetryMaxAttemptsChange = { viewModel.updateRetryMaxAttempts(it) },
            onRetryDelaySecsChange = { viewModel.updateRetryDelaySecs(it) },
            onRetryAlwaysChange = { viewModel.updateRetryAlways(it) },
            onRemoveAdsChange = { viewModel.updateRemoveAds(it) },
            onHlsParallelSegmentsChange = { viewModel.updateHlsParallelSegments(it) },
            onMaxConcurrentTasksChange = { viewModel.updateMaxConcurrentTasks(it) },
            onDownloadLocationChange = { viewModel.updateDownloadLocation(it) },
            onOpenAllFilesAccess = openAllFilesAccess,
            onCheckYtDlpUpdates = { viewModel.checkYtDlpUpdates() },
            onDismiss = { showSettings = false }
        )
    }

    if (showStorageDialog) {
        AlertDialog(
            onDismissRequest = { showStorageDialog = false },
            title = { Text("下载的文件存在哪") },
            text = { Text("现在下载保存在应用私有目录，文件管理器看不到。建议去设置里开启“所有文件访问权限”，之后下载会保存到 Download/MDM，文件管理器里直接能找到。开启后重启应用生效。") },
            confirmButton = {
                TextButton(onClick = {
                    showStorageDialog = false
                    openAllFilesAccess()
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = {
                    context.getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)
                        .edit().putBoolean("storage_prompt_dismissed", true).apply()
                    showStorageDialog = false
                }) { Text("以后再说") }
            }
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出 MDM") },
            text = { Text("有下载正在进行，退出会停止这些下载，确定退出吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    doExit()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
            }
        )
    }

    if (pendingRemoveId != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoveId = null },
            title = { Text("移除任务") },
            text = { Text("同时删掉磁盘上的文件吗？选“仅从列表删除”，文件还留在手机里。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(pendingRemoveId!!, true)
                    pendingRemoveId = null
                }) { Text("从存储删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.remove(pendingRemoveId!!, false)
                    pendingRemoveId = null
                }) { Text("仅从列表删除") }
            }
        )
    }

    pendingBulkRemoveIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingBulkRemoveIds = null },
            title = { Text("移除 ${ids.size} 个任务") },
            text = { Text("同时删掉磁盘上的文件吗？选“仅从列表删除”，文件还留在手机里。") },
            confirmButton = {
                TextButton(onClick = {
                    ids.forEach { viewModel.remove(it, true) }
                    pendingBulkRemoveIds = null
                }) { Text("从存储删除全部") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ids.forEach { viewModel.remove(it, false) }
                    pendingBulkRemoveIds = null
                }) { Text("仅从列表删除") }
            }
        )
    }

    val selectedTask = tasks.firstOrNull { it.id == selectedTaskId }
    if (selectedTask != null) {
        AlertDialog(
            onDismissRequest = { selectedTaskId = null },
            title = { Text(selectedTask.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    TextButton(
                        enabled = selectedTask.state == DownloadState.COMPLETED,
                        onClick = { selectedTaskId = null; openDownloadedFile(context, selectedTask) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { selectedTaskId = null; shareTask(context, selectedTask) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("分享", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        enabled = selectedTask.state == DownloadState.COMPLETED,
                        onClick = { selectedTaskId = null; openTaskFolder(context, selectedTask) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开文件夹", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { selectedTaskId = null; viewModel.redownload(selectedTask) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重新下载", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        enabled = (selectedTask.kind == DownloadKind.HTTP || selectedTask.kind == DownloadKind.HLS) &&
                            (selectedTask.state == DownloadState.PAUSED || selectedTask.state == DownloadState.FAILED),
                        onClick = {
                            updateLinkTaskId = selectedTask.id
                            updateLinkValue = selectedTask.sourceUrl
                            updateLinkError = null
                            selectedTaskId = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("更新链接", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            selectedTaskId = null
                            if (selectedTask.kind == DownloadKind.YOUTUBE) viewModel.retryOptions(selectedTask)
                            else retryOptionsTaskId = selectedTask.id
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重下选项", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { pendingRemoveId = selectedTask.id; selectedTaskId = null },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("删除", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("download URL", selectedTask.sourceUrl))
                            Toast.makeText(context, "已复制下载链接", Toast.LENGTH_SHORT).show()
                            selectedTaskId = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("复制下载链接", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        enabled = selectedTask.state == DownloadState.COMPLETED,
                        onClick = { fileOpsTaskId = selectedTask.id; selectedTaskId = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("复制/移动/重命名", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { propertiesTaskId = selectedTask.id; selectedTaskId = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("属性", modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTaskId = null }) { Text("关闭") }
            }
        )
    }
    val fileOpsTask = tasks.firstOrNull { it.id == fileOpsTaskId }
    if (fileOpsTask != null) {
        AlertDialog(
            onDismissRequest = { fileOpsTaskId = null },
            title = { Text("复制/移动/重命名") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            renameValue = fileOpsTask.title
                            renameError = null
                            renamingTaskId = fileOpsTask.id
                            fileOpsTaskId = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重命名", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            pendingFileOperation = "copy"
                            folderPicker.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("复制到…", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            pendingFileOperation = "move"
                            folderPicker.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("移动到…", modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = { TextButton(onClick = { fileOpsTaskId = null }) { Text("关闭") } }
        )
    }
    val retryTask = tasks.firstOrNull { it.id == retryOptionsTaskId }
    if (retryTask != null) {
        AlertDialog(
            onDismissRequest = { retryOptionsTaskId = null },
            title = { Text("重下选项") },
            text = { Text("此下载类型没有单独的画质或音频参数。将按原链接重新加入任务，并自动使用不冲突的文件名。") },
            confirmButton = {
                TextButton(onClick = { viewModel.redownload(retryTask); retryOptionsTaskId = null }) { Text("重新下载") }
            },
            dismissButton = { TextButton(onClick = { retryOptionsTaskId = null }) { Text("取消") } }
        )
    }
    val propertiesTask = tasks.firstOrNull { it.id == propertiesTaskId }
    if (propertiesTask != null) {
        AlertDialog(
            onDismissRequest = { propertiesTaskId = null },
            title = { Text("属性") },
            text = {
                Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text("名称：${propertiesTask.title}")
                    Text("类型：${when (propertiesTask.kind) {
                        DownloadKind.HTTP -> "HTTP/HTTPS"
                        DownloadKind.TORRENT_MAGNET -> "磁力链接"
                        DownloadKind.TORRENT_FILE -> "Torrent 文件"
                        DownloadKind.HLS -> "HLS 视频"
                        DownloadKind.YOUTUBE -> "YouTube"
                    }}")
                    Text("状态：${stateLabel(propertiesTask.state)}")
                    Text("大小：${formatSize(propertiesTask.downloadedBytes.toLong())}")
                    Text("路径：${propertiesTask.savePath}")
                    Text("来源：${propertiesTask.sourceUrl}")
                    val file = File(propertiesTask.savePath)
                    if (file.exists()) Text("修改时间：${java.text.DateFormat.getDateTimeInstance().format(file.lastModified())}")
                }
            },
            confirmButton = { TextButton(onClick = { propertiesTaskId = null }) { Text("关闭") } }
        )
    }

    val updateLinkTask = tasks.firstOrNull { it.id == updateLinkTaskId }
    if (updateLinkTask != null) {
        AlertDialog(
            onDismissRequest = { updateLinkTaskId = null },
            title = { Text("更新链接") },
            text = {
                Column {
                    OutlinedTextField(
                        value = updateLinkValue,
                        onValueChange = { updateLinkValue = it },
                        label = { Text("新的下载地址") },
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "只换地址，不动已下文件。保存后任务回到“已暂停”，点“继续”按新地址接着下；" +
                            "HTTP 新链接的文件和原来不一致时会从头开始下载；HLS 换链接后从头下载。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (updateLinkError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            updateLinkError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val error = viewModel.updateTaskUrl(updateLinkTask.id, updateLinkValue)
                        .exceptionOrNull()?.message
                    if (error == null) {
                        updateLinkTaskId = null
                        Toast.makeText(context, "链接已更新，点“继续”接着下载", Toast.LENGTH_LONG).show()
                    } else {
                        updateLinkError = error
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { updateLinkTaskId = null }) { Text("取消") }
            }
        )
    }

    val renamingTask = tasks.firstOrNull { it.id == renamingTaskId }
    if (renamingTask != null) {
        AlertDialog(
            onDismissRequest = { renamingTaskId = null },
            title = { Text("重命名") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "仅支持已完成的文件；扩展名会保留。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (renameError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            renameError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val error = viewModel.rename(renamingTask.id, renameValue)
                        .exceptionOrNull()?.message
                    if (error == null) {
                        renamingTaskId = null
                    } else {
                        renameError = error
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingTaskId = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    position: Int,
    taskCount: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val progress = if (task.totalBytes > 0uL) {
        (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    // 引擎每 0.5-1 秒上报一次；用略短于上报周期的线性动画平滑追赶，避免显示滞后。
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "downloadProgress"
    )
    val animatedBytes = (animatedProgress * task.totalBytes.toFloat()).toLong()
    val context = LocalContext.current
    var startedAtMillis by rememberSaveable(task.id) {
        mutableLongStateOf(DownloadTaskTiming.startedAt(context, task.id))
    }
    var nowMillis by remember(task.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id, task.state) {
        if (startedAtMillis == 0L) {
            val savedStart = DownloadTaskTiming.startedAt(context, task.id)
            if (savedStart > 0L) startedAtMillis = savedStart
        }
        if (task.state == DownloadState.DOWNLOADING && startedAtMillis == 0L) {
            startedAtMillis = DownloadTaskTiming.ensureStartedAt(context, task.id)
        }
        if (task.state == DownloadState.DOWNLOADING) {
            while (task.state == DownloadState.DOWNLOADING) {
                nowMillis = System.currentTimeMillis()
                delay(1000)
            }
        } else {
            nowMillis = DownloadTaskTiming.stoppedAt(context, task.id)
                .takeIf { it > 0L } ?: System.currentTimeMillis()
        }
    }
    val stoppedAtMillis = remember(task.id, task.state, task.downloadedBytes) {
        DownloadTaskTiming.stoppedAt(context, task.id)
    }
    val elapsedEndMillis = if (task.state == DownloadState.DOWNLOADING) nowMillis
    else stoppedAtMillis.takeIf { it > 0L } ?: nowMillis
    val elapsedSeconds = if (startedAtMillis > 0L) {
        ((elapsedEndMillis - startedAtMillis) / 1000L).coerceAtLeast(0L)
    } else 0L
    val totalBytes = task.totalBytes.toLong()
    val downloadedBytes = task.downloadedBytes.toLong()
    val remainingSeconds = if (totalBytes > downloadedBytes && task.speedBytesPerSec > 0uL) {
        (totalBytes - downloadedBytes) / task.speedBytesPerSec.toLong().coerceAtLeast(1L)
    } else null
    val minimalist = MaterialTheme.colorScheme.primary == Color(0xFFFED54F)
    val progressColor = if (minimalist) Color(0xFFFED54F) else MaterialTheme.colorScheme.primary
    val leadingColor = if (minimalist) Color(0xFF508F89) else MaterialTheme.colorScheme.secondaryContainer
    val rowProgress = if (task.state == DownloadState.COMPLETED) 1f else animatedProgress
    val rowBaseColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    val defaultLeadingColor = MaterialTheme.colorScheme.secondaryContainer
    val rowModifier = Modifier.fillMaxWidth().height(64.dp).then(
        if (minimalist) Modifier else Modifier.drawBehind {
            drawRect(color = rowBaseColor)
            val iconWidth = 38.dp.toPx().coerceAtMost(size.width)
            drawRect(color = defaultLeadingColor, size = Size(iconWidth, size.height))
            if (rowProgress > 0f) {
                drawRect(
                    color = Color(0xFF508F89),
                    size = Size(size.width * rowProgress, size.height)
                )
            }
        }
    )
    val (taskIcon, taskIconDescription) = taskFileIcon(task)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = rowBaseColor)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
        ) {
            if (task.totalBytes > 0uL) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = progressColor,
                    trackColor = if (minimalist) Color(0xFF212121) else MaterialTheme.colorScheme.surfaceVariant
                )
            } else if (task.state == DownloadState.DOWNLOADING || task.state == DownloadState.QUEUED) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = progressColor,
                    trackColor = if (minimalist) Color(0xFF212121) else MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    progress = { if (task.state == DownloadState.COMPLETED) 1f else 0f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = progressColor,
                    trackColor = if (minimalist) Color(0xFF212121) else MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Box(modifier = rowModifier) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                Box(
                    modifier = Modifier.width(38.dp).fillMaxHeight().then(
                        if (minimalist) Modifier.background(leadingColor) else Modifier
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = { onClick() })
                    } else {
                        Icon(
                            imageVector = taskIcon,
                            contentDescription = taskIconDescription,
                            tint = if (minimalist) Color(0xFFEAF4F2) else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = when (task.kind) {
                                DownloadKind.HTTP -> "HTTP"
                                DownloadKind.TORRENT_MAGNET -> "Magnet"
                                DownloadKind.TORRENT_FILE -> "视频"
                                DownloadKind.HLS -> "视频"
                                DownloadKind.YOUTUBE -> "YouTube"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (minimalist) Color(0xFF86C9C0) else MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                    val metadata = buildList {
                        if (task.totalBytes > 0uL) add("${(animatedProgress * 100).toInt()}% · ${formatSize(animatedBytes)}")
                        else if (downloadedBytes > 0L) add(formatSize(downloadedBytes))
                        else add(stateLabel(task.state))
                        if (!minimalist && elapsedSeconds > 0L) {
                            add(if (remainingSeconds != null) {
                                "${formatDuration(elapsedSeconds)}/${formatDuration(remainingSeconds)}"
                            } else formatDuration(elapsedSeconds))
                        }
                        // HLS 显示已拉取的分片数（不是列表序号）；HTTP 没有分片概念，这行不显示。
                        // 其它类型（磁力/YouTube）仍显示列表序号/总数。
                        when (task.kind) {
                            DownloadKind.HLS -> {
                                if (task.totalSegments > 0u) {
                                    add("已拉取 ${task.completedSegments}/${task.totalSegments} 分片")
                                } else if (task.completedSegments > 0u) {
                                    add("已拉取 ${task.completedSegments} 分片")
                                }
                            }
                            DownloadKind.HTTP -> Unit
                            else -> if (taskCount > 1) add("$position/$taskCount")
                        }
                        if (task.speedBytesPerSec > 0uL) add(formatSpeed(task.speedBytesPerSec.toLong()))
                        if (minimalist && elapsedSeconds > 0L) {
                            add(if (remainingSeconds != null) {
                                "${formatDuration(elapsedSeconds)}/${formatDuration(remainingSeconds)}"
                            } else formatDuration(elapsedSeconds))
                        }
                    }.joinToString(" · ")
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (task.errorMessage != null) {
                        Text(
                            text = task.errorMessage!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!selectionMode && task.state == DownloadState.DOWNLOADING) {
                    IconButton(onClick = onPause, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Pause, contentDescription = "暂停")
                    }
                } else if (!selectionMode && (task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED)) {
                    IconButton(onClick = onResume, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "继续")
                    }
                }
                if (!selectionMode) {
                    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "任务操作")
                    }
                }
            }
            }
        }
    }
}

private data class TaskFileIcon(val imageVector: ImageVector, val description: String)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "mov", "avi", "wmv", "flv", "webm", "m4v", "ts", "mpeg", "mpg", "3gp", "ogv"
)
private val ARCHIVE_EXTENSIONS = setOf(
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz", "tbz2", "txz"
)
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "m4b", "aiff", "alac"
)
private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "avif", "svg"
)

private fun taskFileIcon(task: DownloadTask): TaskFileIcon {
    val titleName = task.title.substringAfterLast('/').substringAfterLast('\\')
    val sourceName = task.sourceUrl.substringBefore('?').substringBefore('#')
        .substringAfterLast('/').substringAfterLast('\\')
    val extension = titleName.substringAfterLast('.', "").lowercase()
        .ifBlank { sourceName.substringAfterLast('.', "").lowercase() }
    return when {
        task.kind == DownloadKind.TORRENT_MAGNET -> TaskFileIcon(Icons.Default.Share, "种子 / P2P 下载")
        extension == "torrent" -> TaskFileIcon(Icons.Default.Share, "Torrent 种子文件")
        extension in VIDEO_EXTENSIONS -> TaskFileIcon(Icons.Default.Movie, "视频文件")
        extension in ARCHIVE_EXTENSIONS -> TaskFileIcon(Icons.Default.FolderZip, "压缩包")
        extension in AUDIO_EXTENSIONS -> TaskFileIcon(Icons.Default.MusicNote, "音频文件")
        extension in IMAGE_EXTENSIONS -> TaskFileIcon(Icons.Default.Image, "图片文件")
        extension in setOf("apk", "aab", "apks", "xapk") -> TaskFileIcon(Icons.Default.Android, "Android 安装包")
        extension == "pdf" -> TaskFileIcon(Icons.Default.PictureAsPdf, "PDF 文件")
        task.kind == DownloadKind.TORRENT_FILE -> TaskFileIcon(Icons.Default.Share, "Torrent 下载")
        task.kind == DownloadKind.HLS || task.kind == DownloadKind.YOUTUBE ->
            TaskFileIcon(Icons.Default.Movie, "视频文件")
        else -> TaskFileIcon(Icons.Default.InsertDriveFile, "文件")
    }
}

private fun stateLabel(state: DownloadState): String = when (state) {
    DownloadState.QUEUED -> "排队中"
    DownloadState.DOWNLOADING -> "下载中"
    DownloadState.PAUSED -> "已暂停"
    DownloadState.COMPLETED -> "已完成"
    DownloadState.FAILED -> "失败"
}

private fun openDownloadedFile(context: Context, task: DownloadTask) {
    val taskPath = File(task.savePath)
    val file = if (task.savePath.startsWith("content://")) null
    else if (taskPath.isDirectory) findPlayableFile(taskPath) else taskPath
    if (file?.exists() != true && !task.savePath.startsWith("content://")) {
        Toast.makeText(context, "暂未找到可打开的下载文件", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = if (task.savePath.startsWith("content://")) Uri.parse(task.savePath) else FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file!!
        )
        val extension = task.title.substringAfterLast('.', file?.extension.orEmpty()).lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
    }
}

private fun shareTask(context: Context, task: DownloadTask) {
    try {
        val uri = if (task.savePath.startsWith("content://")) Uri.parse(task.savePath) else {
            val file = File(task.savePath)
            if (!file.isFile) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, task.sourceUrl)
                }
                context.startActivity(Intent.createChooser(intent, "分享下载链接"))
                return
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val extension = task.title.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, task.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享文件"))
    } catch (_: Exception) {
        Toast.makeText(context, "暂时无法分享此文件", Toast.LENGTH_SHORT).show()
    }
}

private fun openTaskFolder(context: Context, task: DownloadTask) {
    if (task.savePath.startsWith("content://")) {
        runCatching {
            val documentUri = Uri.parse(task.savePath)
            val path = DocumentsContract.findDocumentPath(context.contentResolver, documentUri)?.path
                ?: error("无法定位文件路径")
            val parentId = path.dropLast(1).lastOrNull() ?: error("无法定位上级目录")
            val folderUri = if (DocumentsContract.isTreeUri(documentUri)) {
                DocumentsContract.buildDocumentUriUsingTree(documentUri, parentId)
            } else {
                DocumentsContract.buildDocumentUri(documentUri.authority ?: error("无法识别文件提供方"), parentId)
            }
            context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri)
            })
        }.onFailure {
            Toast.makeText(context, "无法打开此系统目录，请在文件管理器中查看", Toast.LENGTH_LONG).show()
        }
        return
    }
    val folder = File(task.savePath).parentFile
    if (folder == null || !folder.exists()) {
        Toast.makeText(context, "找不到下载文件夹", Toast.LENGTH_SHORT).show()
        return
    }
    val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val folderPath = folder.absoluteFile.path
    val publicPath = publicDownloads.absoluteFile.path
    if (folderPath == publicPath || folderPath.startsWith("$publicPath${File.separator}")) {
        runCatching {
            val relative = if (folderPath == publicPath) "" else folder.relativeTo(publicDownloads).invariantSeparatorsPath
            val documentId = listOf("Download", relative).filter { it.isNotBlank() }.joinToString("/")
            val folderUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:$documentId"
            )
            context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri)
            })
        }.onFailure {
            Toast.makeText(context, "无法打开下载文件夹：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    } else {
        Toast.makeText(context, "此目录位于应用私有存储中，无法在系统文件管理器中直接打开", Toast.LENGTH_LONG).show()
    }
}

private fun copyTaskFileToTree(
    context: Context,
    task: DownloadTask,
    treeUri: Uri,
    move: Boolean
): Pair<Uri, String> {
    val taskFile = if (task.savePath.startsWith("content://")) null else File(task.savePath)
    val sourceFile = if (taskFile?.isDirectory == true) findPlayableFile(taskFile) else taskFile
    val sourceUri = task.savePath.takeIf { it.startsWith("content://") }?.let(Uri::parse)
    require(sourceFile?.isFile == true || sourceUri != null) { "找不到可复制的文件" }
    val fileName = sourceFile?.name ?: task.title
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    val destination = DocumentsContract.createDocument(context.contentResolver, parent, mimeType, fileName)
        ?: error("无法在所选目录创建文件")
    val treeDocument = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getDocumentId(destination)
    )
    try {
        val input = sourceFile?.inputStream() ?: context.contentResolver.openInputStream(sourceUri!!)
            ?: error("无法读取源文件")
        input.use { source ->
            context.contentResolver.openOutputStream(treeDocument, "w")?.use { output ->
                source.copyTo(output)
            } ?: error("无法写入目标文件")
        }
    } catch (error: Exception) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, treeDocument) }
        throw error
    }
    if (move) {
        if (sourceFile != null) check(sourceFile.delete()) { "复制完成，但无法删除原文件" }
        else check(context.contentResolver.delete(sourceUri!!, null, null) > 0) { "复制完成，但无法删除原文件" }
    }
    return treeDocument to fileName
}

private fun findPlayableFile(root: File): File? {
    val playableExtensions = setOf("mp4", "mkv", "webm", "mov", "avi", "ts", "m4v", "mp3", "m4a", "aac", "flac", "wav", "ogg")
    val pending = java.util.ArrayDeque<File>()
    pending.add(root)
    var inspected = 0
    while (pending.isNotEmpty() && inspected < 300) {
        val current = pending.removeFirst()
        inspected++
        if (current.isFile) {
            if (current.extension.lowercase() in playableExtensions) return current
        } else {
            current.listFiles()?.sortedBy { it.name }?.take(40)?.forEach(pending::addLast)
        }
    }
    return null
}

@Composable
private fun AddLinkDialog(
    youtubeEnabled: Boolean,
    onDismiss: () -> Unit,
    onPickTorrentFile: () -> Unit,
    onConfirm: (String, String?, Int?, Int?) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var selectedHeight by remember { mutableStateOf<Int?>(null) }
    var selectedAudio by remember { mutableStateOf<Int?>(null) }
    val lineCount = text.lines().map { it.trim() }.count { it.isNotEmpty() }
    val isMultiLine = lineCount > 1
    val isYoutube = lineCount == 1 && isYouTubePageLink(text)
    val heights = listOf(360, 480, 720, 1080, 1440, 2160)
    val audioBitrates = YoutubeMediaResolver.audioBitrateChoices
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建下载任务") },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                    },
                    label = {
                        Text(
                            if (youtubeEnabled) "输入链接，可多行粘贴（每行一个）：YouTube、直链、magnet:?、.m3u8"
                            else "输入链接，可多行粘贴（每行一个）：直链、magnet:?、.m3u8"
                        )
                    },
                    singleLine = false,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isMultiLine) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "已输入 $lineCount 行，将一次添加 $lineCount 个任务（YouTube 链接和剧集页请逐条添加）。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!isMultiLine) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("文件名（可选，空着按链接取名）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPickTorrentFile,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("选择 .torrent 文件") }
                Text(
                    "有 .torrent 文件也可以直接选：单文件直接下载，多文件会弹列表让您勾选。",
                    style = MaterialTheme.typography.bodySmall
                )
                if (isYoutube && youtubeEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("视频清晰度", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        heights.forEach { height ->
                            FilterChip(
                                selected = selectedHeight == height,
                                onClick = { selectedHeight = if (selectedHeight == height) null else height },
                                label = {
                                    Text(when (height) {
                                        1440 -> "2K · 1440p"
                                        2160 -> "4K · 2160p"
                                        else -> "${height}p"
                                    })
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("音频码率", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        audioBitrates.forEach { bitrate ->
                            FilterChip(
                                selected = selectedAudio == bitrate,
                                onClick = { selectedAudio = if (selectedAudio == bitrate) null else bitrate },
                                label = { Text("${bitrate}k") }
                            )
                        }
                    }
                    Text(
                        if (selectedHeight != null && selectedAudio != null && !isYouTubePlaylistLink(text))
                            "已选清晰度与音频：点“开始下载”后直接启动；所选档位不可用时自动降到下一档。"
                        else if (selectedHeight == null && selectedAudio != null && !isYouTubePlaylistLink(text))
                            "未选视频清晰度：只下载音频，并转换保存为 .m4a。"
                        else if (isYouTubePlaylistLink(text))
                            "播放列表会先读取条目；未选视频清晰度时可只下载音频（.m4a），再多选条目。"
                        else
                            "选好音频码率和视频清晰度可直接下载；只选音频不选清晰度时会保存为 .m4a。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (youtubeEnabled) {
                    Text(
                        "只选音频、不选视频清晰度时仅下载音频并转成 .m4a；未完成其他选项时会先解析。同名文件不会覆盖已有文件。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text("同名文件不会覆盖已有文件。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    text,
                    fileName.ifBlank { null },
                    selectedHeight,
                    selectedAudio
                )
            }) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 磁力/种子文件选择对话框（复用 YoutubePickerDialog 的多选样式）：
 * 每行文件名 + 大小，默认全选；什么都不选时“开始下载”不可点。
 * 注意：传回的是文件在种子里的原始下标（填充文件不占位、不重排），
 * 引擎直接按它调 session.add。
 */
@Composable
private fun MagnetFilePickerDialog(
    title: String,
    files: List<MagnetFileInfo>,
    onDismiss: () -> Unit,
    onConfirm: (List<UInt>) -> Unit
) {
    var selected by remember(files) { mutableStateOf(files.map { it.index }.toSet()) }
    val allSelected = selected.size == files.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "$title（${files.size} 个文件）",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp, max = 560.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked ->
                            selected = if (checked) files.map { it.index }.toSet() else emptySet()
                        }
                    )
                    Text(
                        if (allSelected) "已全选" else "全选",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "已选 ${selected.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn {
                    items(files) { file ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = file.index in selected,
                                onCheckedChange = { checked ->
                                    selected =
                                        if (checked) selected + file.index else selected - file.index
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    formatSize(file.size.toLong()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(files.filter { it.index in selected }.map { it.index }) }
            ) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDuration(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val remainder = safeSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, remainder)
    } else if (minutes > 0) {
        String.format("%dm%02ds", minutes, remainder)
    } else {
        "${remainder}s"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    youtubeEnabled: Boolean,
    themeStyle: String,
    maxConnections: Int,
    speedLimitBps: Long,
    uploadLimitBps: Long,
    retryEnabled: Boolean,
    retryMaxAttempts: Int,
    retryDelaySecs: Int,
    retryAlways: Boolean,
    removeAds: Boolean,
    hlsParallelSegments: Int,
    maxConcurrentTasks: Int,
    ytDlpUpdateStatus: String?,
    checkingYtDlpUpdate: Boolean,
    downloadDir: String,
    downloadLocation: String,
    hasAllFilesAccess: Boolean,
    onThemeStyleChange: (String) -> Unit,
    onConnectionsChange: (Int) -> Unit,
    onSpeedLimitChange: (Long) -> Unit,
    onUploadLimitChange: (Long) -> Unit,
    onRetryEnabledChange: (Boolean) -> Unit,
    onRetryMaxAttemptsChange: (Int) -> Unit,
    onRetryDelaySecsChange: (Int) -> Unit,
    onRetryAlwaysChange: (Boolean) -> Unit,
    onRemoveAdsChange: (Boolean) -> Unit,
    onHlsParallelSegmentsChange: (Int) -> Unit,
    onMaxConcurrentTasksChange: (Int) -> Unit,
    onDownloadLocationChange: (String) -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onCheckYtDlpUpdates: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var hasStoredCookieData by remember { mutableStateOf(YoutubeCookieStore.hasAnyCookieData(context)) }
    var hasYoutubeCookies by remember { mutableStateOf(false) }
    var checkingCookieStore by remember { mutableStateOf(true) }
    var youtubeCookiesEnabled by remember { mutableStateOf(false) }
    var youtubeCookieCount by remember { mutableIntStateOf(YoutubeCookieStore.cookieCount(context)) }
    var cookieNotice by remember { mutableStateOf<String?>(null) }
    var importingCookieFile by remember { mutableStateOf(false) }
    var clearingCookieData by remember { mutableStateOf(false) }
    var updatingCookieSetting by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        val (flags, count) = withContext(Dispatchers.IO) {
            Pair(
                Triple(
                    YoutubeCookieStore.hasAnyCookieData(context),
                    YoutubeCookieStore.hasCookies(context),
                    YoutubeCookieStore.isEnabled(context)
                ),
                YoutubeCookieStore.cookieCount(context)
            )
        }
        hasStoredCookieData = flags.first
        hasYoutubeCookies = flags.second
        youtubeCookiesEnabled = flags.third
        youtubeCookieCount = count
        checkingCookieStore = false
    }
    val cookieFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                importingCookieFile = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val count = YoutubeCookieStore.importFile(context, uri)
                        count to YoutubeCookieStore.isEnabled(context)
                    }
                }
                importingCookieFile = false
                result.onSuccess { (count, enabled) ->
                    hasStoredCookieData = true
                    hasYoutubeCookies = true
                    youtubeCookieCount = count
                    youtubeCookiesEnabled = enabled
                    cookieNotice = "已加密导入 $count 个 YouTube/Google Cookie；${if (youtubeCookiesEnabled) "已启用" else "尚未启用"}。"
                }.onFailure { error ->
                    cookieNotice = error.message ?: "Cookie 文件导入失败"
                }
            }
        }
    }
    val speedOptions = listOf(
        0L to "不限速",
        512L * 1024 to "500 KB/s",
        1024L * 1024 to "1 MB/s",
        5L * 1024 * 1024 to "5 MB/s",
        10L * 1024 * 1024 to "10 MB/s"
    )
    val concurrentTaskOptions = listOf(1, 2, 3, 4, 5, 8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column {
                    Text("外观主题", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = themeStyle == "default",
                            onClick = { onThemeStyleChange("default") },
                            label = { Text("标准") }
                        )
                        FilterChip(
                            selected = themeStyle == "minimalist",
                            onClick = { onThemeStyleChange("minimalist") },
                            label = { Text("Minimalist") }
                        )
                    }
                    Text(
                        "Minimalist 使用深灰纯色界面、青绿色图标区和暖黄进度线。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column {
                    Text("下载目录", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(downloadDir, style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    Text("下载位置", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = downloadLocation == "public",
                            onClick = { onDownloadLocationChange("public") }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("手机存储 Download/MDM", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "文件管理器直接能找到；需要所有文件访问权限",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = downloadLocation == "private",
                            onClick = { onDownloadLocationChange("private") }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("应用私有目录", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "不需要权限，但文件管理器看不到",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (downloadLocation == "public" && !hasAllFilesAccess) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "还没开启所有文件访问权限，现在下载会先存在应用私有目录。这个权限在“设置 - 应用 - 特殊应用权限 - 所有文件访问权限”里，不在应用详情的常规权限列表里，所以那边看着是灰的。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onOpenAllFilesAccess) { Text("去开启权限") }
                    }
                    Text("切换后重启应用生效", style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    Text(
                        "每任务连接数：$maxConnections",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = maxConnections.toFloat(),
                        onValueChange = { onConnectionsChange(it.toInt()) },
                        valueRange = 1f..16f,
                        steps = 14
                    )
                    Text(
                        "连接越多越快，但部分服务器会限制；新下载生效",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column {
                    Text(
                        "同时开始任务数：$maxConcurrentTasks",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        concurrentTaskOptions.forEach { count ->
                            FilterChip(
                                selected = maxConcurrentTasks == count,
                                onClick = { onMaxConcurrentTasksChange(count) },
                                label = { Text(count.toString()) }
                            )
                        }
                    }
                    Text("超过数量的任务会排队等待", style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    Text("全局限速", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        speedOptions.forEach { (bps, label) ->
                            FilterChip(
                                selected = speedLimitBps == bps,
                                onClick = { onSpeedLimitChange(bps) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                Column {
                    Text("上传限速（磁力做种）", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        speedOptions.forEach { (bps, label) ->
                            FilterChip(
                                selected = uploadLimitBps == bps,
                                onClick = { onUploadLimitChange(bps) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                Column {
                    Text("重试设置（m3u8 视频）", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("出错重试", style = MaterialTheme.typography.bodyMedium)
                            Text("下载出错时自动重试", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = retryEnabled, onCheckedChange = onRetryEnabledChange)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("始终重试", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "即使服务器错误无法恢复，也始终重试下载",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = retryAlways, onCheckedChange = onRetryAlwaysChange)
                    }
                    Text(
                        "请注意：启用始终重试将导致过多的数据浪费和电量消耗",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = retryMaxAttempts.toString(),
                        onValueChange = {
                            val digits = it.filter { c -> c.isDigit() }
                            onRetryMaxAttemptsChange(digits.toIntOrNull() ?: 0)
                        },
                        label = { Text("重试次数（0 代表无限）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = retryDelaySecs.toString(),
                        onValueChange = {
                            val digits = it.filter { c -> c.isDigit() }
                            onRetryDelaySecsChange(digits.toIntOrNull() ?: 0)
                        },
                        label = { Text("重试间隔（秒）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("去广告（m3u8 视频）", style = MaterialTheme.typography.bodyMedium)
                            Text("同一列表拉两次，不一样的广告分片跳过不下", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = removeAds, onCheckedChange = onRemoveAdsChange)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "m3u8 同时下载的分片数：$hlsParallelSegments",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = hlsParallelSegments.toFloat(),
                        onValueChange = { onHlsParallelSegmentsChange(it.toInt()) },
                        valueRange = 4f..16f,
                        steps = 11
                    )
                    Text(
                        "分片越多越快，但部分网关会限速；新下载生效",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (youtubeEnabled) {
                    Column {
                        Text("YouTube 登录 Cookie", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                checkingCookieStore -> "正在检查本机 Cookie…"
                                hasYoutubeCookies ->
                                    "已导入 $youtubeCookieCount 个 YouTube/Google Cookie · ${if (youtubeCookiesEnabled) "已启用" else "已停用"}"
                                hasStoredCookieData -> "本机存在 Cookie 数据或残留临时副本，但当前不可用；可重新导入或清除。"
                                else -> "尚未导入 Cookie 文件"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("用于需要登录的 YouTube 下载", style = MaterialTheme.typography.bodyMedium)
                                Text("停用只影响新请求；已开始的请求可能继续使用临时副本。", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = youtubeCookiesEnabled && hasYoutubeCookies,
                                enabled = hasYoutubeCookies && !checkingCookieStore && !updatingCookieSetting &&
                                    !clearingCookieData && !importingCookieFile,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch {
                                        updatingCookieSetting = true
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                YoutubeCookieStore.setEnabled(context, enabled)
                                                YoutubeCookieStore.isEnabled(context)
                                            }
                                        }
                                        updatingCookieSetting = false
                                        result.onSuccess { isEnabled ->
                                            youtubeCookiesEnabled = isEnabled
                                            cookieNotice = if (isEnabled) {
                                                "已启用 Cookie，用于之后开始的请求。"
                                            } else {
                                                "已停用 Cookie，用于之后开始的请求。"
                                            }
                                        }.onFailure {
                                            cookieNotice = it.message ?: "Cookie 设置更新失败"
                                        }
                                    }
                                }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { cookieFilePicker.launch(arrayOf("text/*", "application/octet-stream")) },
                                enabled = !importingCookieFile && !clearingCookieData
                            ) {
                                Text(if (importingCookieFile) "正在导入…" else "导入 cookies.txt")
                            }
                            TextButton(
                                onClick = {
                                    coroutineScope.launch {
                                        clearingCookieData = true
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { YoutubeCookieStore.clear(context) }
                                        }
                                        val (flags, count) = withContext(Dispatchers.IO) {
                                            Pair(
                                                Triple(
                                                    YoutubeCookieStore.hasAnyCookieData(context),
                                                    YoutubeCookieStore.hasCookies(context),
                                                    YoutubeCookieStore.isEnabled(context)
                                                ),
                                                YoutubeCookieStore.cookieCount(context)
                                            )
                                        }
                                        hasStoredCookieData = flags.first
                                        hasYoutubeCookies = flags.second
                                        youtubeCookiesEnabled = flags.third
                                        youtubeCookieCount = count
                                        clearingCookieData = false
                                        cookieNotice = when {
                                            result.isFailure -> result.exceptionOrNull()?.message ?: "清除 Cookie 失败"
                                            result.getOrDefault(false) -> "已清除本机保存的 Cookie。"
                                            else -> "Cookie 清理未能完全完成；请稍后重试，或重新导入。"
                                        }
                                    }
                                },
                                enabled = hasStoredCookieData && !clearingCookieData && !importingCookieFile
                            ) {
                                Text(if (clearingCookieData) "清除中…" else "清除")
                            }
                        }
                        Text(
                            "仅手动导入你本人已登录浏览器导出的 Netscape 格式文件。应用只保留 YouTube/Google 域名 Cookie，并用 Android Keystore 加密存于本机（不备份、不上传）。Cookie 等同登录凭据，请勿分享；此功能不提供内置登录，也不绕过验证码。清除会删除当前临时副本，进行中的登录请求可能失败。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        cookieNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("yt-dlp 视频解析", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "更新检查为手动操作，只使用 Stable，不会自动切换到 Nightly。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onCheckYtDlpUpdates, enabled = !checkingYtDlpUpdate) {
                            if (checkingYtDlpUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (checkingYtDlpUpdate) "正在检查…" else "检查 yt-dlp 更新（Stable）")
                        }
                        ytDlpUpdateStatus?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

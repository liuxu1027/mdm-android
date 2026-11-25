package com.mdm.android.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mdm.android.service.DownloadForegroundService
import com.mdm.android.service.YoutubeRuntime
import com.mdm.android.service.YoutubeMediaResolver
import com.mdm.android.service.YoutubeCookieStore
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
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
    var fileOpsTaskId by remember { mutableStateOf<String?>(null) }
    var propertiesTaskId by remember { mutableStateOf<String?>(null) }
    var retryOptionsTaskId by remember { mutableStateOf<String?>(null) }
    var pendingFileOperation by remember { mutableStateOf<String?>(null) }
    var renamingTaskId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val youtubeResolveState = viewModel.youtubeResolveState
    val hasActiveDownload = tasks.any { it.state == DownloadState.DOWNLOADING }
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
            1 -> tasks.filter { it.kind == DownloadKind.HTTP }
            2 -> tasks.filter { it.kind != DownloadKind.HTTP }
            else -> tasks
        }
    }
    val hasFinished = tasks.any {
        it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mister Download Manager") },
                actions = {
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
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("全部") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("HTTP/HTTPS") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("磁力 / 视频") })
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
                    items(filteredTasks, key = { it.id }) { task ->
                        DownloadTaskCard(
                            task = task,
                            onClick = { selectedTaskId = task.id },
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
            onConfirm = { link, name, height, audio ->
                viewModel.addLink(link, name, height, audio)
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

    if (showSettings) {
        SettingsDialog(
            youtubeEnabled = YoutubeRuntime.youtubeEnabled,
            maxConnections = viewModel.maxConnections,
            speedLimitBps = viewModel.speedLimitBps,
            uploadLimitBps = viewModel.uploadLimitBps,
            retryEnabled = viewModel.retryEnabled,
            retryMaxAttempts = viewModel.retryMaxAttempts,
            retryDelaySecs = viewModel.retryDelaySecs,
            retryAlways = viewModel.retryAlways,
            removeAds = viewModel.removeAds,
            hlsParallelSegments = viewModel.hlsParallelSegments,
            downloadDir = viewModel.downloadDir,
            downloadLocation = viewModel.downloadLocation,
            hasAllFilesAccess = DownloadForegroundService.hasAllFilesAccess(context),
            onConnectionsChange = { viewModel.updateMaxConnections(it) },
            onSpeedLimitChange = { viewModel.updateSpeedLimitBps(it) },
            onUploadLimitChange = { viewModel.updateUploadLimitBps(it) },
            onRetryEnabledChange = { viewModel.updateRetryEnabled(it) },
            onRetryMaxAttemptsChange = { viewModel.updateRetryMaxAttempts(it) },
            onRetryDelaySecsChange = { viewModel.updateRetryDelaySecs(it) },
            onRetryAlwaysChange = { viewModel.updateRetryAlways(it) },
            onRemoveAdsChange = { viewModel.updateRemoveAds(it) },
            onHlsParallelSegmentsChange = { viewModel.updateHlsParallelSegments(it) },
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

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onClick: () -> Unit,
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

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
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
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (task.kind) {
                            DownloadKind.HTTP -> "HTTP"
                            DownloadKind.TORRENT_MAGNET -> "Magnet"
                            DownloadKind.TORRENT_FILE -> "Torrent"
                            DownloadKind.HLS -> "视频"
                            DownloadKind.YOUTUBE -> "YouTube"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (task.totalBytes > 0uL) {
                            "${(animatedProgress * 100).toInt()}% | ${formatSize(animatedBytes)}"
                        } else if (task.downloadedBytes > 0uL) {
                            "已下载 ${formatSize(task.downloadedBytes.toLong())}"
                        } else {
                            "等待开始…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${stateLabel(task.state)} · ${formatSpeed(task.speedBytesPerSec.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
            if (task.state == DownloadState.DOWNLOADING) {
                IconButton(onClick = onPause, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Pause, contentDescription = "暂停")
                }
            } else if (task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED) {
                IconButton(onClick = onResume, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "继续")
                }
            }
            IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "任务操作")
            }
            }
            if (task.totalBytes > 0uL) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.width(36.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.weight(0.7f).height(3.dp)
                    )
                    Spacer(modifier = Modifier.weight(0.3f))
                }
            }
        }
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
    onConfirm: (String, String?, Int?, Int?) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var selectedHeight by remember { mutableStateOf<Int?>(null) }
    var selectedAudio by remember { mutableStateOf<Int?>(null) }
    val isYoutube = isYouTubePageLink(text)
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
                    label = { Text("输入 YouTube 视频/播放列表、HTTP/HTTPS 直链、magnet:? 或 .m3u8 链接") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("文件名（可选，空着按链接取名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
                        else if (isYouTubePlaylistLink(text))
                            "播放列表仍会先读取条目，供你多选或全选后开始下载。"
                        else
                            "清晰度和音频都选好后可直接启动；未选齐时先解析，再确认视频与字幕。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (isYoutube) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Lite 版不支持 YouTube 下载，请使用 Full 版。", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "未选齐清晰度和音频时，YouTube 会先解析视频信息；播放列表需选择下载条目。同名文件不会覆盖已有文件。",
                    style = MaterialTheme.typography.bodySmall
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    youtubeEnabled: Boolean,
    maxConnections: Int,
    speedLimitBps: Long,
    uploadLimitBps: Long,
    retryEnabled: Boolean,
    retryMaxAttempts: Int,
    retryDelaySecs: Int,
    retryAlways: Boolean,
    removeAds: Boolean,
    hlsParallelSegments: Int,
    downloadDir: String,
    downloadLocation: String,
    hasAllFilesAccess: Boolean,
    onConnectionsChange: (Int) -> Unit,
    onSpeedLimitChange: (Long) -> Unit,
    onUploadLimitChange: (Long) -> Unit,
    onRetryEnabledChange: (Boolean) -> Unit,
    onRetryMaxAttemptsChange: (Int) -> Unit,
    onRetryDelaySecsChange: (Int) -> Unit,
    onRetryAlwaysChange: (Boolean) -> Unit,
    onRemoveAdsChange: (Boolean) -> Unit,
    onHlsParallelSegmentsChange: (Int) -> Unit,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                        TextButton(onClick = onCheckYtDlpUpdates) {
                            Text("检查 yt-dlp 更新（Stable）")
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

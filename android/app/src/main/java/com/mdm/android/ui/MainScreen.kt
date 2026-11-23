package com.mdm.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdm.android.service.DownloadForegroundService
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask

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
    val context = LocalContext.current
    val hasActiveDownload = tasks.any { it.state == DownloadState.DOWNLOADING }

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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        DownloadTaskCard(
                            task = task,
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task.id) },
                            onRemove = { pendingRemoveId = task.id }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddLinkDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { link ->
                viewModel.addLink(link)
                showAddDialog = false
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            maxConnections = viewModel.maxConnections,
            speedLimitBps = viewModel.speedLimitBps,
            uploadLimitBps = viewModel.uploadLimitBps,
            retryEnabled = viewModel.retryEnabled,
            retryMaxAttempts = viewModel.retryMaxAttempts,
            retryDelaySecs = viewModel.retryDelaySecs,
            retryAlways = viewModel.retryAlways,
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
            onDownloadLocationChange = { viewModel.updateDownloadLocation(it) },
            onOpenAllFilesAccess = openAllFilesAccess,
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
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit
) {
    val progress = if (task.totalBytes > 0uL) {
        (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when (task.kind) {
                                DownloadKind.HTTP -> "HTTP"
                                DownloadKind.TORRENT_MAGNET -> "Magnet"
                                DownloadKind.TORRENT_FILE -> "Torrent"
                                DownloadKind.HLS -> "视频"
                            }
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (task.totalBytes > 0uL) {
                    "${formatSize(task.downloadedBytes.toLong())} / ${formatSize(task.totalBytes.toLong())} · ${(progress * 100).toInt()}%"
                } else if (task.downloadedBytes > 0uL) {
                    "已下载 ${formatSize(task.downloadedBytes.toLong())}"
                } else {
                    "等待开始…"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (task.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (task.kind == DownloadKind.TORRENT_MAGNET || task.kind == DownloadKind.TORRENT_FILE) {
                        "${task.state.name} · ↓ ${formatSpeed(task.speedBytesPerSec.toLong())} · ↑ ${formatSpeed(task.uploadSpeedBytesPerSec.toLong())}"
                    } else {
                        "${task.state.name} · ${formatSpeed(task.speedBytesPerSec.toLong())}"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Row {
                    if (task.state == DownloadState.DOWNLOADING) {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "暂停")
                        }
                    } else if (task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED) {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "继续")
                        }
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建下载任务") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("输入 HTTP/HTTPS、magnet:? 或 .m3u8 链接") },
                singleLine = false,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("开始下载") }
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
    maxConnections: Int,
    speedLimitBps: Long,
    uploadLimitBps: Long,
    retryEnabled: Boolean,
    retryMaxAttempts: Int,
    retryDelaySecs: Int,
    retryAlways: Boolean,
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
    onDownloadLocationChange: (String) -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onDismiss: () -> Unit
) {
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
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

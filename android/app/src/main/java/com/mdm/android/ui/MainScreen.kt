package com.mdm.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val tasks by viewModel.tasks.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredTasks = remember(tasks, selectedTab) {
        when (selectedTab) {
            1 -> tasks.filter { it.kind == DownloadKind.HTTP }
            2 -> tasks.filter { it.kind != DownloadKind.HTTP }
            else -> tasks
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mister Download Manager") })
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
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("BT / 磁力") })
            }

            if (filteredTasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("点击右下角 + 添加 HTTP 链接或磁力链接", style = MaterialTheme.typography.bodyMedium)
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
                            onRemove = { viewModel.remove(task.id) }
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
                            }
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${task.state.name} · ${formatSpeed(task.speedBytesPerSec.toLong())}",
                    style = MaterialTheme.typography.bodySmall
                )
                Row {
                    if (task.state == DownloadState.DOWNLOADING) {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "暂停")
                        }
                    } else if (task.state == DownloadState.PAUSED) {
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
                label = { Text("输入 HTTP/HTTPS 或 magnet:? 链接") },
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

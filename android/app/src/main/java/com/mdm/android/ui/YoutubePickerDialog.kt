package com.mdm.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdm.android.service.YoutubeDownloadSpec
import com.mdm.android.service.YoutubeMediaEntry
import com.mdm.android.service.YoutubeMediaResolver
import com.mdm.android.service.YoutubeResolveResult

/** Lets the user finish choosing options and playlist entries before starting any download. */
@Composable
fun YoutubePickerDialog(
    result: YoutubeResolveResult,
    ffmpegEnabled: Boolean,
    onDismiss: () -> Unit,
    onDownload: (List<YoutubeDownloadSpec>) -> Unit
) {
    val state = remember(result, ffmpegEnabled) { PickerState(result, ffmpegEnabled) }
    val heightOptions = listOf(
        HeightOption("360p", 360),
        HeightOption("720p", 720),
        HeightOption("1080p", 1080),
        HeightOption("最高画质", null)
    )
    val audioOptions: List<ChoiceOption<Int?>> = remember(result, ffmpegEnabled) {
        listOf(ChoiceOption<Int?>("最佳音质", null)) + state.audioBitrates.map {
            ChoiceOption("≤ $it kbps", it)
        }
    }
    val subtitleOptions: List<ChoiceOption<String?>> = remember(result) {
        listOf(
            ChoiceOption<String?>("自动（中文优先）", null),
            ChoiceOption<String?>("不下载字幕", "")
        ) + state.subtitleLanguages.map { ChoiceOption(it, it) }
    }
    var selectedHeight by remember(result) { mutableIntStateOf(-1) }
    var selectedAudio by remember(result) { mutableIntStateOf(-1) }
    var selectedSubtitle by remember(result) { mutableIntStateOf(0) }
    var selectedUrls by remember(result) { mutableStateOf(emptySet<String>()) }
    val canSelectVideos = selectedHeight >= 0 && selectedAudio >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (result.isPlaylist) "${result.title}（${result.entries.size} 个视频）" else result.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.isPlaylist) {
                    Text(
                        text = "播放列表信息已解析",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp, max = 560.dp)
            ) {
                Text("清晰度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    heightOptions.forEachIndexed { index, option ->
                        FilterChip(
                            selected = selectedHeight == index,
                            onClick = { selectedHeight = index },
                            label = { Text(option.label) }
                        )
                    }
                }
                ChoiceDropdown(
                    title = "音频",
                    options = audioOptions,
                    selectedIndex = selectedAudio,
                    onSelectedIndexChange = { selectedAudio = it }
                )
                ChoiceDropdown(
                    title = "字幕",
                    options = subtitleOptions,
                    selectedIndex = selectedSubtitle,
                    onSelectedIndexChange = { selectedSubtitle = it }
                )
                if (!ffmpegEnabled) {
                    Text(
                        "Lite 仅下载原本带音频的视频流；Full 可用 FFmpeg 合并分离的音视频流。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (result.isPlaylist) "选择列表视频" else "确认视频",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = canSelectVideos,
                            onClick = { selectedUrls = state.entries.map { it.url }.toSet() }
                        ) {
                            Text("全选")
                        }
                        TextButton(enabled = canSelectVideos, onClick = { selectedUrls = emptySet() }) {
                            Text("清空")
                        }
                    }
                }
                Text(
                    text = if (canSelectVideos) "已选择 ${selectedUrls.size}/${state.entries.size}"
                    else "请先选择清晰度和音频，再勾选下载条目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(state.entries, key = { it.url }) { entry ->
                        YoutubeEntryRow(
                            entry = entry,
                            checked = entry.url in selectedUrls,
                            enabled = canSelectVideos,
                            onCheckedChange = { checked ->
                                selectedUrls = if (checked) selectedUrls + entry.url else selectedUrls - entry.url
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSelectVideos && selectedUrls.isNotEmpty(),
                onClick = {
                    val specs = YoutubeMediaResolver.buildSpecs(
                        result = result,
                        selectedUrls = selectedUrls,
                        targetHeight = heightOptions.getOrNull(selectedHeight)?.height,
                        audioCeiling = audioOptions.getOrNull(selectedAudio)?.value,
                        ffmpegEnabled = ffmpegEnabled,
                        subtitleLanguageOverride = subtitleOptions[selectedSubtitle].value
                    )
                    onDownload(specs)
                }
            ) { Text("开始下载") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun <T> ChoiceDropdown(
    title: String,
    options: List<ChoiceOption<T>>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit
) {
    var expanded by remember(title) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(options.getOrNull(selectedIndex)?.label ?: "请选择")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSelectedIndexChange(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun YoutubeEntryRow(
    entry: YoutubeMediaEntry,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val bestHeight = entry.formats.mapNotNull { it.height }.maxOrNull() ?: 0
    val subtitles = (entry.subtitleLanguages + entry.automaticSubtitleLanguages).distinct()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(top = 11.dp, end = 4.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(if (bestHeight > 0) "最高 ${bestHeight}p" else "无可用视频")
                    append(" · ")
                    append(if (subtitles.isEmpty()) "无字幕" else "字幕：${subtitles.joinToString(", ")}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitles.isEmpty()) {
                Text(
                    text = "无字幕，不进行转写",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class HeightOption(val label: String, val height: Int?)
private data class ChoiceOption<T>(val label: String, val value: T)

private class PickerState(result: YoutubeResolveResult, ffmpegEnabled: Boolean) {
    val entries: List<YoutubeMediaEntry> = result.entries
    val audioBitrates: List<Int> = entries
        .flatMap { entry ->
            entry.formats.filter { format ->
                ffmpegEnabled || (!format.vcodec.isNullOrBlank() &&
                    !format.vcodec.equals("none", true) &&
                    !format.acodec.isNullOrBlank() && !format.acodec.equals("none", true))
            }.mapNotNull { format -> format.abr?.toInt() }
        }
        .filter { it > 0 }
        .distinct()
        .sortedDescending()
        .take(8)
    val subtitleLanguages: List<String> = entries
        .flatMap { it.subtitleLanguages + it.automaticSubtitleLanguages }
        .distinct()
        .sortedWith(compareBy<String> { !it.startsWith("zh", ignoreCase = true) }
            .thenBy { !it.startsWith("en", ignoreCase = true) }
            .thenBy { it })
        .take(10)
}

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
    initialTargetHeight: Int? = null,
    initialAudioCeiling: Int? = null,
    onDismiss: () -> Unit,
    onDownload: (List<YoutubeDownloadSpec>) -> Unit
) {
    val heights = listOf(
        HeightOption("360p", 360),
        HeightOption("480p", 480),
        HeightOption("720p", 720),
        HeightOption("1080p", 1080),
        HeightOption("2K · 1440p", 1440),
        HeightOption("4K · 2160p", 2160)
    )
    val state = remember(result, ffmpegEnabled) { PickerState(result) }
    val audioOptions: List<ChoiceOption<Int?>> = remember(result) {
        listOf(ChoiceOption<Int?>("最高音质", null)) +
            (YoutubeMediaResolver.audioBitrateChoices + state.audioBitrates)
                .distinct()
                .sortedDescending()
                .map { ChoiceOption("≤ $it kbps", it) }
    }
    val subtitleOptions: List<ChoiceOption<String?>> = remember(result) {
        listOf(
            ChoiceOption<String?>("自动选择（中文优先）", null),
            ChoiceOption<String?>("不下载字幕", "")
        ) + state.subtitleLanguages.map { ChoiceOption(it, it) }
    }
    var selectedHeight by remember(result, initialTargetHeight) {
        mutableIntStateOf(heights.indexOfFirst { it.height == initialTargetHeight })
    }
    var selectedAudioOnly by remember(result, initialTargetHeight, initialAudioCeiling) {
        mutableStateOf(initialTargetHeight == null && initialAudioCeiling != null)
    }
    var selectedAudio by remember(result, initialAudioCeiling) {
        mutableIntStateOf(audioOptions.indexOfFirst { it.value == initialAudioCeiling }
            .takeIf { initialAudioCeiling != null } ?: -1)
    }
    var selectedSubtitle by remember(result) { mutableIntStateOf(0) }
    var selectedUrls by remember(result) {
        mutableStateOf(if (result.isPlaylist) emptySet() else result.entries.firstOrNull()?.url?.let { setOf(it) }.orEmpty())
    }
    val canChooseVideos = (selectedAudioOnly || selectedHeight >= 0) && selectedAudio >= 0
    val canStart = canChooseVideos && selectedUrls.isNotEmpty()
    val targetHeight = if (selectedAudioOnly) null else heights.getOrNull(selectedHeight)?.height
    val audioCeiling = audioOptions.getOrNull(selectedAudio)?.value

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
                        text = "播放列表已快速读取；视频清晰度会在下载时匹配，字幕缺失不会中断视频。",
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
                Text("视频清晰度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedAudioOnly,
                        enabled = ffmpegEnabled,
                        onClick = {
                            selectedAudioOnly = true
                            selectedHeight = -1
                        },
                        label = { Text("仅音频 · m4a") }
                    )
                    heights.forEachIndexed { index, option ->
                        FilterChip(
                            selected = !selectedAudioOnly && selectedHeight == index,
                            onClick = {
                                selectedAudioOnly = false
                                selectedHeight = index
                            },
                            label = { Text(option.label) }
                        )
                    }
                }
                ChoiceDropdown(
                    title = "音频码率",
                    options = audioOptions,
                    selectedIndex = selectedAudio,
                    onSelectedIndexChange = { selectedAudio = it }
                )
                if (!selectedAudioOnly) {
                    ChoiceDropdown(
                        title = "字幕",
                        options = subtitleOptions,
                        selectedIndex = selectedSubtitle,
                        onSelectedIndexChange = { selectedSubtitle = it }
                    )
                } else {
                    Text("仅下载音频并转换为 m4a；不会下载字幕。", style = MaterialTheme.typography.labelSmall)
                }
                if (!ffmpegEnabled) {
                    Text(
                        "Full 版可合并分离的视频和音频流。",
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
                        text = if (result.isPlaylist) "选择列表视频" else "视频",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (result.isPlaylist) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                enabled = canChooseVideos,
                                onClick = { selectedUrls = state.entries.map { it.url }.toSet() }
                            ) {
                                Text("全选")
                            }
                            TextButton(enabled = canChooseVideos, onClick = { selectedUrls = emptySet() }) { Text("清空") }
                        }
                    }
                }
                Text(
                    text = if (result.isPlaylist && !canChooseVideos) "请先选择视频清晰度或仅音频，再选择音频码率并勾选条目。"
                    else if (result.isPlaylist) "已选择 ${selectedUrls.size}/${state.entries.size}"
                    else if (selectedAudioOnly) "将只下载音频并转换保存为 .m4a。"
                    else "预估大小仅在 yt-dlp 提供文件大小时显示；实际以下载完成文件为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(state.entries, key = { it.url }) { entry ->
                        val estimated = YoutubeMediaResolver.estimatedSize(
                            entry = entry,
                            targetHeight = targetHeight ?: 2160,
                            audioCeiling = audioCeiling,
                            ffmpegEnabled = ffmpegEnabled,
                            audioOnly = selectedAudioOnly
                        )
                        YoutubeEntryRow(
                            entry = entry,
                            estimatedSize = estimated,
                            audioOnly = selectedAudioOnly,
                            checked = entry.url in selectedUrls,
                            enabled = result.isPlaylist && canChooseVideos,
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
                enabled = canStart,
                onClick = {
                    onDownload(
                        YoutubeMediaResolver.buildSpecs(
                            result = result,
                            selectedUrls = selectedUrls,
                            targetHeight = targetHeight,
                            audioCeiling = audioCeiling,
                            ffmpegEnabled = ffmpegEnabled,
                            subtitleLanguageOverride = subtitleOptions[selectedSubtitle].value,
                            audioOnly = selectedAudioOnly
                        )
                    )
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
    estimatedSize: Long?,
    audioOnly: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val bestHeight = entry.formats.mapNotNull { it.height }.maxOrNull() ?: 0
    val subtitles = entry.allSubtitleLanguages.distinct()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (enabled) {
            Checkbox(checked = checked, enabled = true, onCheckedChange = onCheckedChange)
        }
        Column(modifier = Modifier.padding(top = 11.dp, end = 4.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (audioOnly) append("仅音频 · m4a")
                    else if (bestHeight > 0) append("最高 ${bestHeight}p")
                    else append("下载时匹配所选清晰度")
                    append(" · ")
                    append(
                        when {
                            subtitles.isNotEmpty() -> "字幕：${subtitles.joinToString(", ")}"
                            entry.formats.isEmpty() -> "下载时自动检测字幕（无字幕会跳过）"
                            else -> "未发现字幕（会跳过）"
                        }
                    )
                    estimatedSize?.let { append(" · 约 ${formatSize(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private data class HeightOption(val label: String, val height: Int)
private data class ChoiceOption<T>(val label: String, val value: T)

private class PickerState(result: YoutubeResolveResult) {
    val entries: List<YoutubeMediaEntry> = result.entries
    val audioBitrates: List<Int> = entries
        .flatMap { entry -> entry.formats.filter { format ->
            !format.acodec.isNullOrBlank() && !format.acodec.equals("none", true) &&
                (format.vcodec.isNullOrBlank() || format.vcodec.equals("none", true))
        }.mapNotNull { it.abr?.toInt() } }
        .filter { it > 0 }
        .distinct()
    val subtitleLanguages: List<String> = entries
        .flatMap { it.allSubtitleLanguages }
        .distinct()
        .sortedWith(compareBy<String> { !it.startsWith("zh", ignoreCase = true) }
            .thenBy { !it.startsWith("en", ignoreCase = true) }
            .thenBy { it })
}

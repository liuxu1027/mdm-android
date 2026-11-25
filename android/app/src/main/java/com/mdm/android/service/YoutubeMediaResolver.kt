package com.mdm.android.service

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A format exposed by yt-dlp for one YouTube entry. */
data class YoutubeMediaFormat(
    val formatId: String,
    val ext: String?,
    val height: Int?,
    val width: Int?,
    val vcodec: String?,
    val acodec: String?,
    val abr: Double?,
    val tbr: Double?,
    val formatNote: String?,
    val fileSize: Long?,
    val approximateFileSize: Long?
) {
    val estimatedSize: Long? get() = fileSize ?: approximateFileSize
}

data class YoutubeMediaEntry(
    val id: String,
    val title: String,
    val url: String,
    val durationSeconds: Double?,
    val formats: List<YoutubeMediaFormat>,
    val subtitleLanguages: List<String>,
    val automaticSubtitleLanguages: List<String>
) {
    val allSubtitleLanguages: List<String>
        get() = (subtitleLanguages + automaticSubtitleLanguages).distinct()
}

data class YoutubeResolveResult(
    val title: String,
    val isPlaylist: Boolean,
    val entries: List<YoutubeMediaEntry>
)

data class YoutubeDownloadSpec(
    val url: String,
    val title: String,
    val formatSelector: String,
    val subtitleLanguage: String?
)

/** Resolves yt-dlp metadata and turns a user's selection into a download specification. */
object YoutubeMediaResolver {
    val qualityHeights = listOf(360, 480, 720, 1080, 1440, 2160)
    val audioBitrateChoices = listOf(48, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)

    suspend fun resolve(context: Context, url: String): YoutubeResolveResult = withContext(Dispatchers.IO) {
        initRuntime(context)
        val cookieFile = YoutubeCookieStore.createTemporaryCookieFile(context.applicationContext)
        try {
            val isPlaylistUrl = url.contains("list=", ignoreCase = true) ||
                url.contains("/playlist", ignoreCase = true)
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--skip-download")
                addOption("--no-warnings")
                addOption("--ignore-errors")
                cookieFile?.let { addOption("--cookies", it.absolutePath) }
                // Playlist entries need only their flat IDs/titles here; resolving every format
                // individually made large lists take minutes and is unnecessary for selection.
                if (isPlaylistUrl) addOption("--flat-playlist")
            }
            val response = YoutubeDL.getInstance().execute(request)
            val output = response.out.orEmpty()
            check(output.isNotBlank()) { "yt-dlp 未返回解析信息" }
            val result = parseResult(JSONObject(output), url)
            check(result.entries.isNotEmpty()) { "解析结果中没有可下载的视频" }
            result
        } finally {
            YoutubeCookieStore.deleteTemporaryCookieFile(cookieFile)
        }
    }

    fun directSpec(
        url: String,
        requestedName: String?,
        targetHeight: Int,
        audioCeiling: Int?,
        ffmpegEnabled: Boolean
    ): YoutubeDownloadSpec = YoutubeDownloadSpec(
        url = url,
        title = requestedName?.trim().orEmpty(),
        formatSelector = formatSelector(targetHeight, audioCeiling, ffmpegEnabled),
        // Without a metadata pass, let yt-dlp try every manual/automatic caption language.
        // Caption retrieval runs after the video and is non-fatal when no captions exist.
        subtitleLanguage = "all"
    )

    fun buildSpecs(
        result: YoutubeResolveResult,
        selectedUrls: Set<String>,
        targetHeight: Int?,
        audioCeiling: Int?,
        ffmpegEnabled: Boolean,
        subtitleLanguageOverride: String? = null
    ): List<YoutubeDownloadSpec> {
        return result.entries.filter { selectedUrls.isEmpty() || it.url in selectedUrls }.map { entry ->
            YoutubeDownloadSpec(
                url = entry.url,
                title = entry.title,
                formatSelector = formatSelector(targetHeight, audioCeiling, ffmpegEnabled),
                subtitleLanguage = chooseSubtitle(entry, subtitleLanguageOverride)
            )
        }
    }

    /** Best available estimate for the selected video/audio tiers, when yt-dlp exposed sizes. */
    fun estimatedSize(
        entry: YoutubeMediaEntry,
        targetHeight: Int?,
        audioCeiling: Int?,
        ffmpegEnabled: Boolean
    ): Long? {
        if (entry.formats.isEmpty()) return null
        val videoFormats = entry.formats.filter { isVideo(it) && (it.height ?: 0) > 0 }
        val target = targetHeight ?: Int.MAX_VALUE
        val video = videoFormats.filter { (it.height ?: 0) <= target }
            .maxWithOrNull(formatComparator)
            ?: videoFormats.minWithOrNull(formatComparator)
            ?: return null
        val progressive = video.takeIf { hasAudio(it) }
        if (progressive != null) return progressive.estimatedSize
        if (!ffmpegEnabled) return null
        val audio = entry.formats.filter(::isAudio)
            .filter { audioCeiling == null || (it.abr ?: it.tbr ?: 0.0) <= audioCeiling }
            .maxWithOrNull(audioComparator)
            ?: entry.formats.filter(::isAudio).minWithOrNull(audioComparator)
        val videoSize = video.estimatedSize
        val audioSize = audio?.estimatedSize
        return when {
            videoSize != null && audioSize != null -> videoSize + audioSize
            videoSize != null -> videoSize
            else -> audioSize
        }
    }

    private fun formatSelector(targetHeight: Int?, audioCeiling: Int?, ffmpegEnabled: Boolean): String {
        val cap = targetHeight ?: 2160
        val steps = qualityHeights.filter { it <= cap }.asReversed().ifEmpty { listOf(cap) }
        val audioFilter = audioCeiling?.let { "[abr<=$it]" }.orEmpty()
        val choices = mutableListOf<String>()
        steps.forEach { height ->
            if (ffmpegEnabled) {
                choices += "bestvideo[height<=$height]+bestaudio$audioFilter"
                // If no stream fits the chosen audio cap, keep the video and use the next
                // available audio bitrate rather than failing the whole download.
                if (audioCeiling != null) choices += "bestvideo[height<=$height]+bestaudio"
            }
            choices += "best[height<=$height]"
        }
        return choices.distinct().joinToString("/")
    }

    private fun parseResult(root: JSONObject, sourceUrl: String): YoutubeResolveResult {
        val entriesNode = root.optJSONArray("entries")
        val playlist = entriesNode != null
        val nodes = if (entriesNode != null) {
            (0 until entriesNode.length()).mapNotNull { entriesNode.optJSONObject(it) }
        } else {
            listOf(root)
        }
        val entries = nodes.mapNotNull(::parseEntry)
        return YoutubeResolveResult(
            title = root.text("title") ?: entries.firstOrNull()?.title ?: sourceUrl,
            isPlaylist = playlist,
            entries = entries
        )
    }

    private fun parseEntry(node: JSONObject): YoutubeMediaEntry? {
        val id = node.text("id") ?: return null
        val rawUrl = node.text("webpage_url") ?: node.text("original_url") ?: node.text("url")
        val url = rawUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: "https://www.youtube.com/watch?v=$id"
        val formatsArray = node.optJSONArray("formats")
        val formats = if (formatsArray == null) emptyList() else {
            (0 until formatsArray.length()).mapNotNull { index ->
                val format = formatsArray.optJSONObject(index) ?: return@mapNotNull null
                val formatId = format.text("format_id") ?: return@mapNotNull null
                YoutubeMediaFormat(
                    formatId = formatId,
                    ext = format.text("ext"),
                    height = format.int("height"),
                    width = format.int("width"),
                    vcodec = format.text("vcodec"),
                    acodec = format.text("acodec"),
                    abr = format.decimal("abr"),
                    tbr = format.decimal("tbr"),
                    formatNote = format.text("format_note"),
                    fileSize = format.longValue("filesize"),
                    approximateFileSize = format.longValue("filesize_approx")
                )
            }
        }
        return YoutubeMediaEntry(
            id = id,
            title = node.text("title") ?: id,
            url = url,
            durationSeconds = node.decimal("duration"),
            formats = formats,
            subtitleLanguages = languageKeys(node.optJSONObject("subtitles")),
            automaticSubtitleLanguages = languageKeys(node.optJSONObject("automatic_captions"))
        )
    }

    private fun chooseSubtitle(entry: YoutubeMediaEntry, override: String?): String? {
        val all = entry.allSubtitleLanguages
        if (override == "") return null
        if (override != null) return all.firstOrNull { it.equals(override, ignoreCase = true) }
        return all.firstOrNull { it.startsWith("zh", ignoreCase = true) }
            ?: all.firstOrNull { it.startsWith("en", ignoreCase = true) }
            ?: all.firstOrNull()
            ?: "all"
    }

    private fun isVideo(format: YoutubeMediaFormat): Boolean =
        !format.vcodec.isNullOrBlank() && !format.vcodec.equals("none", true)

    private fun isAudio(format: YoutubeMediaFormat): Boolean =
        !format.acodec.isNullOrBlank() && !format.acodec.equals("none", true) && !isVideo(format)

    private fun hasAudio(format: YoutubeMediaFormat): Boolean =
        !format.acodec.isNullOrBlank() && !format.acodec.equals("none", true)

    private fun languageKeys(node: JSONObject?): List<String> =
        node?.keys()?.asSequence()?.toList().orEmpty()

    private fun initRuntime(context: Context) {
        YoutubeRuntime.init(context.applicationContext)
    }

    private fun JSONObject.text(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.int(name: String): Int? = (opt(name) as? Number)?.toInt()

    private fun JSONObject.longValue(name: String): Long? = when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun JSONObject.decimal(name: String): Double? = (opt(name) as? Number)?.toDouble()

    private val formatComparator = compareBy<YoutubeMediaFormat> { it.height ?: -1 }
        .thenBy { it.width ?: -1 }
        .thenBy { it.tbr ?: 0.0 }

    private val audioComparator = compareBy<YoutubeMediaFormat> { it.abr ?: it.tbr ?: 0.0 }
}

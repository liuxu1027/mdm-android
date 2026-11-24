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
    val formatNote: String?
)

data class YoutubeMediaEntry(
    val id: String,
    val title: String,
    val url: String,
    val durationSeconds: Double?,
    val formats: List<YoutubeMediaFormat>,
    val subtitleLanguages: List<String>
)

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
    suspend fun resolve(context: Context, url: String): YoutubeResolveResult = withContext(Dispatchers.IO) {
        initRuntime(context)
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-warnings")
            addOption("--ignore-errors")
            // Deliberately do not add --no-playlist: yt-dlp must return playlist entries.
        }
        val response = YoutubeDL.getInstance().execute(request)
        val output = response.out.orEmpty()
        check(output.isNotBlank()) { "yt-dlp 未返回解析信息" }
        val result = parseResult(JSONObject(output), url)
        check(result.entries.isNotEmpty()) { "解析结果中没有可下载的视频" }
        result
    }

    fun buildSpecs(
        result: YoutubeResolveResult,
        selectedUrls: Set<String>,
        targetHeight: Int?,
        audioCeiling: Int?,
        ffmpegEnabled: Boolean,
        subtitleLanguageOverride: String? = null
    ): List<YoutubeDownloadSpec> {
        return result.entries.filter { selectedUrls.isEmpty() || it.url in selectedUrls }.map { entry ->
            val videoFormats = entry.formats.filter { isVideo(it) && (it.height ?: 0) > 0 }
            val video = videoFormats.let { formats ->
                if (formats.isEmpty()) null else chooseVideo(formats, targetHeight)
            }
            val progressive = entry.formats.filter { isVideo(it) && hasAudio(it) && (it.height ?: 0) > 0 }
            val audio = entry.formats.filter(::isAudio)
            val fullVideo = if (ffmpegEnabled) {
                videoFormats.filter { it.acodec.equals("none", ignoreCase = true) }
                    .let { formats -> if (formats.isEmpty()) video else chooseVideo(formats, targetHeight) }
            } else video
            val selector = if (ffmpegEnabled && fullVideo != null && audio.isNotEmpty()) {
                val chosenAudio = chooseAudio(audio, audioCeiling)
                if (chosenAudio != null && !hasAudio(fullVideo)) {
                    "${fullVideo.formatId}+${chosenAudio.formatId}"
                } else if (hasAudio(fullVideo)) {
                    fullVideo.formatId
                } else {
                    fallbackSelector(targetHeight, true)
                }
            } else {
                chooseProgressive(progressive, targetHeight, audioCeiling)?.formatId
                    ?: fallbackSelector(targetHeight, false)
            }
            YoutubeDownloadSpec(
                url = entry.url,
                title = entry.title,
                formatSelector = selector,
                subtitleLanguage = chooseSubtitle(entry, subtitleLanguageOverride)
            )
        }
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
                    formatNote = format.text("format_note")
                )
            }
        }
        return YoutubeMediaEntry(
            id = id,
            title = node.text("title") ?: id,
            url = url,
            durationSeconds = node.decimal("duration"),
            formats = formats,
            subtitleLanguages = languageKeys(node.optJSONObject("subtitles"))
        )
    }

    private fun chooseVideo(formats: List<YoutubeMediaFormat>, targetHeight: Int?): YoutubeMediaFormat {
        val ordered = formats.sortedWith(formatComparator)
        if (targetHeight == null) return ordered.maxWithOrNull(formatComparator) ?: ordered.first()
        return ordered.filter { (it.height ?: Int.MAX_VALUE) <= targetHeight }
            .maxWithOrNull(formatComparator) ?: ordered.minWithOrNull(formatComparator)!!
    }

    private fun chooseAudio(formats: List<YoutubeMediaFormat>, ceiling: Int?): YoutubeMediaFormat {
        val ordered = formats.sortedWith(audioComparator)
        if (ceiling == null) return ordered.maxWithOrNull(audioComparator) ?: ordered.first()
        return ordered.filter { (it.abr ?: it.tbr ?: 0.0) <= ceiling }
            .maxWithOrNull(audioComparator) ?: ordered.minWithOrNull(audioComparator)!!
    }

    private fun chooseProgressive(
        formats: List<YoutubeMediaFormat>,
        targetHeight: Int?,
        audioCeiling: Int?
    ): YoutubeMediaFormat? {
        if (formats.isEmpty()) return null
        val capped = audioCeiling?.let { ceiling ->
            formats.filter { (it.abr ?: it.tbr ?: 0.0) <= ceiling }
        }.orEmpty().ifEmpty { formats }
        return chooseVideo(capped, targetHeight)
    }

    private fun chooseSubtitle(entry: YoutubeMediaEntry, override: String?): String? {
        val all = entry.subtitleLanguages
        if (override == "") return null
        if (override != null) return all.firstOrNull { it.equals(override, ignoreCase = true) }
        return all.firstOrNull { it.startsWith("zh", ignoreCase = true) }
            ?: all.firstOrNull { it.startsWith("en", ignoreCase = true) }
            ?: all.firstOrNull()
    }

    private fun fallbackSelector(targetHeight: Int?, withAudioMerge: Boolean): String {
        val video = targetHeight?.let { "bestvideo[height<=$it]" } ?: "bestvideo"
        if (withAudioMerge) return "$video+bestaudio/best"
        return if (targetHeight == null) "best" else "best[height<=$targetHeight]/best"
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

    private fun JSONObject.decimal(name: String): Double? = (opt(name) as? Number)?.toDouble()

    private val formatComparator = compareBy<YoutubeMediaFormat> { it.height ?: -1 }
        .thenBy { it.width ?: -1 }
        .thenBy { it.tbr ?: 0.0 }

    private val audioComparator = compareBy<YoutubeMediaFormat> { it.abr ?: it.tbr ?: 0.0 }
}

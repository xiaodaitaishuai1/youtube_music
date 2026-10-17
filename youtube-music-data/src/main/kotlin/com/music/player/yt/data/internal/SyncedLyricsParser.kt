package com.music.player.yt.data.internal

import com.music.player.yt.data.SyncedLyricLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal data class CaptionTrack(
    val baseUrl: String,
    val languageCode: String,
    val isAutomatic: Boolean,
)

internal object SyncedLyricsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun selectTrack(
        playerResponse: JsonObject,
        preferredLanguageCodes: List<String>,
    ): CaptionTrack? {
        val tracks = playerResponse
            .objectAt("captions", "playerCaptionsTracklistRenderer")
            ?.arrayAt("captionTracks")
            .orEmpty()
            .mapNotNull(::captionTrack)

        for (languageCode in preferredLanguageCodes) {
            tracks.firstOrNull {
                !it.isAutomatic && it.languageCode.equals(languageCode, ignoreCase = true)
            }?.let { return it }
        }
        for (languageCode in preferredLanguageCodes) {
            tracks.firstOrNull {
                it.isAutomatic && it.languageCode.equals(languageCode, ignoreCase = true)
            }?.let { return it }
        }
        return tracks.firstOrNull { !it.isAutomatic } ?: tracks.firstOrNull()
    }

    fun parseLines(body: String): List<SyncedLyricLine> {
        val root = json.parseToJsonElement(body).asObjectOrNull()
            ?: throw IllegalArgumentException("Caption response was not a JSON object")
        return root.arrayAt("events")
            .mapIndexedNotNull { index, element ->
                val event = element.asObjectOrNull() ?: return@mapIndexedNotNull null
                val startTimeMs = event.longAt("tStartMs") ?: return@mapIndexedNotNull null
                val text = event.arrayAt("segs").joinToString("") { segment ->
                    segment.asObjectOrNull()?.stringAt("utf8").orEmpty()
                }.trim()
                if (text.isEmpty()) null else IndexedLine(index, SyncedLyricLine(startTimeMs, text))
            }
            .sortedWith(compareBy<IndexedLine> { it.line.startTimeMs }.thenBy { it.index })
            .map(IndexedLine::line)
    }

    private fun captionTrack(element: kotlinx.serialization.json.JsonElement): CaptionTrack? {
        val track = element.asObjectOrNull() ?: return null
        val baseUrl = track.stringAt("baseUrl")?.takeIf(String::isNotBlank) ?: return null
        val languageCode = track.stringAt("languageCode")?.takeIf(String::isNotBlank) ?: return null
        return CaptionTrack(
            baseUrl = baseUrl,
            languageCode = languageCode,
            isAutomatic = track.stringAt("kind").equals("asr", ignoreCase = true),
        )
    }

    private data class IndexedLine(
        val index: Int,
        val line: SyncedLyricLine,
    )
}

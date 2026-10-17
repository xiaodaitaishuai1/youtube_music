package com.music.player.yt.data.internal

import com.music.player.yt.data.LyricsSource
import com.music.player.yt.data.ResolvedLyrics
import com.music.player.yt.data.SyncedLyricLine
import com.music.player.yt.data.YoutubeDataResult
import java.io.IOException
import java.util.Base64
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal sealed interface LyricsProviderAttempt {
    data class Found(val lyrics: ResolvedLyrics) : LyricsProviderAttempt
    data class Error(val error: YoutubeDataResult<Nothing>) : LyricsProviderAttempt
    data object Unavailable : LyricsProviderAttempt
}

internal data class LyricsSearchTerms(
    val title: String,
    val artist: String,
)

internal object LyricsSearchNormalizer {
    fun normalize(title: String, artist: String): LyricsSearchTerms = LyricsSearchTerms(
        title = clean(title.substringBefore(" - ").substringBefore(" | ").trim()),
        artist = clean(artist.substringBefore(',').substringBefore('&').substringBefore(" - ").trim()),
    )

    fun clean(text: String): String {
        var cleaned = text
        val patterns = listOf(
            "\\(.*?official.*?\\)", "\\[.*?official.*?]",
            "\\(.*?video.*?\\)", "\\[.*?video.*?]",
            "\\(.*?audio.*?\\)", "\\[.*?audio.*?]",
            "\\(.*?lyrics.*?\\)", "\\[.*?lyrics.*?]",
            "\\(.*?mv.*?\\)", "\\[.*?mv.*?]",
            "\\(.*?4k.*?\\)", "\\[.*?4k.*?]",
            "\\(.*?hd.*?\\)", "\\[.*?hd.*?]",
            "\\(.*?remastered.*?\\)", "\\[.*?remastered.*?]",
            "\\(.*?\\b\\d{4}\\b.*?\\)", "\\[.*?\\b\\d{4}\\b.*?]",
        )
        patterns.forEach { cleaned = cleaned.replace(Regex(it, RegexOption.IGNORE_CASE), "") }
        cleaned = cleaned.replace(
            Regex("\\(?\\b(ft\\.|feat\\.|featuring|prod\\.|produced by)\\b.*", RegexOption.IGNORE_CASE),
            "",
        )
        return cleaned.replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$"), "").trim()
    }

    fun isChinese(title: String, artist: String): Boolean =
        (title + artist).any { it.code in 0x4E00..0x9FFF }
}

internal class FallbackLyricsResolver(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun lrcLib(terms: LyricsSearchTerms, durationMs: Long): LyricsProviderAttempt {
        val title = terms.title.trim()
        val artist = terms.artist.trim()
        val durationSeconds = (durationMs.coerceAtLeast(0) / 1_000L).toInt()
        val exactUrl = LRCLIB_BASE_URL.newBuilder()
            .addPathSegment("get")
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .addQueryParameter("duration", durationSeconds.toString())
            .build()
        when (val exact = get(exactUrl, notFoundIsUnavailable = true)) {
            is HttpAttempt.Body -> {
                val parsed = parseLrcLibObject(exact.body)
                if (parsed is LyricsProviderAttempt.Found) return parsed
                if (parsed is LyricsProviderAttempt.Error) return parsed
            }
            is HttpAttempt.Error -> return LyricsProviderAttempt.Error(exact.error)
            HttpAttempt.Unavailable -> Unit
        }

        val searchUrl = LRCLIB_BASE_URL.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", "$artist $title".trim())
            .build()
        return when (val search = get(searchUrl, notFoundIsUnavailable = true)) {
            is HttpAttempt.Body -> parseLrcLibSearch(search.body, durationSeconds)
            is HttpAttempt.Error -> LyricsProviderAttempt.Error(search.error)
            HttpAttempt.Unavailable -> LyricsProviderAttempt.Unavailable
        }
    }

    suspend fun kugou(terms: LyricsSearchTerms, durationMs: Long): LyricsProviderAttempt {
        val title = terms.title.trim()
        val artist = terms.artist.trim()
        val queries = listOf("$artist - $title".trim(), title).distinct()
        var match: JsonObject? = null
        var usedQuery = queries.first()
        for (query in queries) {
            when (val search = get(kugouSearchUrl(query), notFoundIsUnavailable = true, identityEncoding = true)) {
                is HttpAttempt.Body -> {
                    val parsed = parseObject(normalizeKugouSearchBody(search.body))
                        ?: return invalidJson("Kugou search response was invalid JSON")
                    val items = parsed.objectAt("data")?.arrayAt("info").orEmpty().mapNotNull { it.asObjectOrNull() }
                    if (items.isNotEmpty()) {
                        match = selectKugouMatch(items, title, artist)
                        usedQuery = query
                        break
                    }
                }
                is HttpAttempt.Error -> return LyricsProviderAttempt.Error(search.error)
                HttpAttempt.Unavailable -> Unit
            }
        }
        val item = match ?: return LyricsProviderAttempt.Unavailable
        val hash = item.stringAt("hash")?.takeIf(String::isNotBlank)
            ?: item.arrayAt("group").firstNotNullOfOrNull { it.asObjectOrNull()?.stringAt("hash")?.takeIf(String::isNotBlank) }
            ?: return LyricsProviderAttempt.Unavailable
        val duration = item.stringAt("duration")?.toIntOrNull()
            ?: (durationMs.coerceAtLeast(0) / 1_000L).toInt()

        val candidateUrl = KUGOU_LYRICS_BASE_URL.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
            .addQueryParameter("keyword", usedQuery)
            .addQueryParameter("duration", duration.toString())
            .addQueryParameter("hash", hash)
            .build()
        val candidate = when (val response = get(candidateUrl, notFoundIsUnavailable = true)) {
            is HttpAttempt.Body -> parseObject(response.body)
                ?: return invalidJson("Kugou lyric search response was invalid JSON")
            is HttpAttempt.Error -> return LyricsProviderAttempt.Error(response.error)
            HttpAttempt.Unavailable -> return LyricsProviderAttempt.Unavailable
        }
        val firstCandidate = candidate.arrayAt("candidates").firstOrNull()?.asObjectOrNull()
            ?: return LyricsProviderAttempt.Unavailable
        val id = firstCandidate.stringAt("id")?.takeIf(String::isNotBlank)
            ?: return LyricsProviderAttempt.Unavailable
        val accessKey = firstCandidate.stringAt("accesskey")?.takeIf(String::isNotBlank)
            ?: return LyricsProviderAttempt.Unavailable

        val downloadUrl = KUGOU_LYRICS_BASE_URL.newBuilder()
            .addPathSegment("download")
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "pc")
            .addQueryParameter("id", id)
            .addQueryParameter("accesskey", accessKey)
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .build()
        val encoded = when (val response = get(downloadUrl, notFoundIsUnavailable = true)) {
            is HttpAttempt.Body -> parseObject(response.body)?.stringAt("content")
                ?: return invalidJson("Kugou lyric download response was invalid JSON")
            is HttpAttempt.Error -> return LyricsProviderAttempt.Error(response.error)
            HttpAttempt.Unavailable -> return LyricsProviderAttempt.Unavailable
        }?.takeIf(String::isNotBlank) ?: return LyricsProviderAttempt.Unavailable
        val lrc = try {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            return LyricsProviderAttempt.Error(YoutubeDataResult.ParseError("Kugou lyric content was invalid Base64", error))
        }
        val lines = LrcLyricsParser.parse(lrc)
        if (lines.isEmpty()) return LyricsProviderAttempt.Unavailable
        return LyricsProviderAttempt.Found(
            ResolvedLyrics(
                text = lines.joinToString("\n", transform = SyncedLyricLine::text),
                syncedLines = lines,
                source = LyricsSource.KUGOU,
            ),
        )
    }

    private fun parseLrcLibObject(body: String): LyricsProviderAttempt {
        val value = parseObject(body) ?: return invalidJson("LRCLIB response was invalid JSON")
        return lrcLibResult(value)
    }

    private fun parseLrcLibSearch(body: String, durationSeconds: Int): LyricsProviderAttempt {
        val values = try {
            json.parseToJsonElement(body) as? JsonArray
                ?: return invalidStructure("LRCLIB search response was not a JSON array")
        } catch (error: SerializationException) {
            return LyricsProviderAttempt.Error(YoutubeDataResult.ParseError("LRCLIB search response was invalid JSON", error))
        }
        val best = values.mapNotNull { it.asObjectOrNull() }
            .filter { !it.stringAt("syncedLyrics").isNullOrBlank() }
            .minByOrNull { abs((it.stringAt("duration")?.toDoubleOrNull() ?: 0.0) - durationSeconds) }
            ?: return LyricsProviderAttempt.Unavailable
        return lrcLibResult(best)
    }

    private fun lrcLibResult(value: JsonObject): LyricsProviderAttempt {
        val lines = LrcLyricsParser.parse(value.stringAt("syncedLyrics").orEmpty())
        if (lines.isEmpty()) return LyricsProviderAttempt.Unavailable
        return LyricsProviderAttempt.Found(
            ResolvedLyrics(
                text = value.stringAt("plainLyrics")?.takeIf(String::isNotBlank),
                syncedLines = lines,
                source = LyricsSource.LRCLIB,
            ),
        )
    }

    private suspend fun get(
        url: HttpUrl,
        notFoundIsUnavailable: Boolean,
        identityEncoding: Boolean = false,
    ): HttpAttempt = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply {
                if (identityEncoding) header("Accept-Encoding", "identity")
            }.get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> HttpAttempt.Body(body)
                    notFoundIsUnavailable && response.code == 404 -> HttpAttempt.Unavailable
                    else -> HttpAttempt.Error(
                        YoutubeDataResult.UpstreamError(response.code, body.takeIf(String::isNotBlank)),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            HttpAttempt.Error(YoutubeDataResult.NetworkError(error))
        } catch (error: IllegalArgumentException) {
            HttpAttempt.Error(YoutubeDataResult.ParseError("Invalid lyrics provider URL", error))
        }
    }

    private fun parseObject(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun normalizeKugouSearchBody(body: String): String = body.trim()
        .removePrefix("<!--KG_TAG_RES_START-->")
        .removeSuffix("<!--KG_TAG_RES_END-->")
        .trim()

    private fun invalidJson(message: String): LyricsProviderAttempt.Error =
        LyricsProviderAttempt.Error(YoutubeDataResult.ParseError(message))

    private fun invalidStructure(message: String): LyricsProviderAttempt.Error =
        LyricsProviderAttempt.Error(YoutubeDataResult.ParseError(message))

    private fun kugouSearchUrl(query: String): HttpUrl = KUGOU_SEARCH_BASE_URL.newBuilder()
        .addPathSegments("api/v3/search/song")
        .addQueryParameter("keyword", query)
        .addQueryParameter("page", "1")
        .addQueryParameter("pagesize", "20")
        .addQueryParameter("format", "json")
        .addQueryParameter("showtype", "1")
        .addQueryParameter("plat", "0")
        .addQueryParameter("version", "9108")
        .addQueryParameter("with_res_tag", "1")
        .build()

    private fun selectKugouMatch(items: List<JsonObject>, title: String, artist: String): JsonObject =
        items.firstOrNull { item ->
            val song = item.stringAt("songname").orEmpty()
            val singer = item.stringAt("singername").orEmpty()
            matchesEitherDirection(song, title) && matchesEitherDirection(singer, artist)
        } ?: items.firstOrNull { matchesEitherDirection(it.stringAt("songname").orEmpty(), title) }
            ?: items.first()

    private fun matchesEitherDirection(value: String, expected: String): Boolean =
        value.contains(expected, ignoreCase = true) || expected.contains(value, ignoreCase = true)

    private sealed interface HttpAttempt {
        data class Body(val body: String) : HttpAttempt
        data class Error(val error: YoutubeDataResult<Nothing>) : HttpAttempt
        data object Unavailable : HttpAttempt
    }

    private companion object {
        val LRCLIB_BASE_URL = "https://lrclib.net/api/".toHttpUrl()
        val KUGOU_SEARCH_BASE_URL = "https://mobileservice.kugou.com/".toHttpUrl()
        val KUGOU_LYRICS_BASE_URL = "https://lyrics.kugou.com/".toHttpUrl()
    }
}

internal object LrcLyricsParser {
    private val lineRegex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)")

    fun parse(lrc: String): List<SyncedLyricLine> = lrc.lineSequence().mapNotNull { line ->
        val match = lineRegex.find(line) ?: return@mapNotNull null
        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val fraction = match.groupValues[3]
        val milliseconds = fraction.toLongOrNull()?.let { if (fraction.length == 2) it * 10 else it }
            ?: return@mapNotNull null
        val text = match.groupValues[4].trim()
        if (text.isEmpty()) null else SyncedLyricLine(minutes * 60_000 + seconds * 1_000 + milliseconds, text)
    }.sortedBy(SyncedLyricLine::startTimeMs).toList()
}

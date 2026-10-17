package com.music.player.yt.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches client version hints on demand. Hosts decide when to call it and whether to persist results.
 */
public class YoutubeVersionHelper(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val sourceUrl: String = DEFAULT_SOURCE_URL,
) {
    public suspend fun fetchCurrentVersion(): YoutubeVersionResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(sourceUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    YoutubeVersionResult.UpstreamError(response.code, body.takeIf(String::isNotBlank))
                } else {
                    extractVersions(body)?.let(YoutubeVersionResult::Success)
                        ?: YoutubeVersionResult.ParseError("Version source did not contain YTMUSIC or ANDROID versions")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            YoutubeVersionResult.NetworkError(error)
        } catch (error: Exception) {
            YoutubeVersionResult.ParseError("Failed to parse YouTube client versions", error)
        }
    }

    private fun extractVersions(content: String): YoutubeClientVersions? {
        val webRemixVersion = VERSION_PATTERN.find(content, 0)?.takeIf { it.groupValues[1] == "YTMUSIC" }?.groupValues?.get(2)
            ?: VERSION_PATTERN.findAll(content).firstOrNull { it.groupValues[1] == "YTMUSIC" }?.groupValues?.get(2)
        val androidVersion = VERSION_PATTERN.findAll(content).firstOrNull { it.groupValues[1] == "ANDROID" }?.groupValues?.get(2)
        return YoutubeClientVersions(webRemixVersion, androidVersion)
            .takeIf { it.webRemixVersion != null || it.androidVersion != null }
    }

    private companion object {
        private const val DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/LuanRT/YouTube.js/main/src/utils/Constants.ts"
        private val VERSION_PATTERN = Regex(
            """\b(YTMUSIC|ANDROID)\s*[:=]\s*\{[^}]*?\bVERSION\s*:\s*['"]([^'"]+)['"]""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}

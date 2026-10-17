package com.music.player.yt.data.internal

import com.music.player.yt.data.YoutubeDataResult
import com.music.player.yt.data.YoutubeMusicLocale
import com.music.player.yt.data.YoutubeMusicSession
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class InnerTubeTransport(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val locale: YoutubeMusicLocale,
    private val webClientProvider: () -> InnerTubeClientConfig = { InnerTubeClients.webRemix() },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun anonymousSession(): YoutubeDataResult<YoutubeMusicSession> {
        return withContext(Dispatchers.IO) {
            try {
                val client = webClientProvider()
                val request = Request.Builder()
                    .url(
                        baseUrl.toHttpUrl().newBuilder()
                            .addPathSegments("sw.js_data")
                            .addQueryParameter("key", client.apiKey)
                            .build(),
                    )
                    .applySonicTubeHeaders(client)
                    .header("Cache-Control", "no-cache, no-store")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        !response.isSuccessful -> YoutubeDataResult.UpstreamError(response.code, body.takeIf(String::isNotBlank))
                        extractVisitorData(body) == null -> YoutubeDataResult.ParseError("Anonymous session response did not contain visitor data")
                        else -> {
                            val session = YoutubeMusicSession(checkNotNull(extractVisitorData(body)))
                            YoutubeDataResult.Success(session, session)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                YoutubeDataResult.NetworkError(error)
            } catch (error: Exception) {
                YoutubeDataResult.ParseError("Failed to parse anonymous session response", error)
            }
        }
    }

    suspend fun post(
        endpoint: String,
        session: YoutubeMusicSession,
        payload: JsonObject,
        continuation: String? = null,
        client: InnerTubeClientConfig = webClientProvider(),
    ): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                val urlBuilder = baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("youtubei/v1/$endpoint")
                    .addQueryParameter("key", client.apiKey)
                if (continuation != null) {
                    urlBuilder.addQueryParameter("continuation", continuation)
                    urlBuilder.addQueryParameter("ctoken", continuation)
                    if (endpoint == "browse") urlBuilder.addQueryParameter("type", "next")
                }
                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .post(
                        json.encodeToString(JsonObject.serializer(), payload)
                            .encodeToByteArray()
                            .toRequestBody(JSON_MEDIA_TYPE),
                    )
                    .applySonicTubeHeaders(client)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        TransportResult.Error(YoutubeDataResult.UpstreamError(response.code, body.takeIf(String::isNotBlank)))
                    } else {
                        try {
                            val parsed = json.parseToJsonElement(body).asObjectOrNull()
                            if (parsed == null) {
                                TransportResult.Error(YoutubeDataResult.ParseError("InnerTube response was not a JSON object"))
                            } else {
                                TransportResult.Success(parsed, updatedSession(parsed, session))
                            }
                        } catch (error: Exception) {
                            TransportResult.Error(YoutubeDataResult.ParseError("InnerTube response was invalid JSON", error))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                TransportResult.Error(YoutubeDataResult.NetworkError(error))
            } catch (error: IllegalArgumentException) {
                TransportResult.Error(YoutubeDataResult.ParseError("Invalid InnerTube base URL", error))
            }
        }
    }

    suspend fun caption(baseUrl: String): CaptionTransportResult = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.toHttpUrl().newBuilder()
                .setQueryParameter("fmt", "json3")
                .build()
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    CaptionTransportResult.Success(body)
                } else {
                    CaptionTransportResult.Error(
                        YoutubeDataResult.UpstreamError(response.code, body.takeIf(String::isNotBlank)),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            CaptionTransportResult.Error(YoutubeDataResult.NetworkError(error))
        } catch (error: IllegalArgumentException) {
            CaptionTransportResult.Error(YoutubeDataResult.ParseError("Invalid caption track URL", error))
        }
    }

    fun browsePayload(session: YoutubeMusicSession, browseId: String? = null, params: String? = null): JsonObject =
        buildJsonObject {
            put("context", context(session, webClientProvider()))
            browseId?.let { put("browseId", it) }
            params?.let { put("params", it) }
        }

    fun searchPayload(session: YoutubeMusicSession, query: String, params: String? = null): JsonObject =
        buildJsonObject {
            put("context", context(session, webClientProvider()))
            put("query", query)
            params?.let { put("params", it) }
        }

    fun suggestionsPayload(session: YoutubeMusicSession, input: String): JsonObject =
        buildJsonObject {
            put("context", context(session, webClientProvider()))
            put("input", input)
        }

    fun nextPayload(session: YoutubeMusicSession, videoId: String): JsonObject =
        buildJsonObject {
            put("context", context(session, webClientProvider()))
            put("videoId", videoId)
        }

    fun playerPayload(
        session: YoutubeMusicSession,
        videoId: String,
        playlistId: String?,
        client: InnerTubeClientConfig,
    ): JsonObject = buildJsonObject {
        put("context", context(session, client))
        put("videoId", videoId)
        playlistId?.let { put("playlistId", it) }
        put("contentCheckOk", true)
        put("platform", "DESKTOP")
    }

    suspend fun pipedStreams(
        pipedBaseUrl: String,
        videoId: String,
    ): PipedTransportResult = withContext(Dispatchers.IO) {
        try {
            val url = pipedBaseUrl.toHttpUrl().newBuilder().addPathSegments("streams/$videoId").build()
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> PipedTransportResult.Error(
                        YoutubeDataResult.UpstreamError(response.code, body.takeIf(String::isNotBlank)),
                    )
                    else -> try {
                        val root = json.parseToJsonElement(body).asObjectOrNull()
                        val streams = root?.arrayAt("audioStreams").orEmpty().mapNotNull { entry ->
                            val item = entry.asObjectOrNull() ?: return@mapNotNull null
                            val itag = item.stringAt("itag")?.toIntOrNull() ?: return@mapNotNull null
                            val streamUrl = item.stringAt("url") ?: return@mapNotNull null
                            val bitrate = item.stringAt("bitrate")?.toIntOrNull() ?: return@mapNotNull null
                            PipedAudioStream(itag, streamUrl, bitrate)
                        }
                        PipedTransportResult.Success(streams)
                    } catch (error: Exception) {
                        PipedTransportResult.Error(YoutubeDataResult.ParseError("Piped response was invalid JSON", error))
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            PipedTransportResult.Error(YoutubeDataResult.NetworkError(error))
        } catch (error: IllegalArgumentException) {
            PipedTransportResult.Error(YoutubeDataResult.ParseError("Invalid Piped endpoint", error))
        }
    }

    private fun context(session: YoutubeMusicSession, client: InnerTubeClientConfig): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", client.name)
            put("clientVersion", client.version)
            put("hl", locale.hl)
            put("gl", locale.gl)
            put("visitorData", session.visitorData)
        })
    }

    private fun updatedSession(response: JsonObject, original: YoutubeMusicSession): YoutubeMusicSession {
        val visitorData = response.objectAt("responseContext")?.stringAt("visitorData")
        return visitorData?.let(::YoutubeMusicSession) ?: original
    }

    private fun Request.Builder.applySonicTubeHeaders(client: InnerTubeClientConfig): Request.Builder = apply {
        header("Content-Type", "application/json")
        header("User-Agent", client.userAgent)
        header("host", "music.youtube.com")
        header("Origin", "https://music.youtube.com")
        header("x-origin", "https://music.youtube.com")
        header("X-YouTube-Client-Name", "67")
        header("X-YouTube-Client-Version", client.version)
        header("platform", "DESKTOP")
        client.referer?.let { header("Referer", it) }
    }

    private fun extractVisitorData(body: String): String? {
        val normalized = body.removePrefix(")]}'").trim()
        val root = json.parseToJsonElement(normalized)
        return root.findString { it.startsWith("Cgt") }
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal sealed interface TransportResult {
    data class Success(val response: JsonObject, val updatedSession: YoutubeMusicSession) : TransportResult
    data class Error(val error: YoutubeDataResult<Nothing>) : TransportResult
}

internal sealed interface CaptionTransportResult {
    data class Success(val body: String) : CaptionTransportResult
    data class Error(val error: YoutubeDataResult<Nothing>) : CaptionTransportResult
}

internal data class PipedAudioStream(
    val itag: Int,
    val url: String,
    val bitrate: Int,
)

internal sealed interface PipedTransportResult {
    data class Success(val streams: List<PipedAudioStream>) : PipedTransportResult
    data class Error(val error: YoutubeDataResult<Nothing>) : PipedTransportResult
}

internal data class InnerTubeClientConfig(
    val name: String,
    val version: String,
    val apiKey: String,
    val userAgent: String,
    val referer: String? = null,
)

internal object InnerTubeClients {
    const val DEFAULT_WEB_REMIX_VERSION: String = "1.20250716.03.00"
    const val DEFAULT_ANDROID_VERSION: String = "21.09.35"

    private const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.157 Safari/537.36"
    private const val ANDROID_USER_AGENT = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/65.0.3325.181 Mobile Safari/537.36"
    private const val IOS_USER_AGENT = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)"
    private const val TV_USER_AGENT = "Mozilla/5.0 (PlayStation 4 5.55) AppleWebKit/601.2 (KHTML, like Gecko)"

    fun webRemix(version: String = DEFAULT_WEB_REMIX_VERSION): InnerTubeClientConfig = InnerTubeClientConfig(
        name = "WEB_REMIX",
        version = version,
        apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
        userAgent = WEB_USER_AGENT,
        referer = "https://music.youtube.com/",
    )

    fun android(version: String): InnerTubeClientConfig = InnerTubeClientConfig(
        name = "ANDROID",
        version = version,
        apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
        userAgent = ANDROID_USER_AGENT,
    )

    fun tvHtml5(): InnerTubeClientConfig = InnerTubeClientConfig(
        name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        version = "2.0",
        apiKey = "AIzaSyDCU8hByM-4DrUqRUYnGn-3llEO78bcxq8",
        userAgent = TV_USER_AGENT,
    )

    fun iosUserAgent(): String = IOS_USER_AGENT
}

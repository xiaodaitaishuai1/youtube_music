package com.music.player.yt.data.internal

import com.music.player.yt.data.StreamInfo
import com.music.player.yt.data.StreamQuality
import com.music.player.yt.data.StreamSource
import com.music.player.yt.data.YoutubeClientVersions
import com.music.player.yt.data.YoutubeDataResult
import com.music.player.yt.data.YoutubeMusicSession
import com.music.player.yt.data.YoutubeStreamOptions
import kotlinx.serialization.json.JsonObject

internal class InnerTubeStreamResolver(
    private val transport: InnerTubeTransport,
    private val versionsProvider: () -> YoutubeClientVersions,
    private val options: YoutubeStreamOptions,
) {
    suspend fun resolve(
        session: YoutubeMusicSession,
        videoId: String,
        playlistId: String?,
        quality: StreamQuality,
        isNetworkMetered: Boolean,
    ): YoutubeDataResult<StreamInfo> {
        val versions = versionsProvider()
        val androidClient = InnerTubeClients.android(checkNotNull(versions.androidVersion))
        var lastFailure: YoutubeDataResult<Nothing>? = null
        var unavailableReason: String? = null
        var latestSession = session

        repeat(ANDROID_ATTEMPTS) {
            when (val result = requestPlayer(latestSession, videoId, playlistId, androidClient)) {
                is PlayerAttempt.Success -> {
                    if (result.player.status == PLAYABLE_STATUS) {
                        selectFormat(result.player, quality, isNetworkMetered)?.url?.let { url ->
                            return YoutubeDataResult.Success(
                                result.player.toStreamInfo(url, StreamSource.ANDROID, InnerTubeClients.iosUserAgent()),
                                latestSession,
                            )
                        }
                    }
                    unavailableReason = result.player.reason ?: "Android player response did not contain a playable stream"
                }
                is PlayerAttempt.Error -> lastFailure = result.error
            }
            when (val refreshed = transport.anonymousSession()) {
                is YoutubeDataResult.Success -> latestSession = refreshed.updatedSession
                else -> Unit
            }
        }

        val tvClient = InnerTubeClients.tvHtml5()
        when (val result = requestPlayer(latestSession, videoId, playlistId, tvClient)) {
            is PlayerAttempt.Error -> lastFailure = result.error
            is PlayerAttempt.Success -> {
                if (result.player.status != PLAYABLE_STATUS) {
                    unavailableReason = result.player.reason ?: unavailableReason
                } else {
                    val format = selectFormat(result.player, quality, isNetworkMetered)
                    val pipedBaseUrl = options.pipedBaseUrl?.takeIf(String::isNotBlank)
                    if (format != null && pipedBaseUrl != null) {
                        when (val piped = transport.pipedStreams(pipedBaseUrl, videoId)) {
                            is PipedTransportResult.Success -> {
                                val pipedUrl = piped.streams.firstOrNull { it.itag == format.itag }?.url
                                    ?: piped.streams.firstOrNull { it.bitrate == format.bitrate }?.url
                                if (pipedUrl != null) {
                                    return YoutubeDataResult.Success(
                                        result.player.toStreamInfo(pipedUrl, StreamSource.PIPED, tvClient.userAgent, format),
                                        latestSession,
                                    )
                                }
                                unavailableReason = "Piped response did not provide a matching stream"
                            }
                            is PipedTransportResult.Error -> lastFailure = piped.error
                        }
                    }
                    if (format?.url != null) {
                        return YoutubeDataResult.Success(
                            result.player.toStreamInfo(format.url, StreamSource.TVHTML5, tvClient.userAgent),
                            latestSession,
                        )
                    }
                    unavailableReason = unavailableReason
                        ?: "TVHTML5 player response did not contain a playable stream"
                }
            }
        }

        return lastFailure ?: YoutubeDataResult.Unavailable(unavailableReason)
    }

    private suspend fun requestPlayer(
        session: YoutubeMusicSession,
        videoId: String,
        playlistId: String?,
        client: InnerTubeClientConfig,
    ): PlayerAttempt {
        val result = transport.post(
            endpoint = "player",
            session = session,
            payload = transport.playerPayload(session, videoId, playlistId, client),
            client = client,
        )
        return when (result) {
            is TransportResult.Error -> PlayerAttempt.Error(result.error)
            is TransportResult.Success -> PlayerAttempt.Success(parsePlayer(result.response))
        }
    }

    private fun parsePlayer(response: JsonObject): PlayerResponseData {
        val streamingData = response.objectAt("streamingData")
        return PlayerResponseData(
            status = response.stringAt("playabilityStatus", "status"),
            reason = response.stringAt("playabilityStatus", "reason"),
            formats = streamingData?.arrayAt("formats").orEmpty().mapNotNull(::parseFormat),
            adaptiveFormats = streamingData?.arrayAt("adaptiveFormats").orEmpty().mapNotNull(::parseFormat),
            expiresInSeconds = streamingData?.stringAt("expiresInSeconds")?.toLongOrNull() ?: DEFAULT_EXPIRY_SECONDS,
        )
    }

    private fun parseFormat(element: kotlinx.serialization.json.JsonElement): StreamFormat? {
        val value = element.asObjectOrNull() ?: return null
        val itag = value.stringAt("itag")?.toIntOrNull() ?: return null
        val mimeType = value.stringAt("mimeType") ?: return null
        val bitrate = value.stringAt("bitrate")?.toIntOrNull() ?: return null
        return StreamFormat(
            itag = itag,
            url = value.stringAt("url"),
            mimeType = mimeType,
            bitrate = bitrate,
            contentLengthBytes = value.stringAt("contentLength")?.toLongOrNull(),
            audioSampleRateHz = value.stringAt("audioSampleRate")?.toIntOrNull(),
            audioChannels = value.stringAt("audioChannels")?.toIntOrNull(),
            width = value.stringAt("width")?.toIntOrNull(),
            height = value.stringAt("height")?.toIntOrNull(),
        )
    }

    private fun selectFormat(
        player: PlayerResponseData,
        quality: StreamQuality,
        isNetworkMetered: Boolean,
    ): StreamFormat? {
        val audioFormats = player.adaptiveFormats.filter(StreamFormat::isAudio)
        val muxedVideoFormats = player.formats.filter { it.isVideo && it.url != null }
        val videoFormats = (player.formats + player.adaptiveFormats).filter { it.isVideo && it.url != null }
        return when (quality) {
            StreamQuality.DATA_SAVER -> audioFormats.minByOrNull(StreamFormat::bitrate)
                ?: muxedVideoFormats.minByOrNull(StreamFormat::bitrate)
                ?: videoFormats.minByOrNull(StreamFormat::bitrate)
            StreamQuality.HIGH -> muxedVideoFormats.maxByOrNull { it.height ?: 0 }
                ?: videoFormats.maxByOrNull { it.height ?: 0 }
                ?: audioFormats.maxByOrNull(StreamFormat::bitrate)
            StreamQuality.AUTO -> muxedVideoFormats.maxByOrNull { it.height ?: 0 }
                ?: videoFormats.maxByOrNull { it.height ?: 0 }
                ?: selectAutomaticAudioFormat(audioFormats, isNetworkMetered)
        }
    }

    private fun selectAutomaticAudioFormat(
        audioFormats: List<StreamFormat>,
        isNetworkMetered: Boolean,
    ): StreamFormat? {
        return if (isNetworkMetered) {
            audioFormats.minByOrNull(StreamFormat::bitrate)
        } else {
            audioFormats.maxByOrNull {
                it.bitrate + if (it.mimeType.startsWith("audio/webm")) WEBM_PRIORITY else 0
            }
        }
    }

    private fun PlayerResponseData.toStreamInfo(
        url: String,
        source: StreamSource,
        userAgent: String,
        selectedFormat: StreamFormat? = null,
    ): StreamInfo {
        val format = selectedFormat ?: checkNotNull(
            (formats + adaptiveFormats).firstOrNull { it.url == url },
        )
        return StreamInfo(
            url = url,
            mimeType = format.mimeType.substringBefore(';'),
            codecs = CODECS_PATTERN.find(format.mimeType)?.groupValues?.get(1),
            itag = format.itag,
            bitrate = format.bitrate,
            contentLengthBytes = format.contentLengthBytes,
            audioSampleRateHz = format.audioSampleRateHz,
            audioChannels = format.audioChannels,
            expiresAtEpochMillis = System.currentTimeMillis() + expiresInSeconds * 1_000L,
            requestHeaders = mapOf("X-User-Agent" to userAgent),
            source = source,
        )
    }

    private sealed interface PlayerAttempt {
        data class Success(val player: PlayerResponseData) : PlayerAttempt

        data class Error(val error: YoutubeDataResult<Nothing>) : PlayerAttempt
    }

    private data class PlayerResponseData(
        val status: String?,
        val reason: String?,
        val formats: List<StreamFormat>,
        val adaptiveFormats: List<StreamFormat>,
        val expiresInSeconds: Long,
    )

    private data class StreamFormat(
        val itag: Int,
        val url: String?,
        val mimeType: String,
        val bitrate: Int,
        val contentLengthBytes: Long?,
        val audioSampleRateHz: Int?,
        val audioChannels: Int?,
        val width: Int?,
        val height: Int?,
    ) {
        val isAudio: Boolean get() = mimeType.startsWith("audio/")
        val isVideo: Boolean get() = width != null
    }

    private companion object {
        private const val ANDROID_ATTEMPTS = 5
        private const val PLAYABLE_STATUS = "OK"
        private const val DEFAULT_EXPIRY_SECONDS = 600L
        private const val WEBM_PRIORITY = 10_240
        private val CODECS_PATTERN = Regex("""codecs=\"([^\"]+)\"""")
    }
}

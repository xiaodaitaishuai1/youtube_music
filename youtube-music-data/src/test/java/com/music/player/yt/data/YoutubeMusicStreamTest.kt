package com.music.player.yt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YoutubeMusicStreamTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun resolveStream_returnsMedia3CompatibleAudioInfo() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = "https://audio/android")))

        val client = client()
        val result = client.resolveStream(
            session = YoutubeMusicSession("Cgtvisitor"),
            videoId = "video-1",
            quality = StreamQuality.AUTO,
            isNetworkMetered = false,
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<StreamInfo>
        assertEquals("https://audio/android", success.data.url)
        assertEquals("audio/mp4", success.data.mimeType)
        assertEquals("mp4a.40.2", success.data.codecs)
        assertEquals(128_000, success.data.bitrate)
        assertEquals(StreamSource.ANDROID, success.data.source)
        assertEquals("com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)", success.data.requestHeaders["X-User-Agent"])
        assertTrue(success.data.expiresAtEpochMillis > System.currentTimeMillis())

        val request = server.takeRequest()
        assertTrue(request.path.orEmpty().startsWith("/youtubei/v1/player?key="))
        assertSonicTubePlayerHeaders(
            request = request,
            version = "21.09.35",
            userAgent = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/65.0.3325.181 Mobile Safari/537.36",
        )
        assertEquals("Cgtvisitor", request.body.readUtf8().substringAfter("\"visitorData\":\"").substringBefore('"'))
    }

    @Test
    fun resolveStream_fallsBackToTvHtml5AfterAndroidRetries() = runTest {
        enqueueAndroidFailuresWithAnonymousRefresh(playerUnavailableResponse("Android blocked"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = "https://audio/tv")))

        val result = client().resolveStream(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<StreamInfo>
        assertEquals("https://audio/tv", success.data.url)
        assertEquals(StreamSource.TVHTML5, success.data.source)
        assertEquals(11, server.requestCount)
        repeat(10) { server.takeRequest() }
        assertEquals("67", server.takeRequest().getHeader("X-YouTube-Client-Name"))
    }

    @Test
    fun resolveStream_refreshesAnonymousSessionBeforeTheNextAndroidAttempt() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                playerUnavailableResponse("Retry with refreshed visitor", visitorData = "Cgtrefreshed"),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(anonymousSessionResponse("Cgtanonymous")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = "https://audio/android")))

        val result = client().resolveStream(YoutubeMusicSession("Cgtoriginal"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<StreamInfo>
        assertEquals("Cgtanonymous", success.updatedSession.visitorData)
        val firstRequest = server.takeRequest()
        val refreshRequest = server.takeRequest()
        val secondRequest = server.takeRequest()
        assertTrue(firstRequest.body.readUtf8().contains("Cgtoriginal"))
        assertTrue(refreshRequest.path.orEmpty().startsWith("/sw.js_data?key="))
        assertTrue(secondRequest.body.readUtf8().contains("Cgtanonymous"))
    }

    @Test
    fun resolveStream_keepsThePreviousSessionWhenAnonymousRefreshFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerUnavailableResponse("Retry with refreshed visitor")))
        server.enqueue(MockResponse().setResponseCode(500).setBody("refresh unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = "https://audio/android")))

        val result = client().resolveStream(YoutubeMusicSession("Cgtoriginal"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<StreamInfo>
        assertEquals("Cgtoriginal", success.updatedSession.visitorData)
        server.takeRequest()
        val refreshRequest = server.takeRequest()
        val retryRequest = server.takeRequest()
        assertTrue(refreshRequest.path.orEmpty().startsWith("/sw.js_data?key="))
        assertTrue(retryRequest.body.readUtf8().contains("Cgtoriginal"))
    }

    @Test
    fun resolveStream_refreshesBeforeTvFallbackAndUsesTheLatestVisitor() = runTest {
        repeat(5) { attempt ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(playerUnavailableResponse("Android blocked")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(anonymousSessionResponse("Cgt$attempt")))
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = "https://audio/tv")))

        val result = client().resolveStream(YoutubeMusicSession("Cgtoriginal"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<StreamInfo>
        assertEquals(StreamSource.TVHTML5, success.data.source)
        assertEquals("Cgt4", success.updatedSession.visitorData)
        repeat(10) { server.takeRequest() }
        val tvRequest = server.takeRequest()
        assertSonicTubePlayerHeaders(
            request = tvRequest,
            version = "2.0",
            userAgent = "Mozilla/5.0 (PlayStation 4 5.55) AppleWebKit/601.2 (KHTML, like Gecko)",
        )
        assertTrue(tvRequest.body.readUtf8().contains("Cgt4"))
    }

    @Test
    fun resolveStream_replacesTvFormatUrlWithConfiguredPipedAudio() = runTest {
        enqueueAndroidFailuresWithAnonymousRefresh(playerUnavailableResponse("Android blocked"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = null)))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"audioStreams":[{"itag":140,"url":"https://piped/audio","bitrate":128000}]}""",
            ),
        )

        val result = client(
            streamOptions = YoutubeStreamOptions(pipedBaseUrl = server.url("/piped/").toString()),
        ).resolveStream(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<StreamInfo>
        assertEquals("https://piped/audio", success.data.url)
        assertEquals(StreamSource.PIPED, success.data.source)
        assertEquals(140, success.data.itag)
        repeat(11) { server.takeRequest() }
        assertEquals("/piped/streams/video-1", server.takeRequest().path)
    }

    @Test
    fun resolveStream_replacesDirectTvUrlWithMatchingConfiguredPipedAudio() = runTest {
        enqueueAndroidFailuresWithAnonymousRefresh(playerUnavailableResponse("Android blocked"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = "https://audio/tv")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"audioStreams":[{"itag":140,"url":"https://piped/audio","bitrate":128000}]}""",
            ),
        )

        val result = client(
            streamOptions = YoutubeStreamOptions(pipedBaseUrl = server.url("/piped/").toString()),
        ).resolveStream(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<StreamInfo>
        assertEquals("https://piped/audio", success.data.url)
        assertEquals(StreamSource.PIPED, success.data.source)
    }

    @Test
    fun clientVersionUpdatesAreInstanceScoped() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = "https://audio/one")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = "https://audio/two")))
        val first = client(initialClientVersions = YoutubeClientVersions(androidVersion = "android-one"))
        val second = client(initialClientVersions = YoutubeClientVersions(androidVersion = "android-two"))

        first.updateClientVersions(YoutubeClientVersions(androidVersion = "android-updated"))
        first.resolveStream(YoutubeMusicSession("Cgtone"), "one")
        second.resolveStream(YoutubeMusicSession("Cgttwo"), "two")

        val firstBody = server.takeRequest().body.readUtf8()
        val secondBody = server.takeRequest().body.readUtf8()
        assertTrue(firstBody.contains("android-updated"))
        assertTrue(secondBody.contains("android-two"))
    }

    @Test
    fun resolveStream_returnsUnavailableWhenEveryPlayerResponseIsUnplayable() = runTest {
        enqueueAndroidFailuresWithAnonymousRefresh(playerUnavailableResponse("Not available"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerUnavailableResponse("Not available")))

        val result = client().resolveStream(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertEquals(YoutubeDataResult.Unavailable("Not available"), result)
        assertEquals(11, server.requestCount)
    }

    @Test
    fun resolveStream_selectsAudioQualityFromTheRequestedPolicy() = runTest {
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(multipleAudioFormatsResponse()))
        }
        val client = client()
        val session = YoutubeMusicSession("Cgtvisitor")

        val dataSaver = client.resolveStream(session, "video-1", quality = StreamQuality.DATA_SAVER)
        val high = client.resolveStream(session, "video-1", quality = StreamQuality.HIGH)
        val automaticWifi = client.resolveStream(session, "video-1", quality = StreamQuality.AUTO, isNetworkMetered = false)
        val automaticMetered = client.resolveStream(session, "video-1", quality = StreamQuality.AUTO, isNetworkMetered = true)

        assertEquals(139, (dataSaver as YoutubeDataResult.Success).data.itag)
        assertEquals(140, (high as YoutubeDataResult.Success).data.itag)
        assertEquals(140, (automaticWifi as YoutubeDataResult.Success).data.itag)
        assertEquals(139, (automaticMetered as YoutubeDataResult.Success).data.itag)
    }

    @Test
    fun resolveStream_selectsVideoFormatsUsingSonicTubeQualityStrategy() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(videoAndAudioResponse()))
        }
        val client = client()
        val session = YoutubeMusicSession("Cgtvisitor")

        val dataSaver = client.resolveStream(session, "video-1", quality = StreamQuality.DATA_SAVER)
        val high = client.resolveStream(session, "video-1", quality = StreamQuality.HIGH)
        val automatic = client.resolveStream(session, "video-1", quality = StreamQuality.AUTO)

        assertEquals(141, (dataSaver as YoutubeDataResult.Success).data.itag)
        assertEquals(22, (high as YoutubeDataResult.Success).data.itag)
        assertEquals(22, (automatic as YoutubeDataResult.Success).data.itag)
        assertEquals("video/mp4", automatic.data.mimeType)
    }

    @Test
    fun resolveStream_fallsBackToAdaptiveVideoWhenNoMuxedVideoIsAvailable() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(adaptiveVideoAndAudioResponse()))

        val result = client().resolveStream(YoutubeMusicSession("Cgtvisitor"), "video-1", quality = StreamQuality.HIGH)

        assertTrue(result is YoutubeDataResult.Success<*>)
        assertEquals(137, (result as YoutubeDataResult.Success<StreamInfo>).data.itag)
        assertEquals("video/mp4", result.data.mimeType)
    }

    @Test
    fun resolveStream_returnsMuxedVideoWhenItIsTheOnlyAvailableResource() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(muxedOnlyResponse()))

        val result = client().resolveStream(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        assertEquals(22, (result as YoutubeDataResult.Success<StreamInfo>).data.itag)
    }

    @Test(expected = CancellationException::class)
    fun resolveStream_rethrowsCancellation() = runTest {
        val cancellingClient = YoutubeMusicDataClient(
            httpClient = OkHttpClient.Builder().addInterceptor { throw CancellationException("cancel") }.build(),
            baseUrl = server.url("/").toString(),
        )

        cancellingClient.resolveStream(YoutubeMusicSession("Cgtvisitor"), "video-1")
    }

    @Test
    fun resolveStream_mapsInvalidPipedEndpointToParseError() = runTest {
        enqueueAndroidFailuresWithAnonymousRefresh(playerUnavailableResponse("Android blocked"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse(url = null)))

        val result = client(
            streamOptions = YoutubeStreamOptions(pipedBaseUrl = "not a valid url"),
        ).resolveStream(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals("Invalid Piped endpoint", (result as YoutubeDataResult.ParseError).message)
    }

    private fun client(
        initialClientVersions: YoutubeClientVersions? = null,
        streamOptions: YoutubeStreamOptions = YoutubeStreamOptions(),
    ) = YoutubeMusicDataClient(
        httpClient = OkHttpClient(),
        baseUrl = server.url("/").toString(),
        initialClientVersions = initialClientVersions,
        streamOptions = streamOptions,
    )

    private fun playerResponse(url: String?): String {
        val urlField = url?.let { "\"url\":\"$it\"," }.orEmpty()
        return """{"responseContext":{"visitorData":"Cgtrefreshed"},"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"120","adaptiveFormats":[{"itag":140,$urlField"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"contentLength":"1000","audioSampleRate":"44100","audioChannels":2}]},"videoDetails":{"videoId":"video-1","title":"Test"}}"""
    }

    private fun playerUnavailableResponse(reason: String, visitorData: String? = null): String {
        val responseContext = visitorData?.let { """{"visitorData":"$it"}""" } ?: "{}"
        return """{"responseContext":$responseContext,"playabilityStatus":{"status":"ERROR","reason":"$reason"}}"""
    }

    private fun anonymousSessionResponse(visitorData: String): String = ")]}'[[0,0,[\"$visitorData\"]]]"

    private fun enqueueAndroidFailuresWithAnonymousRefresh(playerResponse: String) {
        repeat(5) { attempt ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(playerResponse))
            server.enqueue(MockResponse().setResponseCode(200).setBody(anonymousSessionResponse("Cgtrefresh$attempt")))
        }
    }

    private fun assertSonicTubePlayerHeaders(
        request: RecordedRequest,
        version: String,
        userAgent: String,
    ) {
        assertEquals("67", request.getHeader("X-YouTube-Client-Name"))
        assertEquals(version, request.getHeader("X-YouTube-Client-Version"))
        assertEquals(userAgent, request.getHeader("User-Agent"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("music.youtube.com", request.getHeader("Host"))
        assertEquals("https://music.youtube.com", request.getHeader("Origin"))
        assertEquals("https://music.youtube.com", request.getHeader("X-Origin"))
        assertEquals("DESKTOP", request.getHeader("Platform"))
        assertEquals(null, request.getHeader("Referer"))
    }

    private fun multipleAudioFormatsResponse(): String =
        """{"responseContext":{"visitorData":"Cgtrefreshed"},"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"120","adaptiveFormats":[{"itag":139,"url":"https://audio/low","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":64000},{"itag":140,"url":"https://audio/high","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000}]}}"""

    private fun videoAndAudioResponse(): String =
        """{"responseContext":{},"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":22,"url":"https://video/muxed","mimeType":"video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"","bitrate":500000,"width":1280,"height":720}],"adaptiveFormats":[{"itag":141,"url":"https://audio/high","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":256000},{"itag":137,"url":"https://video/adaptive","mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":900000,"width":1920,"height":1080}]}}"""

    private fun adaptiveVideoAndAudioResponse(): String =
        """{"responseContext":{},"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":137,"url":"https://video/adaptive","mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":900000,"width":1920,"height":1080},{"itag":141,"url":"https://audio/high","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":256000}]}}"""

    private fun muxedOnlyResponse(): String =
        """{"responseContext":{},"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":22,"url":"https://video/muxed","mimeType":"video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"","bitrate":500000,"width":1280,"height":720}]}}"""
}

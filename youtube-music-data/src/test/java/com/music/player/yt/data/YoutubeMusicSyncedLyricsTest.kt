package com.music.player.yt.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YoutubeMusicSyncedLyricsTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun syncedLyrics_prefersManualTrackInCallerLanguageOrderAndReturnsUpdatedSession() = runTest {
        server.enqueue(
            jsonResponse(
                playerBody(
                    visitorData = "Cgtupdated",
                    tracks = listOf(
                        track("en", "ASR", "/captions/en-auto"),
                        track("en", null, "/captions/en-manual"),
                        track("ZH-hANS", null, "/captions/zh-manual"),
                    ),
                ),
            ),
        )
        server.enqueue(jsonResponse(json3(event(100, "selected"))))

        val result = client().syncedLyrics(
            session = YoutubeMusicSession("Cgtoriginal"),
            videoId = "video-1",
            preferredLanguageCodes = listOf("zh-Hans", "en"),
        )

        assertTrue(result.toString(), result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<SyncedLyrics>
        assertEquals("ZH-hANS", success.data.languageCode)
        assertEquals(listOf(SyncedLyricLine(100, "selected")), success.data.lines)
        assertEquals("Cgtupdated", success.updatedSession.visitorData)
        assertTrue(server.takeRequest().path!!.startsWith("/youtubei/v1/player?"))
        assertEquals("/captions/zh-manual?fmt=json3", server.takeRequest().path)
    }

    @Test
    fun syncedLyrics_prefersRequestedAutomaticTrackBeforeUnrequestedManualTrack() = runTest {
        server.enqueue(
            jsonResponse(
                playerBody(
                    tracks = listOf(
                        track("fr", null, "/captions/fr-manual"),
                        track("en", "asr", "/captions/en-auto"),
                    ),
                ),
            ),
        )
        server.enqueue(jsonResponse(json3(event(0, "automatic"))))

        val result = client().syncedLyrics(
            YoutubeMusicSession("Cgtvisitor"),
            "video-1",
            preferredLanguageCodes = listOf("en"),
        ) as YoutubeDataResult.Success<SyncedLyrics>

        assertEquals("en", result.data.languageCode)
        server.takeRequest()
        assertEquals("/captions/en-auto?fmt=json3", server.takeRequest().path)
    }

    @Test
    fun syncedLyrics_fallsBackToFirstManualTrack() = runTest {
        server.enqueue(
            jsonResponse(
                playerBody(
                    tracks = listOf(
                        track("en", "asr", "/captions/en-auto"),
                        track("fr", null, "/captions/fr-manual"),
                        track("de", null, "/captions/de-manual"),
                    ),
                ),
            ),
        )
        server.enqueue(jsonResponse(json3(event(0, "manual"))))

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertEquals("fr", (result as YoutubeDataResult.Success<SyncedLyrics>).data.languageCode)
        server.takeRequest()
        assertEquals("/captions/fr-manual?fmt=json3", server.takeRequest().path)
    }

    @Test
    fun syncedLyrics_fallsBackToFirstAvailableAutomaticTrack() = runTest {
        server.enqueue(
            jsonResponse(
                playerBody(
                    tracks = listOf(
                        track("es", "asr", "/captions/es-auto"),
                        track("en", "asr", "/captions/en-auto"),
                    ),
                ),
            ),
        )
        server.enqueue(jsonResponse(json3(event(0, "first"))))

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertEquals("es", (result as YoutubeDataResult.Success<SyncedLyrics>).data.languageCode)
        server.takeRequest()
        assertEquals("/captions/es-auto?fmt=json3", server.takeRequest().path)
    }

    @Test
    fun syncedLyrics_replacesFormatPreservesQueryAndNormalizesEvents() = runTest {
        server.enqueue(
            jsonResponse(
                playerBody(
                    tracks = listOf(track("en", null, "/captions/en?token=abc&fmt=srv3")),
                ),
            ),
        )
        server.enqueue(
            jsonResponse(
                json3(
                    event(200, " later "),
                    event(100, " First", " line "),
                    """{"tStartMs":"100","segs":[{"utf8":"Second"}]}""",
                    event(50, "   "),
                    """{"segs":[{"utf8":"missing time"}]}""",
                    """{"tStartMs":"invalid","segs":[{"utf8":"bad time"}]}""",
                ),
            ),
        )

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")
            as YoutubeDataResult.Success<SyncedLyrics>

        assertEquals(
            listOf(
                SyncedLyricLine(100, "First line"),
                SyncedLyricLine(100, "Second"),
                SyncedLyricLine(200, "later"),
            ),
            result.data.lines,
        )
        server.takeRequest()
        val captionRequest = server.takeRequest()
        assertEquals("abc", captionRequest.requestUrl!!.queryParameter("token"))
        assertEquals(listOf("json3"), captionRequest.requestUrl!!.queryParameterValues("fmt"))
    }

    @Test
    fun syncedLyrics_returnsParseErrorWhenNoUsableTrackExists() = runTest {
        server.enqueue(
            jsonResponse(
                playerBody(
                    tracks = listOf(
                        """{"languageCode":"en","baseUrl":""}""",
                        """{"languageCode":"","baseUrl":"${server.url("/caption")}"}""",
                    ),
                ),
            ),
        )

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals("Player response did not contain an available caption track", (result as YoutubeDataResult.ParseError).message)
    }

    @Test
    fun syncedLyrics_returnsParseErrorWhenCaptionHasNoValidLines() = runTest {
        server.enqueue(jsonResponse(playerBody(tracks = listOf(track("en", null, "/captions/en")))))
        server.enqueue(jsonResponse(json3(event(0, "  "), """{"tStartMs":10}""")))

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals("Caption response did not contain valid lyric lines", (result as YoutubeDataResult.ParseError).message)
    }

    @Test
    fun syncedLyrics_mapsInvalidPlayerJsonToParseError() = runTest {
        server.enqueue(jsonResponse("{"))

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals("InnerTube response was invalid JSON", (result as YoutubeDataResult.ParseError).message)
    }

    @Test
    fun syncedLyrics_mapsInvalidCaptionJsonToParseError() = runTest {
        server.enqueue(jsonResponse(playerBody(tracks = listOf(track("en", null, "/captions/en")))))
        server.enqueue(jsonResponse("{"))

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals("Caption response was invalid JSON", (result as YoutubeDataResult.ParseError).message)
    }

    @Test
    fun syncedLyrics_mapsUnexpectedCaptionStructureToParseError() = runTest {
        server.enqueue(jsonResponse(playerBody(tracks = listOf(track("en", null, "/captions/en")))))
        server.enqueue(jsonResponse("[]"))

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals("Caption response was not a JSON object", (result as YoutubeDataResult.ParseError).message)
    }

    @Test
    fun syncedLyrics_mapsInvalidCaptionUrlToParseError() = runTest {
        server.enqueue(jsonResponse(playerBody(tracks = listOf(track("en", null, "not a url")))))

        val result = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals("Invalid caption track URL", (result as YoutubeDataResult.ParseError).message)
    }

    @Test
    fun syncedLyrics_mapsPlayerAndCaptionIoFailuresToNetworkError() = runTest {
        val playerFailure = YoutubeMusicDataClient(
            httpClient = OkHttpClient.Builder().addInterceptor { throw IOException("player failed") }.build(),
            baseUrl = server.url("/").toString(),
        ).syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")
        assertTrue(playerFailure is YoutubeDataResult.NetworkError)

        server.enqueue(
            jsonResponse(
                playerBody(tracks = listOf(track("en", null, "http://127.0.0.1:1/caption"))),
            ),
        )
        val captionFailure = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")
        assertTrue(captionFailure is YoutubeDataResult.NetworkError)
    }

    @Test
    fun syncedLyrics_mapsPlayerAndCaptionHttpFailuresToUpstreamError() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val playerFailure = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")
        assertEquals(429, (playerFailure as YoutubeDataResult.UpstreamError).statusCode)

        server.enqueue(jsonResponse(playerBody(tracks = listOf(track("en", null, "/captions/en")))))
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val captionFailure = client().syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")
        assertEquals(503, (captionFailure as YoutubeDataResult.UpstreamError).statusCode)
    }

    @Test(expected = CancellationException::class)
    fun syncedLyrics_rethrowsCancellationDuringPlayerRequest() = runTest {
        val cancellingClient = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("cancel") }
            .build()

        client(cancellingClient).syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")
    }

    @Test(expected = CancellationException::class)
    fun syncedLyrics_rethrowsCancellationDuringCaptionRequest() = runTest {
        server.enqueue(jsonResponse(playerBody(tracks = listOf(track("en", null, "/captions/en")))))
        val cancellingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (chain.request().url.encodedPath.startsWith("/captions/")) {
                    throw CancellationException("cancel")
                }
                chain.proceed(chain.request())
            }
            .build()

        client(cancellingClient).syncedLyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")
    }

    private fun client(httpClient: OkHttpClient = OkHttpClient()): YoutubeMusicDataClient = YoutubeMusicDataClient(
        httpClient = httpClient,
        baseUrl = server.url("/").toString(),
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun playerBody(
        tracks: List<String>,
        visitorData: String = "Cgtvisitor",
    ): String = """{"responseContext":{"visitorData":"$visitorData"},"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[${tracks.joinToString(",")}]}}}"""

    private fun track(languageCode: String, kind: String?, pathOrUrl: String): String {
        val url = if (pathOrUrl.startsWith("/")) server.url(pathOrUrl).toString() else pathOrUrl
        val kindJson = kind?.let { ",\"kind\":\"$it\"" }.orEmpty()
        return """{"baseUrl":"$url","languageCode":"$languageCode"$kindJson}"""
    }

    private fun json3(vararg events: String): String = """{"events":[${events.joinToString(",")}] }"""

    private fun event(startTimeMs: Long, vararg segments: String): String =
        """{"tStartMs":$startTimeMs,"segs":[${segments.joinToString(",") { """{"utf8":"$it"}""" }}]}"""
}

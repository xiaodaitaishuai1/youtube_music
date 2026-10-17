package com.music.player.yt.data

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YoutubeMusicLyricsFallbackTest {
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
    fun resolveLyrics_returnsYoutubeCaptionTimeline() = runTest {
        server.enqueue(jsonResponse(playerWithCaption("Cgtcaption", server.url("/caption").toString())))
        server.enqueue(jsonResponse("""{"events":[{"tStartMs":1000,"segs":[{"utf8":"Line"}]}]}"""))

        val result = resolve(title = "Song", artist = "Artist")

        val success = result as YoutubeDataResult.Success<ResolvedLyrics>
        assertEquals(LyricsSource.YOUTUBE_CAPTIONS, success.data.source)
        assertEquals(listOf(SyncedLyricLine(1_000, "Line")), success.data.syncedLines)
        assertNull(success.data.text)
        assertEquals("Cgtcaption", success.updatedSession.visitorData)
    }

    @Test
    fun resolveLyrics_nonChineseFallsBackFromCaptionsToLrcLib() = runTest {
        server.enqueue(jsonResponse(playerWithoutCaptions()))
        server.enqueue(
            jsonResponse(
                """{"plainLyrics":"Plain","syncedLyrics":"[00:01.00]First\n[00:02.500]Second","duration":120}""",
            ),
        )

        val result = resolve(title = "Song", artist = "Artist", durationMs = 120_000)

        val lyrics = (result as YoutubeDataResult.Success<ResolvedLyrics>).data
        assertEquals(LyricsSource.LRCLIB, lyrics.source)
        assertEquals("Plain", lyrics.text)
        assertEquals(
            listOf(SyncedLyricLine(1_000, "First"), SyncedLyricLine(2_500, "Second")),
            lyrics.syncedLines,
        )
        assertTrue(server.takeRequest().path!!.startsWith("/youtubei/v1/player?"))
        assertEquals("lrclib.net", server.takeRequest().getHeader(ORIGINAL_HOST))
    }

    @Test
    fun resolveLyrics_chineseStartsWithKugou() = runTest {
        enqueueKugouLyrics("[00:01.00]中文歌词", title = "夜曲", artist = "周杰伦")

        val result = resolve(title = "夜曲", artist = "周杰伦", durationMs = 180_000)

        val lyrics = (result as YoutubeDataResult.Success<ResolvedLyrics>).data
        assertEquals(LyricsSource.KUGOU, lyrics.source)
        assertEquals(listOf(SyncedLyricLine(1_000, "中文歌词")), lyrics.syncedLines)
        assertEquals("mobileservice.kugou.com", server.takeRequest().getHeader(ORIGINAL_HOST))
    }

    @Test
    fun resolveLyrics_retriesNameProviderWithOriginalNames() = runTest {
        server.enqueue(jsonResponse(playerWithoutCaptions()))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("[]"))
        server.enqueue(
            jsonResponse(
                """{"plainLyrics":"Original","syncedLyrics":"[00:01.00]Original","duration":120}""",
            ),
        )

        val result = resolve(
            title = "Song (Official Video)",
            artist = "Artist feat. Guest",
            durationMs = 120_000,
        )

        assertEquals(
            LyricsSource.LRCLIB,
            (result as YoutubeDataResult.Success<ResolvedLyrics>).data.source,
        )
        server.takeRequest()
        val normalizedExact = server.takeRequest().requestUrl!!
        val normalizedSearch = server.takeRequest().requestUrl!!
        val originalExact = server.takeRequest().requestUrl!!
        assertEquals("Song", normalizedExact.queryParameter("track_name"))
        assertEquals("Artist feat. Guest", normalizedExact.queryParameter("artist_name"))
        assertEquals("Artist feat. Guest Song", normalizedSearch.queryParameter("q"))
        assertEquals("Song (Official Video)", originalExact.queryParameter("track_name"))
        assertEquals("Artist feat. Guest", originalExact.queryParameter("artist_name"))
    }

    @Test
    fun resolveLyrics_lrcLibSearchChoosesClosestDuration() = runTest {
        server.enqueue(jsonResponse(playerWithoutCaptions()))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(
            jsonResponse(
                """[{"plainLyrics":"Far","syncedLyrics":"[00:01.00]Far","duration":630.021224},{"plainLyrics":"Near","syncedLyrics":"[00:02.00]Near","duration":120.735125}]""",
            ),
        )

        val result = resolve(title = "Song", artist = "Artist", durationMs = 120_000)

        val lyrics = (result as YoutubeDataResult.Success<ResolvedLyrics>).data
        assertEquals("Near", lyrics.text)
        assertEquals(listOf(SyncedLyricLine(2_000, "Near")), lyrics.syncedLines)
    }

    @Test
    fun resolveLyrics_kugouUsesMatchingSongAndReturnsTextAndTimeline() = runTest {
        enqueueKugouLyrics(
            lrc = "[00:01.00]First\n[00:03.25]Second",
            title = "Target",
            artist = "Singer",
            includeDistractor = true,
        )

        val result = resolve(title = "目标 Target", artist = "Singer", durationMs = 180_000)

        val lyrics = (result as YoutubeDataResult.Success<ResolvedLyrics>).data
        assertEquals(LyricsSource.KUGOU, lyrics.source)
        assertEquals("First\nSecond", lyrics.text)
        assertEquals(
            listOf(SyncedLyricLine(1_000, "First"), SyncedLyricLine(3_250, "Second")),
            lyrics.syncedLines,
        )
        val search = server.takeRequest().requestUrl!!
        assertEquals("Singer - 目标 Target", search.queryParameter("keyword"))
    }

    @Test
    fun resolveLyrics_finallyFallsBackToYoutubeOfficialWithCaptionSession() = runTest {
        server.enqueue(jsonResponse(playerWithoutCaptions("Cgtrefreshed")))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(jsonResponse("[]"))
        server.enqueue(jsonResponse("""{"data":{"info":[]}}"""))
        server.enqueue(jsonResponse("""{"data":{"info":[]}}"""))
        server.enqueue(jsonResponse(nextWithLyricsEndpoint("lyrics-1")))
        server.enqueue(jsonResponse(officialLyricsBody("Official line")))

        val result = resolve(title = "Song", artist = "Artist")

        val success = result as YoutubeDataResult.Success<ResolvedLyrics>
        assertEquals(LyricsSource.YOUTUBE_OFFICIAL, success.data.source)
        assertEquals("Official line", success.data.text)
        assertNull(success.data.syncedLines)
        assertEquals("Cgtrefreshed", success.updatedSession.visitorData)
        repeat(5) { server.takeRequest() }
        val officialNext = server.takeRequest()
        assertTrue(officialNext.body.readUtf8().contains("\"visitorData\":\"Cgtrefreshed\""))
    }

    @Test
    fun resolveLyrics_returnsNetworkErrorBeforeOtherFailuresAfterAllProvidersFail() = runTest {
        val client = routedClient(
            Interceptor { chain ->
                if (chain.request().url.host == "lrclib.net") throw IOException("offline")
                chain.proceed(chain.request())
            },
        )
        server.enqueue(jsonResponse(playerWithoutCaptions()))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(jsonResponse("{}"))

        val result = client.resolveLyrics(
            YoutubeMusicSession("Cgtvisitor"),
            "video-1",
            "Song",
            "Artist",
            120_000,
        )

        assertTrue(result is YoutubeDataResult.NetworkError)
    }

    @Test
    fun resolveLyrics_returnsUpstreamErrorBeforeParseErrorAfterAllProvidersFail() = runTest {
        server.enqueue(jsonResponse(playerWithoutCaptions()))
        server.enqueue(MockResponse().setResponseCode(503).setBody("lrclib unavailable"))
        server.enqueue(jsonResponse("not-json"))
        server.enqueue(jsonResponse("{}"))

        val result = resolve(title = "Song", artist = "Artist")

        assertTrue(result is YoutubeDataResult.UpstreamError)
        assertEquals(503, (result as YoutubeDataResult.UpstreamError).statusCode)
    }

    @Test
    fun resolveLyrics_returnsUnavailableWhenEveryProviderClearlyHasNoLyrics() = runTest {
        server.enqueue(jsonResponse(playerWithoutCaptions()))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(jsonResponse("[]"))
        server.enqueue(jsonResponse("""{"data":{"info":[]}}"""))
        server.enqueue(jsonResponse("""{"data":{"info":[]}}"""))
        server.enqueue(jsonResponse("""{"responseContext":{}}"""))

        val result = resolve(title = "Song", artist = "Artist")

        assertEquals(
            YoutubeDataResult.Unavailable("No lyrics found from available providers"),
            result,
        )
    }

    @Test(expected = CancellationException::class)
    fun resolveLyrics_rethrowsCancellationFromThirdPartyProvider() = runTest {
        val client = routedClient(
            Interceptor { chain ->
                if (chain.request().url.host == "lrclib.net") throw CancellationException("cancel")
                chain.proceed(chain.request())
            },
        )
        server.enqueue(jsonResponse(playerWithoutCaptions()))

        client.resolveLyrics(
            YoutubeMusicSession("Cgtvisitor"),
            "video-1",
            "Song",
            "Artist",
            120_000,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolvedLyrics_rejectsEmptyContent() {
        ResolvedLyrics(text = " ", syncedLines = emptyList(), source = LyricsSource.LRCLIB)
    }

    private suspend fun resolve(
        title: String,
        artist: String,
        durationMs: Long = 180_000,
    ): YoutubeDataResult<ResolvedLyrics> = routedClient().resolveLyrics(
        session = YoutubeMusicSession("Cgtvisitor"),
        videoId = "video-1",
        title = title,
        artist = artist,
        durationMs = durationMs,
        preferredLanguageCodes = listOf("en"),
    )

    private fun routedClient(vararg interceptors: Interceptor): YoutubeMusicDataClient {
        val builder = OkHttpClient.Builder()
        interceptors.forEach(builder::addInterceptor)
        builder.addInterceptor { chain ->
            val request = chain.request()
            val originalHost = request.url.host
            val localUrl = server.url("/").newBuilder()
                .encodedPath(request.url.encodedPath)
                .encodedQuery(request.url.encodedQuery)
                .build()
            chain.proceed(
                request.newBuilder()
                    .url(localUrl)
                    .header(ORIGINAL_HOST, originalHost)
                    .build(),
            )
        }
        return YoutubeMusicDataClient(
            httpClient = builder.build(),
            baseUrl = server.url("/").toString(),
        )
    }

    private fun enqueueKugouLyrics(
        lrc: String,
        title: String,
        artist: String,
        includeDistractor: Boolean = false,
    ) {
        val distractor = if (includeDistractor) {
            """{"hash":"wrong","duration":180,"songname":"Other","singername":"Other"},"""
        } else {
            ""
        }
        server.enqueue(
            jsonResponse(
                """<!--KG_TAG_RES_START-->{"data":{"info":[$distractor{"hash":"hash-1","duration":180,"songname":"$title","singername":"$artist"}]}}<!--KG_TAG_RES_END-->""",
            ),
        )
        server.enqueue(jsonResponse("""{"candidates":[{"id":"42","accesskey":"key-1"}]}"""))
        val encoded = Base64.getEncoder().encodeToString(lrc.toByteArray())
        server.enqueue(jsonResponse("""{"content":"$encoded"}"""))
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun playerWithoutCaptions(visitorData: String = "Cgtvisitor"): String =
        """{"responseContext":{"visitorData":"$visitorData"}}"""

    private fun playerWithCaption(visitorData: String, captionUrl: String): String =
        """{"responseContext":{"visitorData":"$visitorData"},"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"$captionUrl","languageCode":"en"}]}}}"""

    private fun nextWithLyricsEndpoint(browseId: String): String =
        """{"responseContext":{},"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{}},{"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"$browseId"}}}}]}}}}}"""

    private fun officialLyricsBody(text: String): String =
        """{"responseContext":{},"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"$text"}]}}}]}}}"""

    private companion object {
        const val ORIGINAL_HOST = "X-Original-Host"
    }
}

package com.music.player.yt.data

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

class YoutubeMusicDataClientTest {
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
    fun createAnonymousSession_extractsVisitorData() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(")]}'[[0,0,[\"Cgtvisitor\"]]]"))
        val client = client()

        val result = client.createAnonymousSession()

        assertEquals(
            YoutubeDataResult.Success(YoutubeMusicSession("Cgtvisitor"), YoutubeMusicSession("Cgtvisitor")),
            result,
        )
        val request = server.takeRequest()
        assertEquals("/sw.js_data?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30", request.path)
        assertEquals("67", request.getHeader("X-YouTube-Client-Name"))
        assertEquals("1.20250716.03.00", request.getHeader("X-YouTube-Client-Version"))
        assertEquals("https://music.youtube.com", request.getHeader("Origin"))
        assertEquals("https://music.youtube.com", request.getHeader("X-Origin"))
        assertEquals("https://music.youtube.com/", request.getHeader("Referer"))
        assertEquals("DESKTOP", request.getHeader("Platform"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("music.youtube.com", request.getHeader("Host"))
        assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.157 Safari/537.36",
            request.getHeader("User-Agent"),
        )
    }

    @Test
    fun search_sendsSessionAndReturnsRefreshedSession() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{"visitorData":"Cgtupdated"},"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"track-1"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track title"}]} }},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist name","navigationEndpoint":{"browseEndpoint":{"browseId":"artist-1"}}}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/track"}]}}}}}]}}]}}}}]}}}""",
            ),
        )
        val client = client()

        val result = client.search(YoutubeMusicSession("Cgtoriginal"), "test")

        assertTrue(result is YoutubeDataResult.Success<*>)
        @Suppress("UNCHECKED_CAST")
        val success = result as YoutubeDataResult.Success<Page<CatalogItem>>
        assertEquals("Cgtupdated", success.updatedSession.visitorData)
        assertEquals(Track(id = "track-1", title = "Track title", artists = listOf(Artist("Artist name", "artist-1")), thumbnailUrl = "https://img/track"), success.data.items.single())
        val request = server.takeRequest()
        assertEquals("/youtubei/v1/search?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30", request.path)
        assertEquals("Cgtoriginal", request.body.readUtf8().substringAfter("\"visitorData\":\"").substringBefore('"'))
        assertEquals("67", request.getHeader("X-YouTube-Client-Name"))
        assertEquals(null, request.getHeader("Cookie"))
    }

    @Test
    fun search_mapsHttpFailureToUpstreamError() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))

        val result = client().search(YoutubeMusicSession("Cgtvisitor"), "test")

        assertTrue(result is YoutubeDataResult.UpstreamError)
        val error = result as YoutubeDataResult.UpstreamError
        assertEquals(429, error.statusCode)
        assertEquals("rate limited", error.message)
    }

    @Test
    fun search_mapsInvalidJsonToParseError() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        val result = client().search(YoutubeMusicSession("Cgtvisitor"), "test")

        assertTrue(result is YoutubeDataResult.ParseError)
    }

    @Test
    fun search_mapsIoFailureToNetworkError() = runTest {
        server.shutdown()

        val result = client().search(YoutubeMusicSession("Cgtvisitor"), "test")

        assertTrue(result is YoutubeDataResult.NetworkError)
    }

    @Test
    fun search_mapsInvalidBaseUrlToParseError() = runTest {
        val invalidBaseUrlClient = YoutubeMusicDataClient(
            httpClient = OkHttpClient(),
            baseUrl = "not a valid url",
        )

        val result = invalidBaseUrlClient.search(YoutubeMusicSession("Cgtvisitor"), "test")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals("Invalid InnerTube base URL", (result as YoutubeDataResult.ParseError).message)
    }

    @Test
    fun clientsDoNotShareSessionState() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{" + "\"responseContext\":{},\"contents\":{}}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{" + "\"responseContext\":{},\"contents\":{}}"))
        val first = client()
        val second = client()

        first.search(YoutubeMusicSession("Cgtone"), "one")
        second.search(YoutubeMusicSession("Cgttwo"), "two")

        val firstBody = server.takeRequest().body.readUtf8()
        val secondBody = server.takeRequest().body.readUtf8()
        assertTrue(firstBody.contains("Cgtone"))
        assertTrue(secondBody.contains("Cgttwo"))
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() = runTest {
        val failingClient = YoutubeMusicDataClient(
            httpClient = OkHttpClient.Builder().addInterceptor { throw CancellationException("cancel") }.build(),
            baseUrl = server.url("/").toString(),
        )

        failingClient.search(YoutubeMusicSession("Cgtvisitor"), "test")
    }

    private fun client() = YoutubeMusicDataClient(
        httpClient = OkHttpClient(),
        baseUrl = server.url("/").toString(),
    )
}

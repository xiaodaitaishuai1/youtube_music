package com.music.player.yt.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YoutubeVersionHelperTest {
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
    fun fetchCurrentVersion_extractsYtMusicAndAndroidVersions() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """export const YTMUSIC = { NAME: 'WEB_REMIX', VERSION: 'web-current' }; export const ANDROID = { NAME: 'ANDROID', VERSION: 'android-current' };""",
            ),
        )

        val result = YoutubeVersionHelper(
            httpClient = OkHttpClient(),
            sourceUrl = server.url("/constants").toString(),
        ).fetchCurrentVersion()

        assertTrue(result is YoutubeVersionResult.Success)
        val versions = (result as YoutubeVersionResult.Success).versions
        assertEquals("web-current", versions.webRemixVersion)
        assertEquals("android-current", versions.androidVersion)
        assertEquals("/constants", server.takeRequest().path)
    }

    @Test
    fun fetchCurrentVersion_mapsMissingVersionsToParseError() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("export const UNKNOWN = {};"))

        val result = YoutubeVersionHelper(
            httpClient = OkHttpClient(),
            sourceUrl = server.url("/constants").toString(),
        ).fetchCurrentVersion()

        assertTrue(result is YoutubeVersionResult.ParseError)
    }

    @Test
    fun fetchCurrentVersion_mapsHttpFailureToUpstreamError() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))

        val result = YoutubeVersionHelper(
            httpClient = OkHttpClient(),
            sourceUrl = server.url("/constants").toString(),
        ).fetchCurrentVersion()

        assertEquals(YoutubeVersionResult.UpstreamError(503, "temporarily unavailable"), result)
    }

    @Test
    fun fetchCurrentVersion_mapsIoFailureToNetworkError() = runTest {
        server.shutdown()

        val result = YoutubeVersionHelper(
            httpClient = OkHttpClient(),
            sourceUrl = server.url("/constants").toString(),
        ).fetchCurrentVersion()

        assertTrue(result is YoutubeVersionResult.NetworkError)
    }
}

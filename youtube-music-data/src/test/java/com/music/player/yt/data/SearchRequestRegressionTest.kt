package com.music.player.yt.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SearchRequestRegressionTest {
    @Test fun allFiltersKeepChineseQuerySessionAndOpaqueToken() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = YoutubeMusicDataClient(baseUrl = server.url("/").toString())
            val query = "周杰伦 Jay Chou + / 🎵"
            val token = "opaque+/=%2B中文"
            for (filter in SearchFilter.entries) {
                server.enqueue(MockResponse().setBody("""{"responseContext":{"visitorData":"refreshed"},"continuationContents":{"musicShelfContinuation":{"contents":[]}}}"""))
                val result = client.search(YoutubeMusicSession("test-session"), query, filter, token)
                assertTrue(filter.name, result is YoutubeDataResult.Success)
                result as YoutubeDataResult.Success
                assertNull(result.data.nextPageToken)
                assertEquals("refreshed", result.updatedSession.visitorData)
                val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/youtubei/v1/search", request.requestUrl!!.encodedPath)
                assertEquals(token, request.requestUrl!!.queryParameter("continuation"))
                assertEquals(token, request.requestUrl!!.queryParameter("ctoken"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(query, body["query"]!!.jsonPrimitive.content)
                assertEquals(filter.params, body["params"]?.jsonPrimitive?.content)
                assertEquals("test-session", body["context"]!!.jsonObject["client"]!!.jsonObject["visitorData"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun structurallyInvalidResponsesAreWrappedAsParseError() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = YoutubeMusicDataClient(baseUrl = server.url("/").toString())
            val malformed = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"title":{"runs":[{"text":"No endpoint"}]}}}]}}]}}}}]}}}"""
            for (body in listOf("{}", malformed)) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(client.search(YoutubeMusicSession("test"), "周杰伦") is YoutubeDataResult.ParseError)
            }
        }
    }

    @Test fun knownEmptyResultsRemainSuccessful() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[]}}]}}}}]}}}"""))
            val result = YoutubeMusicDataClient(baseUrl = server.url("/").toString()).search(YoutubeMusicSession("test"), "none")
            assertTrue(result is YoutubeDataResult.Success)
            assertTrue((result as YoutubeDataResult.Success).data.items.isEmpty())
        }
    }

    @Test(expected = CancellationException::class)
    fun continuationCancellationIsRethrown() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { throw CancellationException("cancelled") }.build()
        YoutubeMusicDataClient(httpClient = http).search(YoutubeMusicSession("test"), "周杰伦", SearchFilter.SONGS, "next")
    }
}

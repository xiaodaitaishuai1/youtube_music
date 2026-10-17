package com.music.player.yt.data.internal

import com.music.player.yt.data.SearchFilter
import com.music.player.yt.data.YoutubeDataResult
import com.music.player.yt.data.YoutubeMusicLocale
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only: YT_SEARCH_CAPTURE=1. Captures no cookies or request headers. */
class SearchLiveCaptureTest {
    @Test
    fun captureAnonymousSearchResponses() = runTest(timeout = kotlin.time.Duration.parse("300s")) {
        assumeTrue(System.getenv("YT_SEARCH_CAPTURE") == "1")
        val http = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7897)))
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val transport = InnerTubeTransport(http, "https://music.youtube.com/", YoutubeMusicLocale())
        val sessionResult = transport.anonymousSession()
        if (sessionResult is YoutubeDataResult.ParseError) println("Session parse failure: ${sessionResult.message}; ${sessionResult.cause?.javaClass?.simpleName}")
        assertTrue("Anonymous session failed: ${sessionResult.javaClass.simpleName}", sessionResult is YoutubeDataResult.Success)
        var session = (sessionResult as YoutubeDataResult.Success).data
        val output = File("src/test/resources/search/real").apply { mkdirs() }
        val cases = listOf("jay-cn" to "周杰伦", "jay-en" to "Jay Chou", "shape" to "Shape of You", "no-results" to "zzxqv948173628zzxqv948173628")
        for ((name, query) in cases) {
            val filters = if (name == "shape") SearchFilter.entries else listOf(SearchFilter.ALL, SearchFilter.SONGS, SearchFilter.ARTISTS)
            for (filter in filters) {
                val response = transport.post("search", session, transport.searchPayload(session, query, filter.params))
                assertTrue("$name/$filter failed: ${response.javaClass.simpleName}", response is TransportResult.Success)
                response as TransportResult.Success
                session = response.updatedSession
                File(output, "$name-${filter.name.lowercase()}.json").writeText(Json.encodeToString(JsonObject.serializer(), sanitize(response.response) as JsonObject))
                println("Captured $name/$filter")
                val sections = response.response.arrayAt("contents", "tabbedSearchResultsRenderer", "tabs").firstOrNull().asObjectOrNull()
                    ?.objectAt("tabRenderer", "content", "sectionListRenderer")
                val token = sections?.continuationToken() ?: sections?.arrayAt("contents")?.firstNotNullOfOrNull {
                    it.asObjectOrNull()?.objectAt("musicShelfRenderer")?.continuationToken()
                }
                if (token != null && filter != SearchFilter.ALL) {
                    val next = transport.post("search", session, transport.searchPayload(session, query, filter.params), token)
                    assertTrue("$name/$filter continuation failed", next is TransportResult.Success)
                    next as TransportResult.Success
                    session = next.updatedSession
                    File(output, "$name-${filter.name.lowercase()}-next.json").writeText(Json.encodeToString(JsonObject.serializer(), sanitize(next.response) as JsonObject))
                    println("Captured $name/$filter continuation")
                }
            }
        }
    }

    private fun sanitize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it !in setOf("responseContext", "trackingParams", "clickTrackingParams", "loggingDirectives", "serviceTrackingParams", "visitorData", "menu", "buttons", "loggingContext", "serializedShareEntity") }.mapValues {
            if (it.key == "continuation" || it.key == "token") kotlinx.serialization.json.JsonPrimitive("fixture-continuation") else sanitize(it.value)
        })
        is JsonArray -> JsonArray(value.map(::sanitize))
        else -> value
    }
}

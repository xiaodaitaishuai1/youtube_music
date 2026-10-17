package com.music.player.yt.data.internal

import com.music.player.yt.data.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Verifies the public client and parser against the very same real HTTP response. */
class SearchLiveValidationTest {
    @Test fun realQueriesAndContinuationsMatchParserOutput() = runTest(timeout = kotlin.time.Duration.parse("300s")) {
        assumeTrue(System.getenv("YT_SEARCH_VALIDATE") == "1")
        var searchResponse: JsonObject? = null
        val http = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7897)))
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath.endsWith("/search") && response.isSuccessful) {
                    searchResponse = Json.parseToJsonElement(response.peekBody(2_000_000).string()).jsonObject
                }
                response
            }.build()
        val client = YoutubeMusicDataClient(httpClient = http)
        val initialSession = client.createAnonymousSession()
        assertTrue("Anonymous session: ${initialSession.javaClass.simpleName}", initialSession is YoutubeDataResult.Success)
        var session = (initialSession as YoutubeDataResult.Success).data
        val evidence = mutableListOf("| Query | Filter | Page | Candidates | Parsed | Unsupported | Failed | Duplicates | Items | Next token |", "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
        val filters = listOf(SearchFilter.ALL, SearchFilter.SONGS, SearchFilter.ARTISTS)
        for (query in listOf("周杰伦", "Jay Chou", "Shape of You")) {
            for (filter in filters) {
                var token: String? = null
                for (pageNumber in 1..2) {
                    searchResponse = null
                    val result = client.search(session, query, filter, token)
                    assertTrue("$query/$filter/page$pageNumber: ${result.javaClass.simpleName}", result is YoutubeDataResult.Success)
                    result as YoutubeDataResult.Success
                    session = result.updatedSession
                    val parsed = InnerTubeParser.searchWithDiagnostics(checkNotNull(searchResponse), filter, token != null)
                    assertEquals(parsed.page, result.data)
                    assertEquals("$query/$filter: ${parsed.diagnostics}", 0, parsed.diagnostics.failed)
                    assertTrue("$query/$filter returned no supported results", result.data.items.isNotEmpty())
                    val d = parsed.diagnostics
                    evidence.add("| $query | $filter | $pageNumber | ${d.candidates} | ${d.parsed} | ${d.unsupported} | ${d.failed} | ${d.duplicates} | ${result.data.items.size} | ${result.data.nextPageToken != null} |")
                    token = result.data.nextPageToken
                    if (token == null) break
                }
            }
        }
        val transport = InnerTubeTransport(http, "https://music.youtube.com/", YoutubeMusicLocale())
        val emptyResponse = transport.post("search", session, transport.searchPayload(session, "zzxqv948173628zzxqv948173628", SearchFilter.ARTISTS.params))
        assertTrue(emptyResponse is TransportResult.Success)
        val empty = InnerTubeParser.search((emptyResponse as TransportResult.Success).response, SearchFilter.ARTISTS)
        assertTrue(empty.items.isEmpty())
        evidence.add("\n真实 ARTISTS 无结果结构返回成功空页。ARTISTS 首屏没有令牌时不强制分页。ALL 没有已验证整体令牌时不强制分页。")
        File("../docs/search-live-validation.md").writeText("# 真实搜索验证（2026-10-13）\n\n" + evidence.joinToString("\n"))
    }
}

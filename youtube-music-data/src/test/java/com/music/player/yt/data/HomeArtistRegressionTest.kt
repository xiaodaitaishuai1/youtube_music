package com.music.player.yt.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeArtistRegressionTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun home_keepsResponsiveArtistsAndSectionMetadata() = runTest {
        enqueue(page(carousel(card("responsive", "UC_artist", "MUSIC_PAGE_TYPE_ARTIST"))))

        val result = success(client().home(YoutubeMusicSession("Cgtinitial")))
        val section = result.data.items.single()
        assertEquals("Recommended artists", section.title)
        assertEquals("For you", section.label)
        assertEquals(BrowseEndpoint("more-artists", "artist-params"), section.endpoint)
        assertEquals(listOf(Artist("Artist UC_artist", "UC_artist", "https://img/UC_artist")), section.items)
        assertEquals("Cgtupdated", result.updatedSession.visitorData)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/youtubei/v1/browse?"))
        assertEquals("FEmusic_home", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["browseId"]?.let {
            (it as JsonPrimitive).content
        })
    }

    @Test fun home_usesArtistPageTypeBeforeIdPrefixInBothCardLayouts() = runTest {
        val cards = listOf("responsive", "twoRow").flatMap { layout ->
            listOf("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL").map { type ->
                card(layout, "custom_${layout}_$type", type)
            }
        }
        enqueue(page(carousel(cards.joinToString(","))))

        val items = success(client().home(YoutubeMusicSession("Cgtinitial"))).data.items.single().items
        assertEquals(4, items.size)
        assertTrue(items.all { it is Artist })
        assertEquals(
            listOf(
                "custom_responsive_MUSIC_PAGE_TYPE_ARTIST", "custom_responsive_MUSIC_PAGE_TYPE_USER_CHANNEL",
                "custom_twoRow_MUSIC_PAGE_TYPE_ARTIST", "custom_twoRow_MUSIC_PAGE_TYPE_USER_CHANNEL",
            ),
            items.map { it.id },
        )
    }

    @Test fun home_keepsUcPrefixFallbackWhenPageTypeIsAbsent() = runTest {
        enqueue(page(carousel(listOf("responsive", "twoRow").joinToString(",") { card(it, "UC_$it") })))

        val items = success(client().home(YoutubeMusicSession("Cgtinitial"))).data.items.single().items
        assertEquals(listOf("UC_responsive", "UC_twoRow"), items.map { it.id })
        assertTrue(items.all { it is Artist })
    }

    @Test fun home_doesNotOverrideExplicitAlbumTypeWithUcPrefix() = runTest {
        enqueue(page(carousel(listOf("responsive", "twoRow").joinToString(",") {
            card(it, "UC_$it", "MUSIC_PAGE_TYPE_ALBUM")
        })))

        val items = success(client().home(YoutubeMusicSession("Cgtinitial"))).data.items.single().items
        assertEquals(2, items.size)
        assertTrue(items.all { it is Album })
    }

    @Test fun home_skipsArtistsWithMissingNameOrId() = runTest {
        val invalid = listOf("responsive", "twoRow").flatMap { layout ->
            listOf(
                card(layout, "UC_missing", "MUSIC_PAGE_TYPE_ARTIST", ""),
                card(layout, "", "MUSIC_PAGE_TYPE_ARTIST"),
                card(layout, null, "MUSIC_PAGE_TYPE_ARTIST"),
            )
        }
        enqueue(page(carousel((invalid + card("twoRow", "UC_valid")).joinToString(","))))

        val items = success(client().home(YoutubeMusicSession("Cgtinitial"))).data.items.single().items
        assertEquals(listOf("UC_valid"), items.map { it.id })
    }

    @Test fun home_keepsArtistsAcrossInitialAndContinuationPages() = runTest {
        enqueue(page(carousel(card("responsive", "UC_first")), token = "next-home"))
        enqueue(page(carousel(card("twoRow", "custom_next", "MUSIC_PAGE_TYPE_ARTIST")), continuation = true, token = "last-home"))
        val client = client()

        val initial = success(client.home(YoutubeMusicSession("Cgtinitial")))
        val next = success(client.home(initial.updatedSession, initial.data.nextPageToken))

        assertEquals("next-home", initial.data.nextPageToken)
        assertEquals("last-home", next.data.nextPageToken)
        assertEquals("UC_first", (initial.data.items.single().items.single() as Artist).channelId)
        assertEquals("custom_next", (next.data.items.single().items.single() as Artist).channelId)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("next-home", request.requestUrl!!.queryParameter("continuation"))
        val clientContext = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals("Cgtupdated", (clientContext["visitorData"] as JsonPrimitive).content)
    }

    @Test fun home_keepsMixedCatalogTypesAndSectionOrder() = runTest {
        val song = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Song"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"song-1"}}}}"""
        val album = card("twoRow", "MPRE_album", "MUSIC_PAGE_TYPE_ALBUM")
        val playlist = card("twoRow", "VLplaylist-1", "MUSIC_PAGE_TYPE_PLAYLIST")
        enqueue(page(carousel(song, "Songs"), carousel("$album,$playlist", "Collections"), carousel(card("responsive", "UC_artist"))))

        val sections = success(client().home(YoutubeMusicSession("Cgtinitial"))).data.items
        assertEquals(listOf("Songs", "Collections", "Recommended artists"), sections.map { it.title })
        assertTrue(sections[0].items.single() is Track)
        assertTrue(sections[1].items[0] is Album)
        assertTrue(sections[1].items[1] is Playlist)
        assertEquals("playlist-1", sections[1].items[1].id)
        assertTrue(sections[2].items.single() is Artist)
    }

    @Test fun home_returnsUnavailableForRegionRestriction() = runTest {
        enqueue(page(message()))

        val result = client().home(YoutubeMusicSession("Cgtinitial"))

        assertEquals(YoutubeDataResult.Unavailable("YouTube Music isn't available in your country"), result)
    }

    @Test fun browse_returnsUnavailableForRestrictedContinuation() = runTest {
        enqueue(page(message(), continuation = true))

        val result = client().browse(YoutubeMusicSession("Cgtinitial"), BrowseEndpoint("more-artists"), "next")

        assertTrue(result is YoutubeDataResult.Unavailable)
    }

    @Test fun home_keepsDisplayableItemsWhenRestrictionMessageIsAlsoPresent() = runTest {
        enqueue(page(message(), carousel(card("twoRow", "UC_available"))))

        assertEquals("UC_available", success(client().home(YoutubeMusicSession("Cgtinitial"))).data.items.single().items.single().id)
    }

    @Test fun home_doesNotTreatOtherMessagesOrPlainEmptyPagesAsUnavailable() = runTest {
        enqueue(page())
        enqueue(page(message("INFO", "Nothing to recommend")))
        val client = client()

        assertTrue(success(client.home(YoutubeMusicSession("Cgtinitial"))).data.items.isEmpty())
        assertTrue(success(client.home(YoutubeMusicSession("Cgtinitial"))).data.items.isEmpty())
    }

    @Test fun home_preservesHttpAndJsonErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("invalid json"))
        val client = client()

        assertEquals(YoutubeDataResult.UpstreamError(403, "Forbidden"), client.home(YoutubeMusicSession("Cgtinitial")))
        assertTrue(client.home(YoutubeMusicSession("Cgtinitial")) is YoutubeDataResult.ParseError)
    }

    private fun client() = YoutubeMusicDataClient(baseUrl = server.url("/").toString())

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun success(result: YoutubeDataResult<Page<HomeSection>>): YoutubeDataResult.Success<Page<HomeSection>> {
        assertTrue("Expected success, got $result", result is YoutubeDataResult.Success)
        return result as YoutubeDataResult.Success<Page<HomeSection>>
    }

    private fun card(layout: String, id: String?, type: String? = null, name: String = "Artist $id"): String {
        val browse = buildJsonObject {
            id?.let { put("browseId", it) }
            type?.let { put("browseEndpointContextSupportedConfigs", buildJsonObject {
                put("browseEndpointContextMusicConfig", buildJsonObject { put("pageType", it) })
            }) }
        }
        val title = buildJsonObject {
            put("runs", buildJsonArray { add(buildJsonObject { put("text", name) }) })
        }
        val renderer = buildJsonObject {
            put("navigationEndpoint", buildJsonObject { put("browseEndpoint", browse) })
            if (layout == "responsive") {
                put("flexColumns", Json.parseToJsonElement("""[{"musicResponsiveListItemFlexColumnRenderer":{"text":$title}}]"""))
            } else {
                put("title", title)
            }
            put(if (layout == "responsive") "thumbnail" else "thumbnailRenderer",
                Json.parseToJsonElement("""{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/$id"}]}}}"""))
        }
        return buildJsonObject { put(if (layout == "responsive") "musicResponsiveListItemRenderer" else "musicTwoRowItemRenderer", renderer) }.toString()
    }

    private fun carousel(items: String, title: String = "Recommended artists") =
        """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$title"}]},"strapline":{"runs":[{"text":"For you"}]},"moreContentButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"more-artists","params":"artist-params"}}}}}},"contents":[$items]}}"""

    private fun message(icon: String = "MUSIC_UNAVAILABLE", text: String = "YouTube Music isn't available in your country") =
        """{"itemSectionRenderer":{"contents":[{"messageRenderer":{"icon":{"iconType":"$icon"},"text":{"runs":[{"text":"$text"}]}}}]}}"""

    private fun page(vararg sections: String, continuation: Boolean = false, token: String? = null): String {
        val next = token?.let { """, "continuations":[{"nextContinuationData":{"continuation":"$it"}}]""" }.orEmpty()
        val sectionList = """{"contents":[${sections.joinToString(",")}]$next}"""
        val contents = if (continuation) """"continuationContents":{"sectionListContinuation":$sectionList}"""
        else """"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":$sectionList}}}]}}"""
        return """{"responseContext":{"visitorData":"Cgtupdated"},$contents}"""
    }
}

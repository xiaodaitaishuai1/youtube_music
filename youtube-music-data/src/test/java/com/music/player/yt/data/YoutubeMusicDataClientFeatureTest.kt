package com.music.player.yt.data

import kotlinx.coroutines.test.runTest
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YoutubeMusicDataClientFeatureTest {
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
    fun searchContinuation_usesSearchEndpointAndReturnsNextToken() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"continuationContents":{"musicShelfContinuation":{"contents":[],"continuations":[{"nextContinuationData":{"continuation":"next-search"}}]}}}""",
            ),
        )

        val result = client().search(YoutubeMusicSession("Cgtvisitor"), "query", continuationToken = "previous-search")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val page = (result as YoutubeDataResult.Success<Page<CatalogItem>>).data
        assertEquals("next-search", page.nextPageToken)
        assertEquals(
            "/youtubei/v1/search?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30&continuation=previous-search&ctoken=previous-search",
            server.takeRequest().path,
        )
    }

    @Test
    fun searchSuggestions_parsesQueriesAndRecommendations() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"contents":[{"searchSuggestionsSectionRenderer":{"contents":[{"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"lo"},{"text":"fi"}]}}}]}},{"searchSuggestionsSectionRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"track-2"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Suggested"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/suggested"}]}}}}}]}}]}""",
            ),
        )

        val result = client().searchSuggestions(YoutubeMusicSession("Cgtvisitor"), "lo")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val data = (result as YoutubeDataResult.Success<SearchSuggestions>).data
        assertEquals(listOf("lofi"), data.queries)
        assertEquals("track-2", data.recommendedItems.single().id)
    }

    @Test
    fun lyrics_usesNextThenLyricsBrowseAndKeepsRefreshedSession() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{"visitorData":"Cgtrefreshed"},"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{}},{"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"lyrics-1"}}}}]}}}}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"First line"},{"text":"\nSecond line"}]}}}]}}}""",
            ),
        )

        val result = client().lyrics(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<Lyrics>
        assertEquals("First line\nSecond line", success.data.text)
        assertEquals("Cgtrefreshed", success.updatedSession.visitorData)
        assertTrue(server.takeRequest().path!!.startsWith("/youtubei/v1/next?"))
        assertTrue(server.takeRequest().path!!.startsWith("/youtubei/v1/browse?"))
    }

    @Test
    fun related_usesNextThenRelatedBrowse() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{}},{"tabRenderer":{}},{"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"related-1"}}}}]}}}}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"contents":{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"track-3"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Related"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/related"}]}}}}}]}}]}}}""",
            ),
        )

        val result = client().related(YoutubeMusicSession("Cgtvisitor"), "video-1")

        assertTrue(result is YoutubeDataResult.Success<*>)
        assertEquals("track-3", (result as YoutubeDataResult.Success<RelatedContent>).data.songs.single().id)
    }

    @Test
    fun home_parsesCarouselSectionsAndContinuation() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"continuations":[{"nextContinuationData":{"continuation":"next-home"}}],"contents":[{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Listen again"}]}}},"contents":[{"musicTwoRowItemRenderer":{"navigationEndpoint":{"watchEndpoint":{"videoId":"home-track"}},"title":{"runs":[{"text":"Home track"}]},"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/home"}]}}}}}]}}]}}}}]}}}""",
            ),
        )

        val result = client().home(YoutubeMusicSession("Cgtvisitor"))

        assertTrue(result is YoutubeDataResult.Success<*>)
        val page = (result as YoutubeDataResult.Success<Page<HomeSection>>).data
        assertEquals("next-home", page.nextPageToken)
        assertEquals("Listen again", page.items.single().title)
        assertEquals("home-track", (page.items.single().items.single() as Track).id)
        assertNull(page.items.single().endpoint)
    }

    @Test
    fun home_parsesCarouselMoreContentEndpoint() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Listen again"}]},"moreContentButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"home-more","params":"home-params"}}}}}},"contents":[${twoRowTrack("home-more-track")}]}}]}}}}]}}}""",
            ),
        )

        val result = client().home(YoutubeMusicSession("Cgtvisitor"))

        assertTrue(result is YoutubeDataResult.Success<*>)
        val page = (result as YoutubeDataResult.Success<Page<HomeSection>>).data
        assertEquals(BrowseEndpoint("home-more", "home-params"), page.items.single().endpoint)
    }

    @Test
    fun browse_forwardsEndpointParamsAndParsesMixedSections() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                browseResponse(
                    carouselSection("Carousel", twoRowTrack("carousel-track")),
                    gridSection("Grid", twoRowPlaylist("grid-playlist")),
                ),
            ),
        )

        val result = client().browse(
            session = YoutubeMusicSession("Cgtvisitor"),
            endpoint = BrowseEndpoint("browse-mixed", "browse-params"),
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val page = (result as YoutubeDataResult.Success<Page<HomeSection>>).data
        assertEquals(listOf("Carousel", "Grid"), page.items.map(HomeSection::title))
        assertEquals("carousel-track", page.items[0].items.single().id)
        assertEquals("grid-playlist", page.items[1].items.single().id)
        assertNull(page.items[1].label)
        assertNull(page.items[1].thumbnailUrl)
        assertNull(page.items[1].endpoint)

        val request = server.takeRequest()
        val requestBody = request.body.readUtf8()
        assertTrue(request.path!!.startsWith("/youtubei/v1/browse?"))
        assertTrue(requestBody.contains("\"browseId\":\"browse-mixed\""))
        assertTrue(requestBody.contains("\"params\":\"browse-params\""))
    }

    @Test
    fun browse_parsesDirectSectionListRenderer() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"contents":{"sectionListRenderer":{"contents":[${gridSection("Direct grid", twoRowTrack("direct-grid-track"))}]}}}""",
            ),
        )

        val result = client().browse(
            session = YoutubeMusicSession("Cgtvisitor"),
            endpoint = BrowseEndpoint("browse-direct"),
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val page = (result as YoutubeDataResult.Success<Page<HomeSection>>).data
        assertEquals(listOf("Direct grid"), page.items.map(HomeSection::title))
        assertEquals("direct-grid-track", page.items.single().items.single().id)
    }

    @Test
    fun browse_keepsHeaderlessCarouselWithParsedItems() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                browseResponse(
                    """{"musicCarouselShelfRenderer":{"contents":[${twoRowTrack("headerless-track")}]}}""",
                ),
            ),
        )

        val result = client().browse(
            session = YoutubeMusicSession("Cgtvisitor"),
            endpoint = BrowseEndpoint("browse-headerless"),
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val page = (result as YoutubeDataResult.Success<Page<HomeSection>>).data
        assertEquals(1, page.items.size)
        assertEquals("", page.items.single().title)
        assertNull(page.items.single().endpoint)
        assertEquals("headerless-track", page.items.single().items.single().id)
    }

    @Test
    fun homeSection_keepsLegacyFourArgumentJvmConstructor() {
        val constructor = HomeSection::class.java.constructors.singleOrNull {
            it.parameterTypes.contentEquals(
                arrayOf(String::class.java, String::class.java, String::class.java, List::class.java),
            )
        }

        assertTrue("HomeSection must retain its four-argument JVM constructor", constructor != null)
        val section = constructor!!.newInstance("Title", "Label", "https://img", emptyList<CatalogItem>()) as HomeSection
        assertNull(section.endpoint)
    }

    @Test
    fun browse_continuationParsesSectionListContinuationAndKeepsNextToken() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"continuationContents":{"sectionListContinuation":{"contents":[{"gridRenderer":{"items":[${twoRowTrack("continued-track")}]}}],"continuations":[{"nextContinuationData":{"continuation":"next-browse"}}]}}}""",
            ),
        )

        val result = client().browse(
            session = YoutubeMusicSession("Cgtvisitor"),
            endpoint = BrowseEndpoint("browse-page", "page-params"),
            continuationToken = "previous-browse",
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val page = (result as YoutubeDataResult.Success<Page<HomeSection>>).data
        assertEquals("next-browse", page.nextPageToken)
        assertEquals("", page.items.single().title)
        assertEquals("continued-track", page.items.single().items.single().id)

        val request = server.takeRequest()
        val requestBody = request.body.readUtf8()
        assertEquals(
            "/youtubei/v1/browse?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30&continuation=previous-browse&ctoken=previous-browse&type=next",
            request.path,
        )
        assertTrue(requestBody.contains("\"browseId\":\"browse-page\""))
        assertTrue(requestBody.contains("\"params\":\"page-params\""))
    }

    @Test
    fun moodAndGenres_usesMoodsBrowseEndpointAndParsesSections() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{"visitorData":"Cgtmoods"},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"gridRenderer":{"header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"Moods & moments"}]}}},"items":[{"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"Chill"}]},"solid":{"leftStripeColor":4284900966},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_chill","params":"mood-params"}}}}]}}]}}}}]}}}""",
            ),
        )

        val result = client().moodAndGenres(YoutubeMusicSession("Cgtvisitor"))

        assertTrue(result is YoutubeDataResult.Success<*>)
        val sections = (result as YoutubeDataResult.Success<List<MoodAndGenres>>).data
        assertEquals("Moods & moments", sections.single().title)
        val item = sections.single().items.single()
        assertEquals("Chill", item.title)
        assertEquals(4284900966, item.stripeColor)
        assertEquals("FEmusic_chill", item.endpoint.browseId)
        assertEquals("mood-params", item.endpoint.params)
        assertEquals("Cgtmoods", result.updatedSession.visitorData)

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/youtubei/v1/browse?"))
        assertTrue(request.body.readUtf8().contains("\"browseId\":\"FEmusic_moods_and_genres\""))
    }

    @Test
    fun fetchCuratedSongsPage_rejectsNonPositiveLimit() = runTest {
        var error: IllegalArgumentException? = null

        try {
            client().fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem(), limit = 0)
        } catch (thrown: IllegalArgumentException) {
            error = thrown
        }

        assertEquals("limit must be greater than zero", error?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fetchCuratedSongsPage_stopsAfterTenDirectSongsWithoutAnotherRequest() = runTest {
        val trackIds = (1..12).map { "direct-$it" }
        server.enqueue(MockResponse().setResponseCode(200).setBody(directMoodContentResponse(trackIds)))

        val result = client().fetchCuratedSongsPage(
            session = YoutubeMusicSession("Cgtvisitor"),
            item = curatedItem(),
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(trackIds.take(10), success.data.tracks.map(Track::id))
        assertTrue(success.data.nextCursor != null)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fetchCuratedSongsPage_expandsCollectionsUntilLimitThenStops() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(moodContentResponse()))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                tracksPageResponse("Cgtplaylist", null, *(1..4).map { "playlist-$it" }.toTypedArray()),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                tracksPageResponse("Cgtalbum", null, *(1..5).map { "album-$it" }.toTypedArray()),
            ),
        )

        val result = client().fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem())

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(
            listOf("direct-1", "direct-2") + (1..4).map { "playlist-$it" } + (1..4).map { "album-$it" },
            success.data.tracks.map(Track::id),
        )
        assertTrue(success.data.nextCursor != null)
        assertEquals("Cgtalbum", success.updatedSession.visitorData)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun fetchCuratedSongsPage_consumesBufferedTracksWithoutRepeatingRequest() = runTest {
        val directIds = (1..8).map { "direct-$it" }
        server.enqueue(MockResponse().setResponseCode(200).setBody(curatedMoodContentResponse(directIds, listOf("playlist-1"))))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtplaylist", null, "extra-1", "extra-2", "extra-3", "extra-4")))
        val dataClient = client()

        val first = dataClient.fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem()) as YoutubeDataResult.Success<CuratedSongsPage>
        val second = dataClient.fetchCuratedSongsPage(first.updatedSession, curatedItem(), checkNotNull(first.data.nextCursor))

        assertEquals(directIds + listOf("extra-1", "extra-2"), first.data.tracks.map(Track::id))
        assertTrue(second is YoutubeDataResult.Success<*>)
        second as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(listOf("extra-3", "extra-4"), second.data.tracks.map(Track::id))
        assertNull(second.data.nextCursor)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fetchCuratedSongsPage_loadsContinuationAcrossBatches() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(curatedMoodContentResponse(emptyList(), listOf("playlist-1"))))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtfirst", "next-page", "track-1", "track-2")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtsecond", null, "track-3", "track-4")))
        val dataClient = client()

        val first = dataClient.fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem(), limit = 2) as YoutubeDataResult.Success<CuratedSongsPage>
        val second = dataClient.fetchCuratedSongsPage(first.updatedSession, curatedItem(), checkNotNull(first.data.nextCursor), limit = 2)

        assertEquals(listOf("track-1", "track-2"), first.data.tracks.map(Track::id))
        assertTrue(second is YoutubeDataResult.Success<*>)
        second as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(listOf("track-3", "track-4"), second.data.tracks.map(Track::id))
        assertNull(second.data.nextCursor)
        assertEquals("Cgtsecond", second.updatedSession.visitorData)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"browseId\":\"FEmusic_chill\""))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"browseId\":\"VLplaylist-1\""))
        assertTrue(server.takeRequest().path!!.contains("continuation=next-page"))
    }

    @Test
    fun fetchCuratedSongsPage_duplicateIdsDoNotConsumeLimit() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(curatedMoodContentResponse(listOf("track-1", "track-1", "track-2"), listOf("playlist-1"))))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtplaylist", null, "track-2", "track-3", "track-3", "track-4")))

        val result = client().fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem(), limit = 4)

        assertTrue(result is YoutubeDataResult.Success<*>)
        result as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(listOf("track-1", "track-2", "track-3", "track-4"), result.data.tracks.map(Track::id))
        assertNull(result.data.nextCursor)
    }

    @Test
    fun fetchCuratedSongsPage_failedBatchCanRetryOriginalCursorWithoutMissingSongs() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(curatedMoodContentResponse(listOf("direct-1"), listOf("playlist-1", "playlist-2"))))
        val dataClient = client()
        val first = dataClient.fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem(), limit = 1) as YoutubeDataResult.Success<CuratedSongsPage>
        val originalCursor = checkNotNull(first.data.nextCursor)

        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtfirst-attempt", null, "playlist-1-track")))
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
        val failed = dataClient.fetchCuratedSongsPage(first.updatedSession, curatedItem(), originalCursor, limit = 2)

        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtretry-one", null, "playlist-1-track")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtretry-two", null, "playlist-2-track")))
        val retried = dataClient.fetchCuratedSongsPage(first.updatedSession, curatedItem(), originalCursor, limit = 2)

        assertTrue(failed is YoutubeDataResult.UpstreamError)
        assertTrue(retried is YoutubeDataResult.Success<*>)
        retried as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(listOf("playlist-1-track", "playlist-2-track"), retried.data.tracks.map(Track::id))
        assertNull(retried.data.nextCursor)
        assertEquals("Cgtretry-two", retried.updatedSession.visitorData)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun fetchCuratedSongsPage_failedContinuationCanRetryOriginalCursor() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(curatedMoodContentResponse(listOf("direct-1"), listOf("playlist-1"))))
        val dataClient = client()
        val first = dataClient.fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem(), limit = 1) as YoutubeDataResult.Success<CuratedSongsPage>
        val originalCursor = checkNotNull(first.data.nextCursor)

        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtfirst-attempt", "next-page", "playlist-track-1")))
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
        val failed = dataClient.fetchCuratedSongsPage(first.updatedSession, curatedItem(), originalCursor, limit = 2)

        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtretry-first", "next-page", "playlist-track-1")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtretry-next", null, "playlist-track-2")))
        val retried = dataClient.fetchCuratedSongsPage(first.updatedSession, curatedItem(), originalCursor, limit = 2)

        assertTrue(failed is YoutubeDataResult.UpstreamError)
        assertTrue(retried is YoutubeDataResult.Success<*>)
        retried as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(listOf("playlist-track-1", "playlist-track-2"), retried.data.tracks.map(Track::id))
        assertNull(retried.data.nextCursor)
        assertEquals("Cgtretry-next", retried.updatedSession.visitorData)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun fetchCuratedSongsPage_returnsNullCursorWhenExactFinalPageIsFull() = runTest {
        val trackIds = (1..10).map { "direct-$it" }
        server.enqueue(MockResponse().setResponseCode(200).setBody(directMoodContentResponse(trackIds)))

        val result = client().fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem())

        assertTrue(result is YoutubeDataResult.Success<*>)
        result as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(trackIds, result.data.tracks.map(Track::id))
        assertNull(result.data.nextCursor)
    }

    @Test
    fun fetchCuratedSongsPage_skipsTrailingDuplicatesBeforeReturningFinalCursor() = runTest {
        val uniqueTrackIds = (1..10).map { "direct-$it" }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                directMoodContentResponse(uniqueTrackIds + "direct-1"),
            ),
        )

        val result = client().fetchCuratedSongsPage(YoutubeMusicSession("Cgtvisitor"), curatedItem())

        assertTrue(result is YoutubeDataResult.Success<*>)
        result as YoutubeDataResult.Success<CuratedSongsPage>
        assertEquals(uniqueTrackIds, result.data.tracks.map(Track::id))
        assertNull(result.data.nextCursor)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fetchCuratedSongs_returnsDirectAndExpandedSongsWithoutLimit() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(moodContentResponse()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtplaylist", "next-playlist", "playlist-1a", "direct-1")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtcontinuation", null, "playlist-1b")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtalbum", null, "album-1")))

        val result = client().fetchCuratedSongs(
            session = YoutubeMusicSession("Cgtvisitor"),
            item = MoodAndGenres.Item("Chill", 4284900966, BrowseEndpoint("FEmusic_chill", "mood-params")),
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<List<Track>>
        assertEquals(listOf("direct-1", "direct-2", "playlist-1a", "playlist-1b", "album-1"), success.data.map(Track::id))
        assertEquals("Cgtalbum", success.updatedSession.visitorData)

        assertTrue(server.takeRequest().body.readUtf8().contains("\"browseId\":\"FEmusic_chill\""))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"browseId\":\"VLplaylist-1\""))
        assertTrue(server.takeRequest().path!!.contains("continuation=next-playlist"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"browseId\":\"VLalbum-playlist\""))
    }

    @Test
    fun fetchCuratedSongs_keepsAllDirectSongsWithoutTruncation() = runTest {
        val trackIds = (1..11).map { "direct-$it" }
        server.enqueue(MockResponse().setResponseCode(200).setBody(directMoodContentResponse(trackIds)))

        val result = client().fetchCuratedSongs(
            session = YoutubeMusicSession("Cgtvisitor"),
            item = MoodAndGenres.Item("Focus", 4284900966, BrowseEndpoint("FEmusic_focus")),
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        assertEquals(trackIds, (result as YoutubeDataResult.Success<List<Track>>).data.map(Track::id))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fetchCuratedSongs_keepsDirectSongsWhenPlaylistExpansionFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(moodContentResponse()))
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtalbum", null, "album-1")))

        val result = client().fetchCuratedSongs(
            session = YoutubeMusicSession("Cgtvisitor"),
            item = MoodAndGenres.Item("Chill", 4284900966, BrowseEndpoint("FEmusic_chill", "mood-params")),
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<List<Track>>
        assertEquals(listOf("direct-1", "direct-2", "album-1"), success.data.map(Track::id))
        assertEquals("Cgtalbum", success.updatedSession.visitorData)
    }

    @Test
    fun fetchCuratedSongs_expandsResponsiveListAlbum() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(responsiveAlbumMoodContentResponse()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtalbum", null, "responsive-album-1")))

        val result = client().fetchCuratedSongs(
            session = YoutubeMusicSession("Cgtvisitor"),
            item = MoodAndGenres.Item("Focus", 4284900966, BrowseEndpoint("FEmusic_focus")),
        )

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<List<Track>>
        assertEquals(listOf("direct-1", "responsive-album-1"), success.data.map(Track::id))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"browseId\":\"FEmusic_focus\""))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"browseId\":\"VLresponsive-album-playlist\""))
    }

    @Test
    fun fetchCuratedSongs_returnsInitialBrowseError() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))

        val result = client().fetchCuratedSongs(
            session = YoutubeMusicSession("Cgtvisitor"),
            item = MoodAndGenres.Item("Chill", 4284900966, BrowseEndpoint("FEmusic_chill")),
        )

        assertTrue(result is YoutubeDataResult.UpstreamError)
        assertEquals(503, (result as YoutubeDataResult.UpstreamError).statusCode)
    }

    @Test
    fun track_parsesNextQueueMetadata() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{"videoId":"watch-track","title":{"runs":[{"text":"Watch title"}]},"longBylineText":{"runs":[{"text":"Watch artist","navigationEndpoint":{"browseEndpoint":{"browseId":"artist-watch"}}}]},"lengthText":{"runs":[{"text":"3:30"}]},"thumbnail":{"thumbnails":[{"url":"https://img/watch-small"},{"url":"https://img/watch-large"}]}}}]}}}}}}]}}}}}""",
            ),
        )

        val result = client().track(YoutubeMusicSession("Cgtvisitor"), "watch-track")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val track = (result as YoutubeDataResult.Success<TrackPage>).data.track
        assertEquals("Watch title", track.title)
        assertEquals(210, track.durationSeconds)
        assertEquals("https://img/watch-large", track.thumbnailUrl)
    }

    @Test
    fun albumAndPlaylist_parseHeadersAndSongPages() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(detailResponse("Album title", "album-track", "album-playlist")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(detailResponse("Playlist title", "playlist-track", null)))

        val albumResult = client().album(YoutubeMusicSession("Cgtvisitor"), "album-browse")
        val playlistResult = client().playlist(YoutubeMusicSession("Cgtvisitor"), "playlist-id")

        assertTrue(albumResult is YoutubeDataResult.Success<*>)
        assertTrue(playlistResult is YoutubeDataResult.Success<*>)
        val album = (albumResult as YoutubeDataResult.Success<AlbumPage>).data
        val playlist = (playlistResult as YoutubeDataResult.Success<PlaylistPage>).data
        assertEquals("Album title", album.album.title)
        assertEquals("album-playlist", album.album.playlistId)
        assertEquals("album-track", album.songs.single().id)
        assertEquals("Playlist title", playlist.playlist.title)
        assertEquals("playlist-track", playlist.songs.single().id)
    }

    @Test
    fun album_fallsBackToCanonicalPlaylistWhenDetailHasNoSongs() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAlbumDetailResponse("Album title", "album-playlist")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksPageResponse("Cgtfallback", "album-next", "album-track")))

        val result = client().album(YoutubeMusicSession("Cgtvisitor"), "album-browse")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val success = result as YoutubeDataResult.Success<AlbumPage>
        assertEquals("album-track", success.data.songs.single().id)
        assertEquals("album-next", success.data.nextPageToken)
        assertEquals("Cgtfallback", success.updatedSession.visitorData)

        val detailRequest = server.takeRequest()
        val fallbackRequest = server.takeRequest()
        assertTrue(detailRequest.body.readUtf8().contains("\"browseId\":\"album-browse\""))
        assertTrue(fallbackRequest.body.readUtf8().contains("\"browseId\":\"VLalbum-playlist\""))
    }

    @Test
    fun album_doesNotRequestFallbackWhenDetailAlreadyHasSongs() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(detailResponse("Album title", "album-track", "album-playlist")))

        val result = client().album(YoutubeMusicSession("Cgtvisitor"), "album-browse")

        assertTrue(result is YoutubeDataResult.Success<*>)
        assertEquals("album-track", (result as YoutubeDataResult.Success<AlbumPage>).data.songs.single().id)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun album_doesNotRequestFallbackWithoutPlaylistId() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAlbumDetailResponse("Album title", null)))

        val result = client().album(YoutubeMusicSession("Cgtvisitor"), "album-browse")

        assertTrue(result is YoutubeDataResult.Success<*>)
        assertTrue((result as YoutubeDataResult.Success<AlbumPage>).data.songs.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun album_propagatesFallbackFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAlbumDetailResponse("Album title", "album-playlist")))
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))

        val result = client().album(YoutubeMusicSession("Cgtvisitor"), "album-browse")

        assertTrue(result is YoutubeDataResult.UpstreamError)
        assertEquals(503, (result as YoutubeDataResult.UpstreamError).statusCode)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun album_propagatesFallbackParseFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAlbumDetailResponse("Album title", "album-playlist")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        val result = client().album(YoutubeMusicSession("Cgtvisitor"), "album-browse")

        assertTrue(result is YoutubeDataResult.ParseError)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun album_propagatesFallbackNetworkFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAlbumDetailResponse("Album title", "album-playlist")))
        var requestCount = 0
        val client = client(
            OkHttpClient.Builder().addInterceptor { chain ->
                requestCount += 1
                if (requestCount == 2) throw IOException("offline")
                chain.proceed(chain.request())
            }.build(),
        )

        val result = client.album(YoutubeMusicSession("Cgtvisitor"), "album-browse")

        assertTrue(result is YoutubeDataResult.NetworkError)
        assertEquals(2, requestCount)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun artist_parsesHeaderAndShelf() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"responseContext":{},"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Artist title"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/artist"}]}}}}},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Top songs"}]},"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"artist-track"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist song"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/artist-song"}]}}}}}]}}]}}}}]}}}""",
            ),
        )

        val result = client().artist(YoutubeMusicSession("Cgtvisitor"), "artist-browse")

        assertTrue(result is YoutubeDataResult.Success<*>)
        val page = (result as YoutubeDataResult.Success<ArtistPage>).data
        assertEquals("Artist title", page.artist.name)
        assertEquals("Top songs", page.sections.single().title)
        assertEquals("artist-track", page.sections.single().items.single().id)
    }

    private fun client(httpClient: OkHttpClient = OkHttpClient()) = YoutubeMusicDataClient(
        httpClient = httpClient,
        baseUrl = server.url("/").toString(),
    )

    private fun detailResponse(title: String, trackId: String, playlistId: String?): String {
        val canonical = playlistId?.let { "\"microformat\":{\"microformatDataRenderer\":{\"urlCanonical\":\"https://music.youtube.com/playlist?list=$it\"}}," }.orEmpty()
        return """{${canonical}"responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"$title"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/detail"}]}}}}}]}}}}],"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$trackId"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $trackId"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/detail-song"}]}}}}}]}}]}}}}}"""
    }

    private fun emptyAlbumDetailResponse(title: String, playlistId: String?): String {
        val canonical = playlistId?.let { "\"microformat\":{\"microformatDataRenderer\":{\"urlCanonical\":\"https://music.youtube.com/playlist?list=$it\"}}," }.orEmpty()
        return """{${canonical}"responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"$title"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/detail"}]}}}}}]}}}}],"secondaryContents":{"sectionListRenderer":{"contents":[]}}}}}"""
    }

    private fun moodContentResponse(): String {
        val sections = listOf(
            """{"gridRenderer":{"items":[${twoRowTrack("direct-1")},${twoRowPlaylist("playlist-1")},${twoRowAlbum("album-browse", "album-playlist")}]}}""",
            """{"musicCarouselShelfRenderer":{"contents":[${twoRowTrack("direct-2")}]}}""",
        ).joinToString(",")
        return """{"responseContext":{"visitorData":"Cgtcategory"},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[$sections]}}}}]}}}"""
    }

    private fun directMoodContentResponse(trackIds: List<String>): String =
        """{"responseContext":{},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{"contents":[${trackIds.joinToString(",", transform = ::twoRowTrack)}]}}]}}}}]}}}"""

    private fun curatedMoodContentResponse(
        directTrackIds: List<String>,
        playlistIds: List<String>,
    ): String {
        val items = directTrackIds.map(::twoRowTrack) + playlistIds.map(::twoRowPlaylist)
        return browseResponse(carouselSection("Curated", items.joinToString(",")))
    }

    private fun curatedItem(): MoodAndGenres.Item =
        MoodAndGenres.Item("Chill", 4284900966, BrowseEndpoint("FEmusic_chill", "mood-params"))

    private fun browseResponse(vararg sections: String): String =
        """{"responseContext":{},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[${sections.joinToString(",")}]}}}}]}}}"""

    private fun carouselSection(title: String, items: String): String =
        """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$title"}]}}},"contents":[$items]}}"""

    private fun gridSection(title: String, items: String): String =
        """{"gridRenderer":{"header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"$title"}]}}},"items":[$items]}}"""

    private fun responsiveAlbumMoodContentResponse(): String =
        """{"responseContext":{},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{"contents":[${twoRowTrack("direct-1")},${responsiveAlbum("responsive-album-browse", "responsive-album-playlist")}]}}]}}}}]}}}"""

    private fun tracksPageResponse(
        visitorData: String,
        continuationToken: String?,
        vararg trackIds: String,
    ): String {
        val continuation = continuationToken?.let {
            """, "continuations":[{"nextContinuationData":{"continuation":"$it"}}]"""
        }.orEmpty()
        val tracks = trackIds.joinToString(",") { responsiveTrack(it) }
        return """{"responseContext":{"visitorData":"$visitorData"},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$tracks]$continuation}}}"""
    }

    private fun twoRowTrack(id: String): String =
        """{"musicTwoRowItemRenderer":{"navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}},"title":{"runs":[{"text":"Song $id"}]},"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/$id"}]}}}}}"""

    private fun twoRowPlaylist(id: String): String =
        """{"musicTwoRowItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"VL$id"}},"title":{"runs":[{"text":"Playlist $id"}]},"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/$id"}]}}}}}"""

    private fun twoRowAlbum(browseId: String, playlistId: String): String =
        """{"musicTwoRowItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"}},"title":{"runs":[{"text":"Album $browseId"}]},"thumbnailOverlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"$playlistId"}}}}}}}}"""

    private fun responsiveAlbum(browseId: String, playlistId: String): String =
        """{"musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album $browseId"}]}}}],"overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"$playlistId"}}}}}},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/$browseId"}]}}}}}"""

    private fun responsiveTrack(id: String): String =
        """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $id"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/$id"}]}}}}}"""
}

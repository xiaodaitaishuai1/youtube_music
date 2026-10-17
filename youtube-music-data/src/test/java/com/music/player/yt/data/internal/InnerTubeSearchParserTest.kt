package com.music.player.yt.data.internal

import com.music.player.yt.data.Album
import com.music.player.yt.data.Artist
import com.music.player.yt.data.Playlist
import com.music.player.yt.data.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class InnerTubeSearchParserTest {
    @Test fun singleShelfKeepsExistingResult() = assertEquals(listOf("first"), fixture("single").items.map { it.id })
    @Test fun allShelvesPreserveOrder() = assertEquals(listOf("first", "second"), fixture("multi").items.map { it.id })
    @Test fun knownItemSectionsCarrySearchRows() {
        assertEquals(listOf("one", "two"), parse(initial("""{"itemSectionRenderer":{"contents":[${row("one")},${row("two")}]}}""")).items.map { it.id })
    }
    @Test fun capturedChineseAllIncludesArtistCardAndEarlierResults() {
        val items = fixture("real/jay-cn-all").items
        assertTrue(items.first() is Artist)
        assertTrue(items.filterIsInstance<Track>().isNotEmpty())
        assertTrue(items.filterIsInstance<Playlist>().isNotEmpty())
    }
    @Test fun capturedNoResultsMessageIsEmptySuccess() = assertTrue(fixture("real/no-results-artists").items.isEmpty())
    @Test fun trailingIncompleteCardDoesNotHideEarlierShelf() = assertEquals(listOf("first"), fixture("trailing-card").items.map { it.id })
    @Test fun appendCommandsReturnsRows() = assertEquals(listOf("next"), fixture("append-commands").items.map { it.id })
    @Test fun appendActionsReturnsRows() = assertEquals(listOf("next"), fixture("append-actions").items.map { it.id })

    @Test fun selectsContentTabAfterEmptyFirstTab() {
        assertEquals(listOf("selected"), parse(tabs("{\"tabRenderer\":{}}", tab(shelf(row("selected")), selected = true))).items.map { it.id })
    }
    @Test fun emptyUnselectedSectionListDoesNotMaskLaterResults() {
        assertEquals(listOf("later"), parse(tabs(tab(""), tab(shelf(row("later"))))).items.map { it.id })
    }
    @Test fun selectedTabExcludesUnselectedResults() {
        assertEquals(listOf("selected"), parse(tabs(tab(shelf(row("wrong"))), tab(shelf(row("selected")), true))).items.map { it.id })
    }
    @Test fun selectedEmptyTabDoesNotFallBackToUnselectedResults() {
        assertTrue(parse(tabs(tab(shelf(row("wrong"))), tab(shelf(), true))).items.isEmpty())
    }
    @Test fun deduplicatesByModelAndStableId() {
        val artist = twoRow("same", "Artist", "MUSIC_PAGE_TYPE_ARTIST")
        val result = parse(initial(shelf(row("same"), artist), shelf(row("same"), row("other"))))
        assertEquals(listOf("same", "same", "other"), result.items.map { it.id })
        assertTrue(result.items[0] is Track)
        assertTrue(result.items[1] is Artist)
    }
    @Test fun cardArtistUsesItsEndpointAndKeepsInternalRows() {
        val card = """{"musicCardShelfRenderer":{"title":{"runs":[{"text":"Singer"}]},"onTap":{"browseEndpoint":{"browseId":"UC-singer","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}},"contents":[${row("inside")}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/singer"}]}}}}}"""
        val items = parse(initial(card, shelf(row("inside")))).items
        assertEquals(listOf("UC-singer", "inside"), items.map { it.id })
        assertEquals(Artist("Singer", "UC-singer", "https://img/singer"), items.first())
    }
    @Test fun songCardUsesPlaybackEndpoint() {
        val card = """{"musicCardShelfRenderer":{"title":{"runs":[{"text":"Featured song"}]},"onTap":{"watchEndpoint":{"videoId":"featured"}}}}"""
        assertEquals(Track("featured", "Featured song"), parse(initial(card)).items.single())
    }
    @Test fun responsiveArtistAndPlaylistUseFlexColumnTitle() {
        val artist = responsiveBrowse("UC-singer", "Singer", "MUSIC_PAGE_TYPE_ARTIST")
        val playlist = responsiveBrowse("VL-list", "Playlist", "MUSIC_PAGE_TYPE_PLAYLIST")
        val items = parse(initial(shelf(artist, playlist))).items
        assertTrue(items[0] is Artist)
        assertEquals("Singer", items[0].title)
        assertTrue(items[1] is Playlist)
        assertEquals("-list", items[1].id)
    }
    @Test fun twoRowTypesFollowEndpointPageType() {
        val items = parse(initial(shelf(twoRow("non-UC", "Singer", "MUSIC_PAGE_TYPE_ARTIST"), twoRow("MPRE-album", "Album", "MUSIC_PAGE_TYPE_ALBUM"), twoRow("VL-list", "List", "MUSIC_PAGE_TYPE_PLAYLIST")))).items
        assertTrue(items[0] is Artist)
        assertTrue(items[1] is Album)
        assertTrue(items[2] is Playlist)
    }
    @Test fun unknownBrowseTypeIsNotInventedAsAlbum() {
        assertThrows(IllegalArgumentException::class.java) { parse(initial(shelf(twoRow("unknown", "Unknown", "MUSIC_PAGE_TYPE_UNKNOWN")))) }
    }
    @Test fun userChannelKeepsExistingArtistModel() {
        assertEquals(Artist("Channel", "UC-channel"), parse(initial(shelf(twoRow("UC-channel", "Channel", "MUSIC_PAGE_TYPE_USER_CHANNEL")))).items.single())
    }
    @Test fun unsupportedPodcastIsCountedWithoutInventingAlbum() {
        val response = Json.parseToJsonElement(initial(shelf(row("ok"), twoRow("MPSP-show", "Podcast", "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE")))).jsonObject
        val result = InnerTubeParser.searchWithDiagnostics(response)
        assertEquals(listOf("ok"), result.page.items.map { it.id })
        assertEquals(1, result.diagnostics.unsupported)
        assertEquals(0, result.diagnostics.failed)
    }
    @Test fun knownEmptyShelfIsSuccess() = assertTrue(parse(initial(shelf())).items.isEmpty())
    @Test fun unknownRootFailsInsteadOfEmptySuccess() { assertThrows(IllegalArgumentException::class.java) { parse("{}") } }
    @Test fun allNonEmptyRowsMalformedFails() {
        assertThrows(IllegalArgumentException::class.java) { parse(initial(shelf("{\"musicResponsiveListItemRenderer\":{\"title\":{\"runs\":[{\"text\":\"Missing ID\"}]}}}"))) }
    }
    @Test fun partialFailurePreservesValidRows() {
        assertEquals(listOf("ok"), parse(initial(shelf(row("ok"), "{\"musicResponsiveListItemRenderer\":{}}"))).items.map { it.id })
    }
    @Test fun appendControlItemIsTokenNotResult() {
        val action = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${row("next")},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"opaque+/%="}}}}]}}]}"""
        val page = parse(action)
        assertEquals(listOf("next"), page.items.map { it.id })
        assertEquals("opaque+/%=", page.nextPageToken)
    }
    @Test fun appendWithoutTokenEndsPagination() = assertNull(fixture("append-actions").nextPageToken)
    @Test fun unrelatedAppendTargetsAreNotCombined() {
        val action = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":{"sectionListRenderer":{"targetId":"search-feed","contents":[]}}}}]}},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"targetId":"other","continuationItems":[${row("wrong")}] }},{"appendContinuationItemsAction":{"targetId":"search-feed","continuationItems":[${row("right")}]}}]}"""
        assertEquals(listOf("right"), parse(action).items.map { it.id })
    }
    @Test fun ambiguousAppendTargetsFail() {
        val action = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"targetId":"one","continuationItems":[${row("one")}] }},{"appendContinuationItemsAction":{"targetId":"two","continuationItems":[${row("two")}]}}]}"""
        assertThrows(IllegalArgumentException::class.java) { parse(action) }
    }
    @Test fun nestedKnownContainerTargetSelectsMatchingAppend() {
        for (wrapper in listOf(
            """{"itemSectionRenderer":{"contents":[{"musicShelfRenderer":{"targetId":"search-feed","contents":[]}}]}}""",
            """{"itemSectionRenderer":{"targetId":"search-feed","contents":[]}}""",
        )) {
            val response = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":{"sectionListRenderer":{"contents":[$wrapper]}}}}]}},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"targetId":"other","continuationItems":[${row("wrong")}] }},{"appendContinuationItemsAction":{"targetId":"search-feed","continuationItems":[${row("right")}]}}]}"""
            assertEquals(listOf("right"), parse(response).items.map { it.id })
        }
    }
    @Test fun multipleMatchingShelfTargetsAreStillAmbiguous() {
        val response = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"targetId":"one","contents":[]}},{"musicShelfRenderer":{"targetId":"two","contents":[]}}]}}}}]}},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"targetId":"one","continuationItems":[${row("one")}] }},{"appendContinuationItemsAction":{"targetId":"two","continuationItems":[${row("two")}]}}]}"""
        assertThrows(IllegalArgumentException::class.java) { parse(response) }
    }
    @Test fun malformedKnownContainersFailRatherThanEmptySuccess() {
        for (body in listOf(initial("{\"musicShelfRenderer\":{}}"), """{"continuationContents":{"musicShelfContinuation":{"contents":{}}}}""", """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{}}]}""")) {
            assertThrows(IllegalArgumentException::class.java) { parse(body) }
        }
    }
    @Test fun numericYearIsNotTrackDuration() {
        val response = initial(shelf("""{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"one"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Title"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"2026"}]}}}]}}"""))
        assertNull((parse(response).items.single() as Track).durationSeconds)
    }
    @Test fun independentCardControlTokenIsNotAllContinuation() {
        val response = initial("""{"musicCardShelfRenderer":{"title":{"runs":[{"text":"Song"}]},"onTap":{"watchEndpoint":{"videoId":"one"}},"contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"card-only"}}}}]}}""")
        assertNull(parse(response).nextPageToken)
    }
    @Test fun sectionListControlItemIsGlobalToken() {
        val response = initial(shelf(row("one")), """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"global-control"}}}}""")
        assertEquals("global-control", parse(response).nextPageToken)
    }
    @Test fun allDoesNotAdoptIndependentShelfTokens() {
        assertNull(parse(initial(shelfWithToken("one", row("one")), shelfWithToken("two", row("two")))).nextPageToken)
    }
    @Test fun allDoesNotAdoptShelfControlItemToken() {
        assertNull(parse(initial(shelf(row("one"), """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"shelf-only"}}}}"""))).nextPageToken)
    }
    @Test fun allContinuationDoesNotAdoptIndependentShelfToken() {
        val response = Json.parseToJsonElement(initial(shelfWithToken("independent", row("one")))).jsonObject
        assertNull(InnerTubeParser.search(response, com.music.player.yt.data.SearchFilter.ALL, continuation = true).nextPageToken)
    }
    @Test fun cardWithOnlyPlaybackOverlayIsAnEntity() {
        val response = initial("""{"musicCardShelfRenderer":{"title":{"runs":[{"text":"Song"}]},"thumbnailOverlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"overlay-song"}}}}}}}}""")
        assertEquals("overlay-song", parse(response).items.single().id)
    }
    @Test fun searchKeepsExplicitBadgeAndFixedDuration() {
        val response = initial(shelf("""{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"one"},"title":{"runs":[{"text":"Title"}]},"badges":[{"musicInlineBadgeRenderer":{"icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}],"fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:44"}]}}}]}}"""))
        val track = parse(response).items.single() as Track
        assertTrue(track.explicit)
        assertEquals(224, track.durationSeconds)
    }
    @Test fun filteredShelfKeepsItsToken() {
        val response = Json.parseToJsonElement(initial(shelfWithToken("filtered", row("one")))).jsonObject
        assertEquals("filtered", InnerTubeParser.search(response, com.music.player.yt.data.SearchFilter.SONGS).nextPageToken)
    }
    @Test fun allKeepsSectionListToken() {
        val response = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":{"sectionListRenderer":{"contents":[${shelf(row("one"))}],"continuations":[{"nextContinuationData":{"continuation":"global"}}]}}}}]}}}"""
        assertEquals("global", parse(response).nextPageToken)
    }
    @Test fun capturedResponseMatrixParsesEveryCandidate() {
        val fixtures = java.io.File("src/test/resources/search/real").listFiles()!!.filter { it.extension == "json" }
        assertTrue(fixtures.size >= 20)
        fixtures.forEach { file ->
            val filter = com.music.player.yt.data.SearchFilter.entries.first { file.nameWithoutExtension.removeSuffix("-next").endsWith(it.name.lowercase()) }
            val parsed = InnerTubeParser.searchWithDiagnostics(Json.parseToJsonElement(file.readText()).jsonObject, filter, file.nameWithoutExtension.endsWith("-next"))
            assertEquals("${file.name}: ${parsed.diagnostics}", 0, parsed.diagnostics.failed)
            if (file.name == "no-results-artists.json") assertTrue(parsed.page.items.isEmpty()) else assertTrue(file.name, parsed.page.items.isNotEmpty())
        }
    }

    private fun fixture(name: String) = parse(checkNotNull(javaClass.getResource("/search/$name.json")).readText())
    private fun parse(text: String) = InnerTubeParser.search(Json.parseToJsonElement(text).jsonObject)
    private fun initial(vararg sections: String) = tabs(tab(sections.joinToString(",")))
    private fun tabs(vararg tabs: String) = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[${tabs.joinToString(",")}]}}}"""
    private fun tab(sections: String, selected: Boolean = false) = """{"tabRenderer":{"selected":$selected,"content":{"sectionListRenderer":{"contents":[$sections]}}}}"""
    private fun shelf(vararg rows: String) = """{"musicShelfRenderer":{"contents":[${rows.joinToString(",")}]}}"""
    private fun shelfWithToken(token: String, row: String) = """{"musicShelfRenderer":{"contents":[$row],"continuations":[{"nextContinuationData":{"continuation":"$token"}}]}}"""
    private fun row(id: String) = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},"title":{"runs":[{"text":"Song $id"}]}}}"""
    private fun endpoint(id: String, type: String) = """{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$type"}}}}"""
    private fun twoRow(id: String, title: String, type: String) = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$title"}]},"navigationEndpoint":${endpoint(id, type)}}}"""
    private fun responsiveBrowse(id: String, title: String, type: String) = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}}],"navigationEndpoint":${endpoint(id, type)}}}"""
}

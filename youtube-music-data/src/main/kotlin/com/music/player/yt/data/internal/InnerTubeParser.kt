package com.music.player.yt.data.internal

import com.music.player.yt.data.Album
import com.music.player.yt.data.AlbumPage
import com.music.player.yt.data.Artist
import com.music.player.yt.data.ArtistPage
import com.music.player.yt.data.ArtistSection
import com.music.player.yt.data.BrowseEndpoint
import com.music.player.yt.data.CatalogItem
import com.music.player.yt.data.HomeSection
import com.music.player.yt.data.Lyrics
import com.music.player.yt.data.MoodAndGenres
import com.music.player.yt.data.Page
import com.music.player.yt.data.Playlist
import com.music.player.yt.data.PlaylistPage
import com.music.player.yt.data.RelatedContent
import com.music.player.yt.data.SearchSuggestions
import com.music.player.yt.data.SearchFilter
import com.music.player.yt.data.Track
import com.music.player.yt.data.TrackPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object InnerTubeParser {
    fun home(response: JsonObject): Page<HomeSection> {
        val initialSectionList = response.arrayAt(
            "contents", "singleColumnBrowseResultsRenderer", "tabs",
        ).firstObject("tabRenderer")?.objectAt("content", "sectionListRenderer")
            ?: response.objectAt("contents", "sectionListRenderer")
        val continuationSectionList = response.objectAt("continuationContents", "sectionListContinuation")
        val contents = initialSectionList?.arrayAt("contents")
            ?: continuationSectionList?.arrayAt("contents")
            ?: JsonArray(emptyList())
        return Page(
            items = contents.mapNotNull { content -> content.asObjectOrNull()?.let(::homeSection) },
            nextPageToken = initialSectionList?.continuationToken() ?: continuationSectionList?.continuationToken(),
        )
    }

    fun homeUnavailableReason(response: JsonObject): String? {
        fun findMessage(element: JsonElement?): String? = when (element) {
            is JsonObject -> {
                val message = element.objectAt("messageRenderer")
                if (message?.stringAt("icon", "iconType") == "MUSIC_UNAVAILABLE") {
                    message.objectAt("text")?.runsText()?.takeIf(String::isNotBlank)
                        ?: "YouTube Music is unavailable"
                } else {
                    element.values.firstNotNullOfOrNull(::findMessage)
                }
            }
            is JsonArray -> element.firstNotNullOfOrNull(::findMessage)
            else -> null
        }
        return findMessage(response.objectAt("contents"))
            ?: findMessage(response.objectAt("continuationContents"))
    }

    fun moodAndGenres(response: JsonObject): List<MoodAndGenres> = sectionContents(response).mapNotNull { content ->
        val grid = content.asObjectOrNull()?.objectAt("gridRenderer") ?: return@mapNotNull null
        val title = grid.objectAt("header", "gridHeaderRenderer", "title")?.runsText()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        MoodAndGenres(
            title = title,
            items = grid.arrayAt("items").mapNotNull(::moodAndGenresItem),
        )
    }

    fun moodContentItems(response: JsonObject): List<CatalogItem> = sectionContents(response).flatMap { content ->
        val section = content.asObjectOrNull() ?: return@flatMap emptyList()
        val gridItems = section.objectAt("gridRenderer")?.arrayAt("items").orEmpty()
        val carouselItems = section.objectAt("musicCarouselShelfRenderer")?.arrayAt("contents").orEmpty()
        (gridItems + carouselItems).mapNotNull(::catalogItem)
    }

    fun search(response: JsonObject, filter: SearchFilter = SearchFilter.ALL, continuation: Boolean = false): Page<CatalogItem> =
        searchWithDiagnostics(response, filter, continuation).page

    fun searchWithDiagnostics(response: JsonObject, filter: SearchFilter = SearchFilter.ALL, continuation: Boolean = false): InnerTubeSearchParser.ParsedPage =
        InnerTubeSearchParser.parse(response, filter, continuation, ::searchCatalogItem, ::searchCardItem)

    private fun searchCatalogItem(element: kotlinx.serialization.json.JsonElement): CatalogItem? {
        val value = element.asObjectOrNull() ?: return null
        val renderer = value.objectAt("musicResponsiveListItemRenderer") ?: value.objectAt("musicTwoRowItemRenderer") ?: return null
        val titleText = renderer.arrayAt("flexColumns").firstOrNull().asObjectOrNull()
            ?.objectAt("musicResponsiveListItemFlexColumnRenderer", "text") ?: renderer.objectAt("title") ?: return null
        val endpoint = renderer.objectAt("navigationEndpoint")
            ?: titleText.arrayAt("runs").firstNotNullOfOrNull { it.asObjectOrNull()?.objectAt("navigationEndpoint") }
            ?: renderer.objectAt("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint")
            ?: renderer.objectAt("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint")
        return searchEntity(renderer, titleText, endpoint)
    }

    private fun searchCardItem(card: JsonObject): CatalogItem? {
        val title = card.objectAt("title") ?: return null
        val endpoint = card.objectAt("onTap") ?: card.objectAt("navigationEndpoint")
            ?: title.arrayAt("runs").firstNotNullOfOrNull { it.asObjectOrNull()?.objectAt("navigationEndpoint") }
            ?: card.objectAt("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint")
        return searchEntity(card, title, endpoint)
    }

    private fun searchEntity(renderer: JsonObject, titleText: JsonObject, endpoint: JsonObject?): CatalogItem? {
        val title = titleText.runsText().takeIf(String::isNotBlank) ?: return null
        val columns = renderer.arrayAt("flexColumns").mapNotNull { it.asObjectOrNull()?.objectAt("musicResponsiveListItemFlexColumnRenderer", "text") }
        val metadata = renderer.objectAt("subtitle") ?: columns.getOrNull(1)
        val videoId = renderer.stringAt("playlistItemData", "videoId") ?: endpoint?.stringAt("watchEndpoint", "videoId")
        if (!videoId.isNullOrBlank()) {
            val artists = (listOfNotNull(metadata) + columns.drop(1)).flatMap { it.artistsFromRuns() }
                .filter { it.channelId?.let { id -> !id.startsWith("MPRE") && !id.startsWith("VL") } == true }.distinctBy { it.id }
            val album = columns.drop(1).firstNotNullOfOrNull { text ->
                text.arrayAt("runs").firstNotNullOfOrNull { run ->
                    val item = run.asObjectOrNull() ?: return@firstNotNullOfOrNull null
                    val id = item.stringAt("navigationEndpoint", "browseEndpoint", "browseId") ?: return@firstNotNullOfOrNull null
                    if (!id.startsWith("MPRE")) null else item.stringAt("text")?.let { Album(id, it) }
                }
            }
            val fixed = renderer.arrayAt("fixedColumns").mapNotNull { it.asObjectOrNull()?.objectAt("musicResponsiveListItemFixedColumnRenderer", "text") }
            val duration = (fixed + listOfNotNull(metadata) + columns.drop(1)).firstNotNullOfOrNull { text ->
                text.arrayAt("runs").firstNotNullOfOrNull {
                    it.asObjectOrNull()?.stringAt("text")?.takeIf { value -> Regex("\\d+:\\d{2}(?::\\d{2})?").matches(value) }?.durationSeconds()
                }
            }
            return Track(videoId, title, artists, album, duration, renderer.thumbnailUrl(), renderer.searchExplicitBadge())
        }
        val browse = endpoint?.objectAt("browseEndpoint") ?: return null
        val id = browse.stringAt("browseId")?.takeIf(String::isNotBlank) ?: return null
        val pageType = browse.stringAt("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")
        return when {
            pageType in setOf("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL") || (pageType == null && id.startsWith("UC")) -> Artist(title, id, renderer.thumbnailUrl())
            pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || (pageType == null && id.startsWith("VL")) -> Playlist(id.removePrefix("VL"), title, metadata?.artistsFromRuns()?.firstOrNull(), metadata?.runsText(), renderer.thumbnailUrl())
            pageType == "MUSIC_PAGE_TYPE_ALBUM" || (pageType == null && id.startsWith("MPRE")) -> Album(
                id, title, metadata?.artistsFromRuns().orEmpty(),
                renderer.stringAt("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId")
                    ?: renderer.stringAt("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId"),
                metadata?.runsText()?.fourDigitYear(), renderer.thumbnailUrl(), renderer.searchExplicitBadge(),
            )
            else -> null
        }
    }

    private fun JsonObject.searchExplicitBadge(): Boolean = (arrayAt("badges") + arrayAt("subtitleBadges")).any {
        it.asObjectOrNull()?.stringAt("musicInlineBadgeRenderer", "icon", "iconType") == "MUSIC_EXPLICIT_BADGE"
    }

    fun suggestions(response: JsonObject): SearchSuggestions {
        val contents = response.arrayAt("contents")
        val queries = contents.getOrNull(0).asObjectOrNull()
            ?.objectAt("searchSuggestionsSectionRenderer")
            ?.arrayAt("contents")
            ?.mapNotNull { it.asObjectOrNull()?.objectAt("searchSuggestionRenderer", "suggestion")?.runsText() }
            .orEmpty()
        val recommendations = contents.getOrNull(1).asObjectOrNull()
            ?.objectAt("searchSuggestionsSectionRenderer")
            ?.arrayAt("contents")
            ?.mapNotNull(::catalogItem)
            .orEmpty()
        return SearchSuggestions(queries, recommendations)
    }

    fun album(response: JsonObject, browseId: String): AlbumPage {
        val header = detailHeader(response)
        val canonical = response.stringAt("microformat", "microformatDataRenderer", "urlCanonical")
        val playlistId = canonical?.substringAfter("list=", "")?.takeIf(String::isNotEmpty)
        val album = Album(
            browseId = browseId,
            title = header?.objectAt("title")?.runsText().orEmpty(),
            artists = header?.objectAt("straplineTextOne")?.artistsFromRuns().orEmpty(),
            playlistId = playlistId,
            year = header?.objectAt("subtitle")?.runsText()?.fourDigitYear(),
            thumbnailUrl = header?.thumbnailUrl(),
        )
        val shelf = playlistShelf(response)
        return AlbumPage(
            album = album,
            songs = shelf?.arrayAt("contents")?.mapNotNull(::trackFromShelf).orEmpty(),
            nextPageToken = shelf?.continuationToken(),
        )
    }

    fun playlist(response: JsonObject, playlistId: String): PlaylistPage {
        val header = detailHeader(response)
        val playlist = Playlist(
            id = playlistId,
            title = header?.objectAt("title")?.runsText().orEmpty(),
            author = header?.objectAt("straplineTextOne")?.artistsFromRuns()?.firstOrNull(),
            songCountText = header?.objectAt("secondSubtitle")?.runsText(),
            thumbnailUrl = header?.thumbnailUrl(),
        )
        val shelf = playlistShelf(response)
        return PlaylistPage(playlist, shelf?.arrayAt("contents")?.mapNotNull(::trackFromShelf).orEmpty(), shelf?.continuationToken())
    }

    fun tracksPage(response: JsonObject): Page<Track> {
        val initial = playlistShelf(response)
        val continuation = response.objectAt("continuationContents", "musicPlaylistShelfContinuation")
        val shelf = initial ?: continuation ?: return Page(emptyList())
        return Page(shelf.arrayAt("contents").mapNotNull(::trackFromShelf), shelf.continuationToken())
    }

    fun artist(response: JsonObject, browseId: String): ArtistPage {
        val header = response.objectAt("header", "musicImmersiveHeaderRenderer")
            ?: response.objectAt("header", "musicVisualHeaderRenderer")
        val artist = Artist(
            name = header?.objectAt("title")?.runsText().orEmpty().ifBlank { browseId },
            channelId = browseId,
            thumbnailUrl = header?.thumbnailUrl(),
        )
        val contents = response.arrayAt(
            "contents", "singleColumnBrowseResultsRenderer", "tabs",
        ).firstObject("tabRenderer")?.arrayAt("content", "sectionListRenderer", "contents")
            ?: JsonArray(emptyList())
        val sections = contents.mapNotNull { content ->
            val section = content.asObjectOrNull() ?: return@mapNotNull null
            val shelf = section.objectAt("musicShelfRenderer")
            val carousel = section.objectAt("musicCarouselShelfRenderer")
            when {
                shelf != null -> ArtistSection(
                    title = shelf.objectAt("title")?.runsText().orEmpty(),
                    items = shelf.arrayAt("contents").mapNotNull(::catalogItem),
                    nextPageToken = shelf.continuationToken(),
                )
                carousel != null -> ArtistSection(
                    title = carousel.objectAt("header", "musicCarouselShelfBasicHeaderRenderer", "title")?.runsText().orEmpty(),
                    items = carousel.arrayAt("contents").mapNotNull(::catalogItem),
                )
                else -> null
            }
        }
        return ArtistPage(artist, sections, header?.objectAt("description")?.runsText()?.takeIf(String::isNotBlank))
    }

    fun track(response: JsonObject, videoId: String): TrackPage {
        val tracks = nextTracks(response)
        return TrackPage(tracks.firstOrNull { it.first }?.second ?: tracks.firstOrNull()?.second ?: Track(videoId, videoId))
    }

    fun lyrics(response: JsonObject): Lyrics? {
        val text = response.objectAt("contents", "sectionListRenderer")?.arrayAt("contents")
            ?.firstNotNullOfOrNull { it.asObjectOrNull()?.objectAt("musicDescriptionShelfRenderer", "description")?.runsText() }
            ?.takeIf(String::isNotBlank)
        return text?.let(::Lyrics)
    }

    fun related(response: JsonObject): RelatedContent {
        val items = response.objectAt("contents", "sectionListRenderer")?.arrayAt("contents").orEmpty()
            .flatMap { section -> section.asObjectOrNull()?.objectAt("musicCarouselShelfRenderer")?.arrayAt("contents").orEmpty() }
            .mapNotNull(::catalogItem)
        return RelatedContent(
            songs = items.filterIsInstance<Track>(),
            albums = items.filterIsInstance<Album>(),
            artists = items.filterIsInstance<Artist>(),
            playlists = items.filterIsInstance<Playlist>(),
        )
    }

    fun lyricsBrowseId(nextResponse: JsonObject): String? = nextBrowseId(nextResponse, 1)

    fun relatedBrowseId(nextResponse: JsonObject): String? = nextBrowseId(nextResponse, 2)

    private fun homeSection(section: JsonObject): HomeSection? = when {
        section.objectAt("musicCarouselShelfRenderer") != null -> homeCarouselSection(
            checkNotNull(section.objectAt("musicCarouselShelfRenderer")),
        )
        section.objectAt("gridRenderer") != null -> homeGridSection(checkNotNull(section.objectAt("gridRenderer")))
        else -> null
    }

    private fun homeCarouselSection(renderer: JsonObject): HomeSection? {
        val header = renderer.objectAt("header", "musicCarouselShelfBasicHeaderRenderer")
        val items = renderer.arrayAt("contents").mapNotNull(::catalogItem)
        if (items.isEmpty()) return null
        return HomeSection(
            title = header?.objectAt("title")?.runsText().orEmpty(),
            label = header?.objectAt("strapline")?.runsText()?.takeIf(String::isNotBlank),
            thumbnailUrl = header?.thumbnailUrl(),
            items = items,
            endpoint = header?.browseEndpointAt("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint"),
        )
    }

    private fun homeGridSection(renderer: JsonObject): HomeSection? {
        val items = renderer.arrayAt("items").mapNotNull(::catalogItem)
        if (items.isEmpty()) return null
        return HomeSection(
            title = renderer.objectAt("header", "gridHeaderRenderer", "title")?.runsText().orEmpty(),
            items = items,
        )
    }

    private fun moodAndGenresItem(element: kotlinx.serialization.json.JsonElement): MoodAndGenres.Item? {
        val renderer = element.asObjectOrNull()?.objectAt("musicNavigationButtonRenderer") ?: return null
        val title = renderer.objectAt("buttonText")?.runsText()?.takeIf(String::isNotBlank) ?: return null
        val stripeColor = renderer.longAt("solid", "leftStripeColor") ?: return null
        val endpoint = renderer.objectAt("clickCommand", "browseEndpoint") ?: return null
        val browseId = endpoint.stringAt("browseId")?.takeIf(String::isNotBlank) ?: return null
        return MoodAndGenres.Item(title, stripeColor, BrowseEndpoint(browseId, endpoint.stringAt("params")))
    }

    private fun JsonObject.browseEndpointAt(vararg path: String): BrowseEndpoint? {
        val endpoint = objectAt(*path) ?: return null
        val browseId = endpoint.stringAt("browseId")?.takeIf(String::isNotBlank) ?: return null
        return BrowseEndpoint(browseId, endpoint.stringAt("params"))
    }

    private fun catalogItem(element: kotlinx.serialization.json.JsonElement): CatalogItem? {
        val content = element.asObjectOrNull() ?: return null
        content.objectAt("musicResponsiveListItemRenderer")?.let { return responsiveItem(it) }
        content.objectAt("musicTwoRowItemRenderer")?.let { return twoRowItem(it) }
        return null
    }

    private fun responsiveItem(renderer: JsonObject): CatalogItem? {
        val videoId = renderer.stringAt("playlistItemData", "videoId")
            ?: renderer.stringAt("navigationEndpoint", "watchEndpoint", "videoId")
        val title = renderer.arrayAt("flexColumns").getOrNull(0).asObjectOrNull()
            ?.objectAt("musicResponsiveListItemFlexColumnRenderer", "text")?.runsText()
            ?.takeIf(String::isNotBlank)
            ?: renderer.objectAt("title")?.runsText()?.takeIf(String::isNotBlank)
            ?: return null
        if (videoId != null) {
            val columns = renderer.arrayAt("flexColumns")
            return Track(
                id = videoId,
                title = title,
                artists = columns.getOrNull(1).asObjectOrNull()
                    ?.objectAt("musicResponsiveListItemFlexColumnRenderer", "text")?.artistsFromRuns().orEmpty(),
                album = columns.getOrNull(2).asObjectOrNull()
                    ?.objectAt("musicResponsiveListItemFlexColumnRenderer", "text")?.albumFromRuns(),
                durationSeconds = columns.getOrNull(3).asObjectOrNull()
                    ?.objectAt("musicResponsiveListItemFlexColumnRenderer", "text")?.runsText()?.durationSeconds(),
                thumbnailUrl = renderer.thumbnailUrl(),
                explicit = renderer.hasExplicitBadge(),
            )
        }
        val browseId = renderer.stringAt("navigationEndpoint", "browseEndpoint", "browseId")
            ?.takeIf(String::isNotBlank) ?: return null
        if (renderer.isArtistItem(browseId)) return Artist(title, browseId, renderer.thumbnailUrl())
        if (!browseId.startsWith("VL")) {
            return Album(
                browseId = browseId,
                title = title,
                artists = renderer.arrayAt("flexColumns").getOrNull(1).asObjectOrNull()
                    ?.objectAt("musicResponsiveListItemFlexColumnRenderer", "text")?.artistsFromRuns().orEmpty(),
                playlistId = renderer.stringAt(
                    "overlay",
                    "musicItemThumbnailOverlayRenderer",
                    "content",
                    "musicPlayButtonRenderer",
                    "playNavigationEndpoint",
                    "watchPlaylistEndpoint",
                    "playlistId",
                ),
                year = renderer.arrayAt("flexColumns").getOrNull(1).asObjectOrNull()
                    ?.objectAt("musicResponsiveListItemFlexColumnRenderer", "text")?.runsText()?.fourDigitYear(),
                thumbnailUrl = renderer.thumbnailUrl(),
                explicit = renderer.hasExplicitBadge(),
            )
        }
        return twoRowItem(renderer)
    }

    private fun twoRowItem(renderer: JsonObject): CatalogItem? {
        val title = renderer.objectAt("title")?.runsText()?.takeIf(String::isNotBlank) ?: return null
        val navigation = renderer.objectAt("navigationEndpoint")
        val videoId = navigation?.stringAt("watchEndpoint", "videoId")
        if (videoId != null) return Track(videoId, title, renderer.objectAt("subtitle")?.artistsFromRuns().orEmpty(), thumbnailUrl = renderer.thumbnailUrl(), explicit = renderer.hasExplicitBadge())
        val browseId = navigation?.stringAt("browseEndpoint", "browseId")?.takeIf(String::isNotBlank) ?: return null
        if (renderer.isArtistItem(browseId)) return Artist(title, browseId, renderer.thumbnailUrl())
        if (browseId.startsWith("VL")) return Playlist(
            id = browseId.removePrefix("VL"),
            title = title,
            author = renderer.objectAt("subtitle")?.artistsFromRuns()?.firstOrNull(),
            songCountText = renderer.objectAt("subtitle")?.runsText(),
            thumbnailUrl = renderer.thumbnailUrl(),
        )
        return Album(
            browseId = browseId,
            title = title,
            artists = renderer.objectAt("subtitle")?.artistsFromRuns().orEmpty(),
            playlistId = renderer.stringAt("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId"),
            year = renderer.objectAt("subtitle")?.runsText()?.fourDigitYear(),
            thumbnailUrl = renderer.thumbnailUrl(),
            explicit = renderer.hasExplicitBadge(),
        )
    }

    private fun JsonObject.isArtistItem(browseId: String): Boolean {
        val pageType = stringAt(
            "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
            "browseEndpointContextMusicConfig", "pageType",
        )
        return pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" ||
            (pageType == null && browseId.startsWith("UC"))
    }

    private fun trackFromShelf(element: kotlinx.serialization.json.JsonElement): Track? =
        catalogItem(element) as? Track

    private fun detailHeader(response: JsonObject): JsonObject? {
        val first = response.arrayAt(
            "contents", "twoColumnBrowseResultsRenderer", "tabs",
        ).firstObject("tabRenderer")?.arrayAt("content", "sectionListRenderer", "contents")?.firstOrNull()?.asObjectOrNull()
        return first?.objectAt("musicResponsiveHeaderRenderer")
            ?: first?.objectAt("musicEditablePlaylistDetailHeaderRenderer", "header", "musicResponsiveHeaderRenderer")
    }

    private fun playlistShelf(response: JsonObject): JsonObject? = response.objectAt(
        "contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer",
    )?.arrayAt("contents")?.firstOrNull()?.asObjectOrNull()?.objectAt("musicPlaylistShelfRenderer")

    private fun nextTracks(response: JsonObject): List<Pair<Boolean, Track>> {
        val continuation = response.objectAt("continuationContents", "playlistPanelContinuation")
        val panel = continuation ?: response.arrayAt(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs",
        ).firstObject("tabRenderer")?.objectAt("content", "musicQueueRenderer", "content", "playlistPanelRenderer")
        return panel?.arrayAt("contents").orEmpty().mapNotNull { entry ->
            val renderer = entry.asObjectOrNull()?.objectAt("playlistPanelVideoRenderer") ?: return@mapNotNull null
            val track = Track(
                id = renderer.stringAt("videoId") ?: return@mapNotNull null,
                title = renderer.objectAt("title")?.runsText() ?: return@mapNotNull null,
                artists = renderer.objectAt("longBylineText")?.artistsFromRuns().orEmpty(),
                durationSeconds = renderer.objectAt("lengthText")?.runsText()?.durationSeconds(),
                thumbnailUrl = renderer.objectAt("thumbnail")?.arrayAt("thumbnails")?.lastOrNull()?.asObjectOrNull()?.stringAt("url"),
                explicit = renderer.hasExplicitBadge(),
            )
            (renderer.stringAt("selected") == "true") to track
        }
    }

    private fun nextBrowseId(response: JsonObject, index: Int): String? = response.arrayAt(
        "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs",
    ).getOrNull(index).asObjectOrNull()?.objectAt("tabRenderer")?.stringAt("endpoint", "browseEndpoint", "browseId")

    private fun JsonObject.thumbnailUrl(): String? {
        fun fromThumbnail(node: JsonObject?): String? = node?.arrayAt("thumbnails")?.lastOrNull()?.asObjectOrNull()?.stringAt("url")
        return fromThumbnail(objectAt("thumbnail", "musicThumbnailRenderer", "thumbnail"))
            ?: fromThumbnail(objectAt("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail"))
            ?: fromThumbnail(objectAt("thumbnail"))
            ?: fromThumbnail(objectAt("foregroundThumbnail"))
    }

    private fun JsonObject.artistsFromRuns(): List<Artist> = arrayAt("runs").mapNotNull { run ->
        val item = run.asObjectOrNull() ?: return@mapNotNull null
        val name = item.stringAt("text") ?: return@mapNotNull null
        val id = item.stringAt("navigationEndpoint", "browseEndpoint", "browseId")
        id?.let { Artist(name, it) }
    }

    private fun JsonObject.albumFromRuns(): Album? {
        val run = arrayAt("runs").firstOrNull()?.asObjectOrNull() ?: return null
        val title = run.stringAt("text") ?: return null
        val browseId = run.stringAt("navigationEndpoint", "browseEndpoint", "browseId") ?: return null
        return Album(browseId, title)
    }

    private fun JsonObject.hasExplicitBadge(): Boolean = arrayAt("badges", "subtitleBadges").any {
        it.asObjectOrNull()?.stringAt("musicInlineBadgeRenderer", "icon", "iconType") == "MUSIC_EXPLICIT_BADGE"
    }

    private fun String.fourDigitYear(): Int? = Regex("\\b(19|20)\\d{2}\\b").find(this)?.value?.toIntOrNull()

    private fun String.durationSeconds(): Int? {
        val values = split(':').map { it.toIntOrNull() ?: return null }
        return values.fold(0) { total, value -> total * 60 + value }
    }

    private fun JsonArray.firstObject(key: String): JsonObject? =
        firstOrNull()?.asObjectOrNull()?.objectAt(key)

    private fun sectionContents(response: JsonObject): JsonArray = response.arrayAt(
        "contents", "singleColumnBrowseResultsRenderer", "tabs",
    ).firstObject("tabRenderer")?.arrayAt("content", "sectionListRenderer", "contents")
        ?: JsonArray(emptyList())
}

package com.music.player.yt.data.internal

import com.music.player.yt.data.CatalogItem
import com.music.player.yt.data.Page
import com.music.player.yt.data.SearchFilter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Search container traversal is deliberately separate from the shared catalog row parser. */
internal object InnerTubeSearchParser {
    internal data class Diagnostics(
        val containers: Int,
        val candidates: Int,
        val parsed: Int,
        val failed: Int,
        val duplicates: Int,
        val unsupported: Int = 0,
    )

    internal data class ParsedPage(val page: Page<CatalogItem>, val diagnostics: Diagnostics)

    fun parse(
        response: JsonObject,
        filter: SearchFilter,
        continuation: Boolean,
        rowParser: (JsonElement) -> CatalogItem?,
        cardParser: (JsonObject) -> CatalogItem?,
    ): ParsedPage {
        val collector = Collector(rowParser, cardParser)
        val sectionList = selectedSectionList(response)
        val continuationShelf = response.objectAt("continuationContents", "musicShelfContinuation")
        val actions = (response.arrayAt("onResponseReceivedCommands") + response.arrayAt("onResponseReceivedActions"))
            .mapNotNull { it.asObjectOrNull()?.objectAt("appendContinuationItemsAction") }
        val nextToken: String?
        when {
            actions.isNotEmpty() -> {
                val targets = buildSet {
                    sectionList?.stringAt("targetId")?.let(::add)
                    sectionList?.arrayAt("contents")?.forEach { collectTargets(it, this) }
                }
                val matching = actions.filter { it.stringAt("targetId") in targets }
                val selected = when {
                    matching.isNotEmpty() -> matching
                    targets.isNotEmpty() -> throw IllegalArgumentException("Search continuation did not match result target")
                    actions.size == 1 -> actions
                    actions.map { it.stringAt("targetId") }.distinct().let { it.size == 1 && it.single() != null } -> actions
                    else -> throw IllegalArgumentException("Ambiguous search continuation targets")
                }
                require(selected.map { it.stringAt("targetId") }.distinct().size == 1) { "Ambiguous search continuation targets" }
                collector.containers++
                selected.forEach { collector.entries(it.requiredArray("continuationItems")) }
                nextToken = collector.uniqueControlToken()
            }
            continuationShelf != null -> {
                collector.containers++
                collector.entries(continuationShelf.requiredArray("contents"))
                nextToken = continuationShelf.continuationToken() ?: collector.uniqueControlToken()
            }
            sectionList != null -> {
                collector.containers++
                sectionList.requiredArray("contents").forEach { collector.section(it) }
                val globalToken = sectionList.continuationToken() ?: collector.uniqueControlToken()
                nextToken = globalToken ?: if (filter != SearchFilter.ALL) collector.uniqueShelfToken() else null
            }
            else -> throw IllegalArgumentException("Unknown search ${if (continuation) "continuation" else "initial"} response structure")
        }
        require(collector.candidates == 0 || collector.items.isNotEmpty() || collector.explicitNoResults) {
            "Search candidates could not be parsed (${collector.candidates} candidates, ${collector.failed} failures)"
        }
        require(collector.recognizedResults || collector.explicitNoResults || sectionList?.arrayAt("contents")?.isEmpty() == true) {
            "Unknown search result containers"
        }
        val unique = collector.items.distinctBy { it.javaClass to it.id }
        return ParsedPage(
            Page(unique, nextToken),
            Diagnostics(collector.containers, collector.candidates, collector.items.size, collector.failed, collector.items.size - unique.size, collector.unsupported),
        )
    }

    private fun selectedSectionList(response: JsonObject): JsonObject? {
        val tabs = response.arrayAt("contents", "tabbedSearchResultsRenderer", "tabs")
            .mapNotNull { it.asObjectOrNull()?.objectAt("tabRenderer") }
        val selected = tabs.firstOrNull { it.stringAt("selected") == "true" && it.objectAt("content", "sectionListRenderer") != null }
        return selected?.objectAt("content", "sectionListRenderer")
            ?: tabs.firstNotNullOfOrNull { tab ->
                tab.objectAt("content", "sectionListRenderer")?.takeIf { list ->
                    list.arrayAt("contents").any { entry ->
                        entry.asObjectOrNull()?.keys?.any { it in setOf("musicShelfRenderer", "musicCardShelfRenderer", "itemSectionRenderer") } == true
                    }
                }
            } ?: tabs.firstNotNullOfOrNull { it.objectAt("content", "sectionListRenderer")?.takeIf { list -> list.arrayAt("contents").isEmpty() } }
    }

    private fun collectTargets(element: JsonElement, targets: MutableSet<String>) {
        val value = element.asObjectOrNull() ?: return
        for (key in listOf("musicShelfRenderer", "musicCardShelfRenderer", "itemSectionRenderer")) {
            val container = value.objectAt(key) ?: continue
            container.stringAt("targetId")?.let(targets::add)
            container.arrayAt("contents").forEach { collectTargets(it, targets) }
        }
    }

    private class Collector(
        val rowParser: (JsonElement) -> CatalogItem?,
        val cardParser: (JsonObject) -> CatalogItem?,
    ) {
        val items = mutableListOf<CatalogItem>()
        val shelfTokens = mutableListOf<String>()
        val controlTokens = mutableListOf<String>()
        var containers = 0
        var candidates = 0
        var failed = 0
        var unsupported = 0
        var recognizedResults = false
        var explicitNoResults = false

        fun section(element: JsonElement) {
            val objectValue = element.asObjectOrNull() ?: return
            when {
                objectValue.objectAt("musicShelfRenderer") != null -> {
                    val shelf = checkNotNull(objectValue.objectAt("musicShelfRenderer"))
                    containers++
                    recognizedResults = true
                    shelf.continuationToken()?.let(shelfTokens::add)
                    entries(shelf.requiredArray("contents"), shelfTokens)
                }
                objectValue.objectAt("musicCardShelfRenderer") != null -> {
                    val card = checkNotNull(objectValue.objectAt("musicCardShelfRenderer"))
                    containers++
                    recognizedResults = true
                    // A title-only card is decoration, not a failed entity.
                    if (card.objectAt("onTap") != null || card.objectAt("navigationEndpoint") != null ||
                        card.objectAt("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint") != null ||
                        card.objectAt("title")?.arrayAt("runs")?.any { it.asObjectOrNull()?.objectAt("navigationEndpoint") != null } == true) {
                        candidate { cardParser(card) }
                    }
                    if (card.containsKey("contents")) entries(card.requiredArray("contents"), shelfTokens)
                }
                objectValue.objectAt("itemSectionRenderer") != null -> {
                    containers++
                    recognizedResults = true
                    entries(checkNotNull(objectValue.objectAt("itemSectionRenderer")).requiredArray("contents"), shelfTokens)
                }
                objectValue.objectAt("continuationItemRenderer") != null -> objectValue.stringAt("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token")?.let(controlTokens::add)
            }
        }

        fun entries(entries: List<JsonElement>, tokenDestination: MutableList<String> = controlTokens) {
            recognizedResults = true
            entries.forEach { element ->
                val value = element.asObjectOrNull() ?: run { candidates++; failed++; return@forEach }
                when {
                    value.objectAt("continuationItemRenderer") != null -> value.stringAt("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token")?.let(tokenDestination::add)
                    value.objectAt("musicResponsiveListItemRenderer") != null || value.objectAt("musicTwoRowItemRenderer") != null -> {
                        val renderer = value.objectAt("musicResponsiveListItemRenderer") ?: checkNotNull(value.objectAt("musicTwoRowItemRenderer"))
                        val pageType = renderer.stringAt("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")
                        if (pageType == "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE") {
                            candidates++
                            unsupported++ // Public CatalogItem has no podcast-show model.
                        } else candidate { rowParser(element) }
                    }
                    value.objectAt("messageRenderer") != null -> {
                        val message = checkNotNull(value.objectAt("messageRenderer"))
                        if (message.stringAt("style", "value") == "RENDER_STYLE_SIMPLE_HEADER") Unit
                        else if (message.stringAt("icon", "iconType") == "SEARCH" && message.objectAt("subtext", "messageSubtextRenderer") != null && message.objectAt("text") != null) explicitNoResults = true
                        else { candidates++; failed++ }
                    }
                    value.objectAt("musicShelfRenderer") != null || value.objectAt("musicCardShelfRenderer") != null || value.objectAt("itemSectionRenderer") != null -> section(element)
                    value.keys.any { it in setOf("musicShelfDividerRenderer", "musicShelfHeaderRenderer") } -> Unit
                    else -> { candidates++; failed++ }
                }
            }
        }

        private fun candidate(parse: () -> CatalogItem?) {
            candidates++
            val item = parse()?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
            if (item == null) failed++ else items.add(item)
        }

        fun uniqueControlToken(): String? = uniqueToken(controlTokens)
        fun uniqueShelfToken(): String? = uniqueToken(shelfTokens)
        private fun uniqueToken(tokens: List<String>): String? {
            val unique = tokens.distinct()
            require(unique.size <= 1) { "Ambiguous search continuation tokens" }
            return unique.singleOrNull()
        }
    }

    private fun JsonObject.requiredArray(key: String): JsonArray =
        this[key] as? JsonArray ?: throw IllegalArgumentException("Search container is missing an array: $key")
}

package com.music.player.yt.data

import com.music.player.yt.data.internal.InnerTubeParser
import com.music.player.yt.data.internal.CaptionTransportResult
import com.music.player.yt.data.internal.FallbackLyricsResolver
import com.music.player.yt.data.internal.InnerTubeClients
import com.music.player.yt.data.internal.InnerTubeStreamResolver
import com.music.player.yt.data.internal.InnerTubeTransport
import com.music.player.yt.data.internal.SyncedLyricsParser
import com.music.player.yt.data.internal.LyricsProviderAttempt
import com.music.player.yt.data.internal.LyricsSearchNormalizer
import com.music.player.yt.data.internal.LyricsSearchTerms
import com.music.player.yt.data.internal.TransportResult
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient

public class YoutubeMusicDataClient(
    httpClient: OkHttpClient = OkHttpClient(),
    locale: YoutubeMusicLocale = YoutubeMusicLocale(),
    baseUrl: String = DEFAULT_BASE_URL,
    initialClientVersions: YoutubeClientVersions? = null,
    streamOptions: YoutubeStreamOptions = YoutubeStreamOptions(),
) {
    private val clientVersions = AtomicReference(normalizeVersions(initialClientVersions))
    private val transport = InnerTubeTransport(
        httpClient = httpClient,
        baseUrl = baseUrl,
        locale = locale,
        webClientProvider = { InnerTubeClients.webRemix(checkNotNull(clientVersions.get().webRemixVersion)) },
    )
    private val streamResolver = InnerTubeStreamResolver(
        transport = transport,
        versionsProvider = clientVersions::get,
        options = streamOptions,
    )
    private val fallbackLyricsResolver = FallbackLyricsResolver(httpClient)

    /** Replaces this client's version snapshot. Other client instances are unaffected. */
    public fun updateClientVersions(versions: YoutubeClientVersions) {
        clientVersions.set(normalizeVersions(versions))
    }

    public suspend fun createAnonymousSession(): YoutubeDataResult<YoutubeMusicSession> =
        transport.anonymousSession()

    public suspend fun home(
        session: YoutubeMusicSession,
        continuationToken: String? = null,
    ): YoutubeDataResult<Page<HomeSection>> = browse(
        session = session,
        endpoint = BrowseEndpoint(HOME_BROWSE_ID),
        continuationToken = continuationToken,
    )

    public suspend fun browse(
        session: YoutubeMusicSession,
        endpoint: BrowseEndpoint,
        continuationToken: String? = null,
    ): YoutubeDataResult<Page<HomeSection>> = execute(
        session = session,
        endpoint = "browse",
        payload = transport.browsePayload(session, endpoint.browseId, endpoint.params),
        continuationToken = continuationToken,
        responseCheck = { response, page ->
            if (page.items.isEmpty()) {
                InnerTubeParser.homeUnavailableReason(response)?.let { YoutubeDataResult.Unavailable(it) }
            } else {
                null
            }
        },
        parser = InnerTubeParser::home,
    )

    public suspend fun moodAndGenres(
        session: YoutubeMusicSession,
    ): YoutubeDataResult<List<MoodAndGenres>> = execute(
        session,
        "browse",
        transport.browsePayload(session, MOODS_AND_GENRES_BROWSE_ID),
        null,
        parser = InnerTubeParser::moodAndGenres,
    )

    public suspend fun search(
        session: YoutubeMusicSession,
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        continuationToken: String? = null,
    ): YoutubeDataResult<Page<CatalogItem>> = execute(session, "search", transport.searchPayload(session, query, filter.params), continuationToken) { response ->
        InnerTubeParser.search(response, filter, continuationToken != null)
    }

    public suspend fun searchSuggestions(
        session: YoutubeMusicSession,
        query: String,
    ): YoutubeDataResult<SearchSuggestions> = execute(session, "music/get_search_suggestions", transport.suggestionsPayload(session, query), null, parser = InnerTubeParser::suggestions)

    public suspend fun track(
        session: YoutubeMusicSession,
        videoId: String,
    ): YoutubeDataResult<TrackPage> = execute(session, "next", transport.nextPayload(session, videoId), null) { response ->
        InnerTubeParser.track(response, videoId)
    }

    /** Resolves a short-lived stream resource that a host can supply to Media3. */
    public suspend fun resolveStream(
        session: YoutubeMusicSession,
        videoId: String,
        playlistId: String? = null,
        quality: StreamQuality = StreamQuality.AUTO,
        isNetworkMetered: Boolean = false,
    ): YoutubeDataResult<StreamInfo> = streamResolver.resolve(
        session = session,
        videoId = videoId,
        playlistId = playlistId,
        quality = quality,
        isNetworkMetered = isNetworkMetered,
    )

    public suspend fun album(
        session: YoutubeMusicSession,
        browseId: String,
    ): YoutubeDataResult<AlbumPage> {
        val initial = execute(session, "browse", transport.browsePayload(session, browseId), null) { response ->
            InnerTubeParser.album(response, browseId)
        }
        if (initial !is YoutubeDataResult.Success) return initial

        val playlistId = initial.data.album.playlistId?.takeIf(String::isNotBlank)
        if (initial.data.songs.isNotEmpty() || playlistId == null) return initial

        return when (val songs = albumSongs(initial.updatedSession, playlistId)) {
            is YoutubeDataResult.Success -> YoutubeDataResult.Success(
                data = initial.data.copy(
                    songs = songs.data.items,
                    nextPageToken = songs.data.nextPageToken,
                ),
                updatedSession = songs.updatedSession,
            )

            is YoutubeDataResult.NetworkError -> songs
            is YoutubeDataResult.ParseError -> songs
            is YoutubeDataResult.UpstreamError -> songs
            is YoutubeDataResult.Unavailable -> songs
        }
    }

    public suspend fun albumSongs(
        session: YoutubeMusicSession,
        playlistId: String,
        continuationToken: String? = null,
    ): YoutubeDataResult<Page<Track>> = execute(session, "browse", transport.browsePayload(session, "VL$playlistId"), continuationToken, parser = InnerTubeParser::tracksPage)

    public suspend fun playlist(
        session: YoutubeMusicSession,
        playlistId: String,
    ): YoutubeDataResult<PlaylistPage> = execute(session, "browse", transport.browsePayload(session, "VL$playlistId"), null) { response ->
        InnerTubeParser.playlist(response, playlistId)
    }

    public suspend fun playlistSongs(
        session: YoutubeMusicSession,
        playlistId: String,
        continuationToken: String,
    ): YoutubeDataResult<Page<Track>> = execute(session, "browse", transport.browsePayload(session, "VL$playlistId"), continuationToken, parser = InnerTubeParser::tracksPage)

    public suspend fun fetchCuratedSongsPage(
        session: YoutubeMusicSession,
        item: MoodAndGenres.Item,
        cursor: CuratedSongsCursor? = null,
        limit: Int = 10,
    ): YoutubeDataResult<CuratedSongsPage> {
        require(limit > 0) { "limit must be greater than zero" }

        var latestSession = session
        val initialState = if (cursor == null) {
            when (val initial = execute(
                session,
                "browse",
                transport.browsePayload(session, item.endpoint.browseId, item.endpoint.params),
                null,
                parser = InnerTubeParser::moodContentItems,
            )) {
                is YoutubeDataResult.Success -> {
                    latestSession = initial.updatedSession
                    CuratedSongsCursorState(
                        directTracks = initial.data.filterIsInstance<Track>(),
                        directTrackIndex = 0,
                        collectionIds = initial.data.curatedCollectionIds(),
                        collectionIndex = 0,
                        continuationToken = null,
                        bufferedTracks = emptyList(),
                        returnedTrackIds = emptySet(),
                    )
                }
                is YoutubeDataResult.NetworkError -> return initial
                is YoutubeDataResult.ParseError -> return initial
                is YoutubeDataResult.UpstreamError -> return initial
                is YoutubeDataResult.Unavailable -> return initial
            }
        } else {
            cursor.snapshot()
        }

        var directTrackIndex = initialState.directTrackIndex
        var collectionIndex = initialState.collectionIndex
        var continuationToken = initialState.continuationToken
        var bufferedTracks = initialState.bufferedTracks
        val returnedTrackIds = initialState.returnedTrackIds.toMutableSet()
        val pageTracks = mutableListOf<Track>()

        fun consume(tracks: List<Track>, startIndex: Int = 0): Int {
            var index = startIndex
            while (index < tracks.size && pageTracks.size < limit) {
                val track = tracks[index++]
                if (returnedTrackIds.add(track.id)) pageTracks += track
            }
            return index
        }

        directTrackIndex = consume(initialState.directTracks, directTrackIndex)
        while (pageTracks.size < limit) {
            if (bufferedTracks.isNotEmpty()) {
                val consumed = consume(bufferedTracks)
                bufferedTracks = bufferedTracks.drop(consumed)
                if (pageTracks.size == limit) break
            }

            if (collectionIndex >= initialState.collectionIds.size) break
            val playlistId = initialState.collectionIds[collectionIndex]
            when (val songs = albumSongs(latestSession, playlistId, continuationToken)) {
                is YoutubeDataResult.Success -> {
                    latestSession = songs.updatedSession
                    bufferedTracks = songs.data.items
                    continuationToken = songs.data.nextPageToken
                    if (continuationToken == null) collectionIndex += 1
                }
                is YoutubeDataResult.NetworkError -> return songs
                is YoutubeDataResult.ParseError -> return songs
                is YoutubeDataResult.UpstreamError -> return songs
                is YoutubeDataResult.Unavailable -> return songs
            }
        }

        while (
            directTrackIndex < initialState.directTracks.size &&
            initialState.directTracks[directTrackIndex].id in returnedTrackIds
        ) {
            directTrackIndex += 1
        }
        val firstUnreturnedBufferedTrack = bufferedTracks.indexOfFirst { it.id !in returnedTrackIds }
        bufferedTracks = when (firstUnreturnedBufferedTrack) {
            -1 -> emptyList()
            0 -> bufferedTracks
            else -> bufferedTracks.drop(firstUnreturnedBufferedTrack)
        }

        val hasMore = directTrackIndex < initialState.directTracks.size ||
            bufferedTracks.isNotEmpty() ||
            collectionIndex < initialState.collectionIds.size
        val nextCursor = if (hasMore) {
            CuratedSongsCursor.create(
                CuratedSongsCursorState(
                    directTracks = initialState.directTracks,
                    directTrackIndex = directTrackIndex,
                    collectionIds = initialState.collectionIds,
                    collectionIndex = collectionIndex,
                    continuationToken = continuationToken,
                    bufferedTracks = bufferedTracks,
                    returnedTrackIds = returnedTrackIds,
                ),
            )
        } else {
            null
        }
        return YoutubeDataResult.Success(CuratedSongsPage(pageTracks, nextCursor), latestSession)
    }

    public suspend fun fetchCuratedSongs(
        session: YoutubeMusicSession,
        item: MoodAndGenres.Item,
    ): YoutubeDataResult<List<Track>> {
        val initial = execute(
            session,
            "browse",
            transport.browsePayload(session, item.endpoint.browseId, item.endpoint.params),
            null,
            parser = InnerTubeParser::moodContentItems,
        )
        if (initial !is YoutubeDataResult.Success) {
            return when (initial) {
                is YoutubeDataResult.NetworkError -> YoutubeDataResult.NetworkError(initial.cause)
                is YoutubeDataResult.ParseError -> YoutubeDataResult.ParseError(initial.message, initial.cause)
                is YoutubeDataResult.UpstreamError -> YoutubeDataResult.UpstreamError(initial.statusCode, initial.message)
                is YoutubeDataResult.Unavailable -> YoutubeDataResult.Unavailable(initial.reason)
                is YoutubeDataResult.Success -> error("Unreachable")
            }
        }

        var latestSession = initial.updatedSession
        val tracks = initial.data.filterIsInstance<Track>().toMutableList()
        for (playlistId in initial.data.curatedCollectionIds()) {
            var continuationToken: String? = null
            do {
                when (val songs = albumSongs(latestSession, playlistId, continuationToken)) {
                    is YoutubeDataResult.Success -> {
                        latestSession = songs.updatedSession
                        tracks += songs.data.items
                        continuationToken = songs.data.nextPageToken
                    }
                    else -> break
                }
            } while (continuationToken != null)
        }
        return YoutubeDataResult.Success(tracks.distinctBy(Track::id), latestSession)
    }

    public suspend fun artist(
        session: YoutubeMusicSession,
        browseId: String,
    ): YoutubeDataResult<ArtistPage> = execute(session, "browse", transport.browsePayload(session, browseId), null) { response ->
        InnerTubeParser.artist(response, browseId)
    }

    public suspend fun lyrics(
        session: YoutubeMusicSession,
        videoId: String,
    ): YoutubeDataResult<Lyrics> = lyricsAttempt(session, videoId).result

    private suspend fun lyricsAttempt(
        session: YoutubeMusicSession,
        videoId: String,
    ): SessionAttempt<Lyrics> {
        val next = transport.post("next", session, transport.nextPayload(session, videoId))
        if (next is TransportResult.Error) return SessionAttempt(next.error, session)
        next as TransportResult.Success
        val browseId = InnerTubeParser.lyricsBrowseId(next.response)
            ?: return SessionAttempt(
                YoutubeDataResult.ParseError("Track did not provide a lyrics endpoint"),
                next.updatedSession,
                unavailable = true,
            )
        val browse = transport.post(
            "browse",
            next.updatedSession,
            transport.browsePayload(next.updatedSession, browseId),
        )
        if (browse is TransportResult.Error) return SessionAttempt(browse.error, next.updatedSession)
        browse as TransportResult.Success
        val lyrics = InnerTubeParser.lyrics(browse.response)
            ?: return SessionAttempt(
                YoutubeDataResult.ParseError("Lyrics response did not contain text"),
                browse.updatedSession,
                unavailable = true,
            )
        return SessionAttempt(
            YoutubeDataResult.Success(lyrics, browse.updatedSession),
            browse.updatedSession,
        )
    }

    public suspend fun syncedLyrics(
        session: YoutubeMusicSession,
        videoId: String,
        preferredLanguageCodes: List<String> = emptyList(),
    ): YoutubeDataResult<SyncedLyrics> = syncedLyricsAttempt(session, videoId, preferredLanguageCodes).result

    private suspend fun syncedLyricsAttempt(
        session: YoutubeMusicSession,
        videoId: String,
        preferredLanguageCodes: List<String>,
    ): SessionAttempt<SyncedLyrics> {
        val client = InnerTubeClients.webRemix(checkNotNull(clientVersions.get().webRemixVersion))
        val player = transport.post(
            endpoint = "player",
            session = session,
            payload = transport.playerPayload(session, videoId, playlistId = null, client = client),
            client = client,
        )
        if (player is TransportResult.Error) return SessionAttempt(player.error, session)
        player as TransportResult.Success

        val track = SyncedLyricsParser.selectTrack(player.response, preferredLanguageCodes)
            ?: return SessionAttempt(
                YoutubeDataResult.ParseError("Player response did not contain an available caption track"),
                player.updatedSession,
                unavailable = true,
            )
        val result = when (val caption = transport.caption(track.baseUrl)) {
            is CaptionTransportResult.Error -> caption.error
            is CaptionTransportResult.Success -> {
                val lines = try {
                    SyncedLyricsParser.parseLines(caption.body)
                } catch (error: SerializationException) {
                    return SessionAttempt(
                        YoutubeDataResult.ParseError("Caption response was invalid JSON", error),
                        player.updatedSession,
                    )
                } catch (error: IllegalArgumentException) {
                    return SessionAttempt(
                        YoutubeDataResult.ParseError(
                            error.message ?: "Unexpected caption response structure",
                            error,
                        ),
                        player.updatedSession,
                    )
                }
                if (lines.isEmpty()) {
                    YoutubeDataResult.ParseError("Caption response did not contain valid lyric lines")
                } else {
                    YoutubeDataResult.Success(
                        data = SyncedLyrics(lines = lines, languageCode = track.languageCode),
                        updatedSession = player.updatedSession,
                    )
                }
            }
        }
        return SessionAttempt(
            result,
            player.updatedSession,
            unavailable = result is YoutubeDataResult.ParseError &&
                result.message == "Caption response did not contain valid lyric lines",
        )
    }

    public suspend fun resolveLyrics(
        session: YoutubeMusicSession,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        preferredLanguageCodes: List<String> = emptyList(),
    ): YoutubeDataResult<ResolvedLyrics> {
        val normalized = LyricsSearchNormalizer.normalize(title, artist)
        val original = LyricsSearchTerms(title, artist)
        val namesChanged = normalized != original
        val order = if (LyricsSearchNormalizer.isChinese(title, artist)) {
            listOf(LyricsSource.KUGOU, LyricsSource.YOUTUBE_CAPTIONS, LyricsSource.LRCLIB, LyricsSource.YOUTUBE_OFFICIAL)
        } else {
            listOf(LyricsSource.YOUTUBE_CAPTIONS, LyricsSource.LRCLIB, LyricsSource.KUGOU, LyricsSource.YOUTUBE_OFFICIAL)
        }
        var latestSession = session
        val errors = LyricsErrorAccumulator()

        suspend fun thirdPartyAttempt(
            request: suspend (LyricsSearchTerms) -> LyricsProviderAttempt,
        ): LyricsProviderAttempt {
            val first = request(normalized)
            return if (first is LyricsProviderAttempt.Unavailable && namesChanged) request(original) else first
        }

        for (source in order) {
            when (source) {
                LyricsSource.YOUTUBE_CAPTIONS -> {
                    val attempt = syncedLyricsAttempt(latestSession, videoId, preferredLanguageCodes)
                    latestSession = attempt.session
                    when (val result = attempt.result) {
                        is YoutubeDataResult.Success -> return YoutubeDataResult.Success(
                            ResolvedLyrics(null, result.data.lines, source),
                            latestSession,
                        )
                        else -> if (!attempt.unavailable) errors.record(result)
                    }
                }
                LyricsSource.LRCLIB -> when (
                    val attempt = thirdPartyAttempt { fallbackLyricsResolver.lrcLib(it, durationMs) }
                ) {
                    is LyricsProviderAttempt.Found -> return YoutubeDataResult.Success(attempt.lyrics, latestSession)
                    is LyricsProviderAttempt.Error -> errors.record(attempt.error)
                    LyricsProviderAttempt.Unavailable -> Unit
                }
                LyricsSource.KUGOU -> when (
                    val attempt = thirdPartyAttempt { fallbackLyricsResolver.kugou(it, durationMs) }
                ) {
                    is LyricsProviderAttempt.Found -> return YoutubeDataResult.Success(attempt.lyrics, latestSession)
                    is LyricsProviderAttempt.Error -> errors.record(attempt.error)
                    LyricsProviderAttempt.Unavailable -> Unit
                }
                LyricsSource.YOUTUBE_OFFICIAL -> {
                    val attempt = lyricsAttempt(latestSession, videoId)
                    latestSession = attempt.session
                    when (val result = attempt.result) {
                        is YoutubeDataResult.Success -> {
                            val text = result.data.text.trim()
                            if (text.isNotEmpty()) {
                                return YoutubeDataResult.Success(
                                    ResolvedLyrics(text, null, source),
                                    latestSession,
                                )
                            }
                        }
                        else -> if (!attempt.unavailable) errors.record(result)
                    }
                }
            }
        }
        return errors.result()
    }

    public suspend fun related(
        session: YoutubeMusicSession,
        videoId: String,
    ): YoutubeDataResult<RelatedContent> {
        val next = transport.post("next", session, transport.nextPayload(session, videoId))
        if (next is TransportResult.Error) return next.error
        next as TransportResult.Success
        val browseId = InnerTubeParser.relatedBrowseId(next.response)
            ?: return YoutubeDataResult.ParseError("Track did not provide a related-content endpoint")
        return execute(next.updatedSession, "browse", transport.browsePayload(next.updatedSession, browseId), null, parser = InnerTubeParser::related)
    }

    private suspend fun <T> execute(
        session: YoutubeMusicSession,
        endpoint: String,
        payload: kotlinx.serialization.json.JsonObject,
        continuationToken: String?,
        responseCheck: ((kotlinx.serialization.json.JsonObject, T) -> YoutubeDataResult<Nothing>?)? = null,
        parser: (kotlinx.serialization.json.JsonObject) -> T,
    ): YoutubeDataResult<T> {
        return when (val response = transport.post(endpoint, session, payload, continuationToken)) {
            is TransportResult.Error -> response.error
            is TransportResult.Success -> try {
                val data = parser(response.response)
                responseCheck?.invoke(response.response, data)
                    ?: YoutubeDataResult.Success(data, response.updatedSession)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                YoutubeDataResult.ParseError("Unexpected InnerTube response structure", error)
            }
        }
    }

    private companion object {
        private const val DEFAULT_BASE_URL = "https://music.youtube.com/"
        private const val HOME_BROWSE_ID = "FEmusic_home"
        private const val MOODS_AND_GENRES_BROWSE_ID = "FEmusic_moods_and_genres"

        private fun normalizeVersions(versions: YoutubeClientVersions?): YoutubeClientVersions = YoutubeClientVersions(
            webRemixVersion = versions?.webRemixVersion?.takeIf(String::isNotBlank)
                ?: InnerTubeClients.DEFAULT_WEB_REMIX_VERSION,
            androidVersion = versions?.androidVersion?.takeIf(String::isNotBlank)
                ?: InnerTubeClients.DEFAULT_ANDROID_VERSION,
        )
    }
}

private data class SessionAttempt<T>(
    val result: YoutubeDataResult<T>,
    val session: YoutubeMusicSession,
    val unavailable: Boolean = false,
)

private class LyricsErrorAccumulator {
    private var network: YoutubeDataResult.NetworkError? = null
    private var upstream: YoutubeDataResult.UpstreamError? = null
    private var parse: YoutubeDataResult.ParseError? = null

    fun record(error: YoutubeDataResult<*>) {
        when (error) {
            is YoutubeDataResult.NetworkError -> if (network == null) network = error
            is YoutubeDataResult.UpstreamError -> if (upstream == null) upstream = error
            is YoutubeDataResult.ParseError -> if (parse == null) parse = error
            is YoutubeDataResult.Unavailable,
            is YoutubeDataResult.Success -> Unit
        }
    }

    fun result(): YoutubeDataResult<Nothing> = network
        ?: upstream
        ?: parse
        ?: YoutubeDataResult.Unavailable("No lyrics found from available providers")
}

private fun List<CatalogItem>.curatedCollectionIds(): List<String> = mapNotNull { content ->
    when (content) {
        is Playlist -> content.id
        is Album -> content.playlistId
        else -> null
    }
}

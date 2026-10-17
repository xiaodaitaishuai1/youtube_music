package com.music.player.yt.data

import java.io.IOException

public data class YoutubeMusicSession(
    public val visitorData: String,
)

public data class YoutubeMusicLocale(
    public val hl: String = "en",
    public val gl: String = "US",
)

/**
 * Client versions supplied by the host. Missing values use this library's built-in defaults.
 */
public data class YoutubeClientVersions(
    public val webRemixVersion: String? = null,
    public val androidVersion: String? = null,
)

/**
 * A host-triggered version refresh does not require a visitor session, so it has its own result type.
 */
public sealed interface YoutubeVersionResult {
    public data class Success(
        public val versions: YoutubeClientVersions,
    ) : YoutubeVersionResult

    public data class NetworkError(
        public val cause: IOException,
    ) : YoutubeVersionResult

    public data class ParseError(
        public val message: String,
        public val cause: Throwable? = null,
    ) : YoutubeVersionResult

    public data class UpstreamError(
        public val statusCode: Int,
        public val message: String?,
    ) : YoutubeVersionResult
}

public sealed interface YoutubeDataResult<out T> {
    public data class Success<T>(
        public val data: T,
        public val updatedSession: YoutubeMusicSession,
    ) : YoutubeDataResult<T>

    public data class NetworkError(
        public val cause: IOException,
    ) : YoutubeDataResult<Nothing>

    public data class ParseError(
        public val message: String,
        public val cause: Throwable? = null,
    ) : YoutubeDataResult<Nothing>

    public data class UpstreamError(
        public val statusCode: Int,
        public val message: String?,
    ) : YoutubeDataResult<Nothing>

    /** The upstream response is valid but the requested content or playable stream is unavailable. */
    public data class Unavailable(
        public val reason: String? = null,
    ) : YoutubeDataResult<Nothing>
}

public enum class StreamQuality {
    AUTO,
    DATA_SAVER,
    HIGH,
}

public enum class StreamSource {
    ANDROID,
    TVHTML5,
    PIPED,
}

/**
 * Piped fallback is disabled until a host explicitly supplies an endpoint.
 */
public data class YoutubeStreamOptions(
    public val pipedBaseUrl: String? = null,
)

/**
 * A short-lived media resource that the host can pass to a Media3 HTTP data source.
 *
 * Depending on [StreamQuality] and the formats exposed by YouTube, this can be an audio-only
 * resource or a video resource that contains an audio track, matching SonicTube playback.
 */
public data class StreamInfo(
    public val url: String,
    public val mimeType: String,
    public val codecs: String? = null,
    public val itag: Int,
    public val bitrate: Int,
    public val contentLengthBytes: Long? = null,
    public val audioSampleRateHz: Int? = null,
    public val audioChannels: Int? = null,
    public val expiresAtEpochMillis: Long,
    public val requestHeaders: Map<String, String>,
    public val source: StreamSource,
)

public data class Page<T>(
    public val items: List<T>,
    public val nextPageToken: String? = null,
)

internal data class CuratedSongsCursorState(
    val directTracks: List<Track>,
    val directTrackIndex: Int,
    val collectionIds: List<String>,
    val collectionIndex: Int,
    val continuationToken: String?,
    val bufferedTracks: List<Track>,
    val returnedTrackIds: Set<String>,
)

public class CuratedSongsCursor private constructor(
    private val state: CuratedSongsCursorState,
) {
    @JvmSynthetic
    internal fun snapshot(): CuratedSongsCursorState = state

    internal companion object {
        @JvmSynthetic
        fun create(state: CuratedSongsCursorState): CuratedSongsCursor = CuratedSongsCursor(state)
    }
}

public data class CuratedSongsPage(
    public val tracks: List<Track>,
    public val nextCursor: CuratedSongsCursor?,
)

public sealed interface CatalogItem {
    public val id: String
    public val title: String
    public val thumbnailUrl: String?
}

public data class Artist(
    public val name: String,
    public val channelId: String? = null,
    public override val thumbnailUrl: String? = null,
) : CatalogItem {
    override val id: String get() = channelId ?: name
    override val title: String get() = name
    public val browseId: String? get() = channelId
}

public data class Album(
    public val browseId: String,
    public override val title: String,
    public val artists: List<Artist> = emptyList(),
    public val playlistId: String? = null,
    public val year: Int? = null,
    public override val thumbnailUrl: String? = null,
    public val explicit: Boolean = false,
) : CatalogItem {
    override val id: String get() = browseId
    public val thumbnail: String? get() = thumbnailUrl
}

public data class Track(
    public override val id: String,
    public override val title: String,
    public val artists: List<Artist> = emptyList(),
    public val album: Album? = null,
    public val durationSeconds: Int? = null,
    public override val thumbnailUrl: String? = null,
    public val explicit: Boolean = false,
) : CatalogItem {
    public val thumbnail: String? get() = thumbnailUrl
    public val duration: Int? get() = durationSeconds
}

public data class Playlist(
    public override val id: String,
    public override val title: String,
    public val author: Artist? = null,
    public val songCountText: String? = null,
    public override val thumbnailUrl: String? = null,
) : CatalogItem {
    public val thumbnail: String? get() = thumbnailUrl
}

public data class HomeSection @JvmOverloads constructor(
    public val title: String,
    public val label: String? = null,
    public val thumbnailUrl: String? = null,
    public val items: List<CatalogItem> = emptyList(),
    public val endpoint: BrowseEndpoint? = null,
)

public data class BrowseEndpoint(
    public val browseId: String,
    public val params: String? = null,
)

public data class MoodAndGenres(
    public val title: String,
    public val items: List<Item>,
) {
    public data class Item(
        public val title: String,
        public val stripeColor: Long,
        public val endpoint: BrowseEndpoint,
    )
}

public data class TrackPage(
    public val track: Track,
)

public data class AlbumPage(
    public val album: Album,
    public val songs: List<Track> = emptyList(),
    public val nextPageToken: String? = null,
    public val otherVersions: List<Album> = emptyList(),
)

public data class PlaylistPage(
    public val playlist: Playlist,
    public val songs: List<Track> = emptyList(),
    public val nextPageToken: String? = null,
)

public data class ArtistSection(
    public val title: String,
    public val items: List<CatalogItem>,
    public val nextPageToken: String? = null,
)

public data class ArtistPage(
    public val artist: Artist,
    public val sections: List<ArtistSection> = emptyList(),
    public val description: String? = null,
)

public data class SearchSuggestions(
    public val queries: List<String> = emptyList(),
    public val recommendedItems: List<CatalogItem> = emptyList(),
)

public data class Lyrics(
    public val text: String,
)

public data class SyncedLyricLine(
    public val startTimeMs: Long,
    public val text: String,
)

public data class SyncedLyrics(
    public val lines: List<SyncedLyricLine>,
    public val languageCode: String,
)

public enum class LyricsSource {
    YOUTUBE_CAPTIONS,
    LRCLIB,
    KUGOU,
    YOUTUBE_OFFICIAL,
}

public data class ResolvedLyrics(
    public val text: String?,
    public val syncedLines: List<SyncedLyricLine>?,
    public val source: LyricsSource,
) {
    init {
        require(!text.isNullOrBlank() || !syncedLines.isNullOrEmpty()) {
            "Resolved lyrics must contain text or synchronized lines"
        }
    }
}

public data class RelatedContent(
    public val songs: List<Track> = emptyList(),
    public val albums: List<Album> = emptyList(),
    public val artists: List<Artist> = emptyList(),
    public val playlists: List<Playlist> = emptyList(),
)

public enum class SearchFilter(public val params: String?) {
    ALL(null),
    SONGS("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    VIDEOS("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"),
    ALBUMS("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
    ARTISTS("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
    FEATURED_PLAYLISTS("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D"),
    COMMUNITY_PLAYLISTS("EgeKAQQoAEABagoQAxAEEAoQCRAF"),
}

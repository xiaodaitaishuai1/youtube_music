package com.music.player.yt.data.sample

import android.app.Activity
import com.music.player.yt.data.ResolvedLyrics
import com.music.player.yt.data.YoutubeClientVersions
import com.music.player.yt.data.SyncedLyrics
import com.music.player.yt.data.YoutubeDataResult
import com.music.player.yt.data.YoutubeMusicDataClient
import com.music.player.yt.data.YoutubeMusicSession
import com.music.player.yt.data.YoutubeVersionHelper
import com.music.player.yt.data.YoutubeVersionResult

class MainActivity : Activity() {
    private val youtubeMusicDataClient = YoutubeMusicDataClient(
        initialClientVersions = loadSavedClientVersions(),
    )
    private val youtubeVersionHelper = YoutubeVersionHelper()

    suspend fun refreshYoutubeClientVersions() {
        when (val result = youtubeVersionHelper.fetchCurrentVersion()) {
            is YoutubeVersionResult.Success -> {
                youtubeMusicDataClient.updateClientVersions(result.versions)
                persistVersions(result.versions)
            }
            is YoutubeVersionResult.NetworkError,
            is YoutubeVersionResult.ParseError,
            is YoutubeVersionResult.UpstreamError -> Unit
        }
    }

    suspend fun loadSyncedLyrics(
        session: YoutubeMusicSession,
        videoId: String,
        preferredLanguageCodes: List<String> = emptyList(),
    ): YoutubeDataResult<SyncedLyrics> = youtubeMusicDataClient.syncedLyrics(
        session = session,
        videoId = videoId,
        preferredLanguageCodes = preferredLanguageCodes,
    )

    suspend fun resolveLyrics(
        session: YoutubeMusicSession,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        preferredLanguageCodes: List<String> = emptyList(),
    ): YoutubeDataResult<ResolvedLyrics> = youtubeMusicDataClient.resolveLyrics(
        session = session,
        videoId = videoId,
        title = title,
        artist = artist,
        durationMs = durationMs,
        preferredLanguageCodes = preferredLanguageCodes,
    )

    // Replace these placeholders with the host's own persistent state store.
    private fun loadSavedClientVersions(): YoutubeClientVersions? = null

    private fun persistVersions(versions: YoutubeClientVersions) = Unit
}

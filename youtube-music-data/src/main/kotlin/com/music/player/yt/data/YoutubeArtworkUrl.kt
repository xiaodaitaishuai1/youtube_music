package com.music.player.yt.data

private val ResizableArtworkUrl = Regex(
    "^(https://(?:yt3|lh3)\\.googleusercontent\\.com/[^?]+?=w)\\d+(-h)\\d+([^?]*)(\\?.*)?$",
)

public fun String.resizedArtworkUrl(size: Int): String {
    require(size > 0) { "size must be greater than zero" }
    val match = ResizableArtworkUrl.matchEntire(this) ?: return this
    return buildString {
        append(match.groupValues[1])
        append(size)
        append(match.groupValues[2])
        append(size)
        append(match.groupValues[3])
        append(match.groupValues[4])
    }
}

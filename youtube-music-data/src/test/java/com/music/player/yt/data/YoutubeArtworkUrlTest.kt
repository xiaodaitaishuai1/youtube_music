package com.music.player.yt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class YoutubeArtworkUrlTest {
    @Test
    fun `resizes a yt3 artwork URL to a square target`() {
        assertEquals(
            "https://yt3.googleusercontent.com/artwork=w1200-h1200-l90-rj",
            "https://yt3.googleusercontent.com/artwork=w544-h544-l90-rj".resizedArtworkUrl(1200),
        )
    }

    @Test
    fun `preserves artwork URL suffixes and query parameters`() {
        assertEquals(
            "https://lh3.googleusercontent.com/artwork=w1200-h1200-p-l90-rj?foo=bar",
            "https://lh3.googleusercontent.com/artwork=w544-h544-p-l90-rj?foo=bar".resizedArtworkUrl(1200),
        )
    }

    @Test
    fun `leaves unsupported artwork URLs unchanged`() {
        val url = "https://example.com/artwork=w544-h544-l90-rj"

        assertEquals(url, url.resizedArtworkUrl(1200))
    }

    @Test
    fun `rejects nonpositive target sizes`() {
        try {
            "https://yt3.googleusercontent.com/artwork=w544-h544-l90-rj".resizedArtworkUrl(0)
            fail("Expected an IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertEquals("size must be greater than zero", error.message)
        }
    }
}

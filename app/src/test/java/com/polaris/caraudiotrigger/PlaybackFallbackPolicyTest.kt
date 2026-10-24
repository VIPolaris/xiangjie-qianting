package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.playback.PlaybackFallbackPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFallbackPolicyTest {
    @Test
    fun `decode failure falls back exactly once to distinct built in audio`() {
        assertTrue(
            PlaybackFallbackPolicy.shouldAttemptFallback(
                fallbackAlreadyAttempted = false,
                primaryPath = "/private/custom.mp3",
                fallbackPath = "/private/built-in.wav",
                fallbackIsUsable = true,
            ),
        )
        assertFalse(
            PlaybackFallbackPolicy.shouldAttemptFallback(
                fallbackAlreadyAttempted = true,
                primaryPath = "/private/custom.mp3",
                fallbackPath = "/private/built-in.wav",
                fallbackIsUsable = true,
            ),
        )
    }

    @Test
    fun `missing or same fallback is rejected`() {
        assertFalse(
            PlaybackFallbackPolicy.shouldAttemptFallback(false, "custom", "fallback", false),
        )
        assertFalse(
            PlaybackFallbackPolicy.shouldAttemptFallback(false, "same", "same", true),
        )
    }
}

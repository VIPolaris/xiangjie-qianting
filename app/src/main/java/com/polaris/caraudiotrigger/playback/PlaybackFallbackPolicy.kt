package com.polaris.caraudiotrigger.playback

object PlaybackFallbackPolicy {
    fun shouldAttemptFallback(
        fallbackAlreadyAttempted: Boolean,
        primaryPath: String,
        fallbackPath: String,
        fallbackIsUsable: Boolean,
    ): Boolean = !fallbackAlreadyAttempted &&
        fallbackIsUsable &&
        fallbackPath.isNotBlank() &&
        fallbackPath != primaryPath
}

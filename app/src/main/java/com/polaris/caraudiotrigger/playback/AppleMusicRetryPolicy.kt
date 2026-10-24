package com.polaris.caraudiotrigger.playback

internal object AppleMusicRetryPolicy {
    const val BOUNDED_RETRY_MILLIS = 60_000L

    fun deadline(startedAtMillis: Long, retryUntilDisconnected: Boolean): Long =
        if (retryUntilDisconnected) Long.MAX_VALUE else startedAtMillis + BOUNDED_RETRY_MILLIS

    fun remainingMillis(deadlineMillis: Long, nowMillis: Long): Long =
        if (deadlineMillis == Long.MAX_VALUE) Long.MAX_VALUE else deadlineMillis - nowMillis

    fun shouldContinue(
        routeAvailable: Boolean,
        result: AppleMusicResumeResult,
        deadlineMillis: Long,
        nowMillis: Long,
    ): Boolean = routeAvailable &&
        !result.confirmed &&
        !result.terminal &&
        remainingMillis(deadlineMillis, nowMillis) > 0L
}

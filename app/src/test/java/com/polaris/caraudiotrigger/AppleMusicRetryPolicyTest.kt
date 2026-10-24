package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.playback.AppleMusicResumeResult
import com.polaris.caraudiotrigger.playback.AppleMusicRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicRetryPolicyTest {
    @Test
    fun `persistent recovery has no time deadline`() {
        val deadline = AppleMusicRetryPolicy.deadline(1_000L, retryUntilDisconnected = true)

        assertEquals(Long.MAX_VALUE, deadline)
        assertTrue(
            AppleMusicRetryPolicy.shouldContinue(
                routeAvailable = true,
                result = AppleMusicResumeResult.NO_SESSION,
                deadlineMillis = deadline,
                nowMillis = Long.MAX_VALUE - 1,
            ),
        )
    }

    @Test
    fun `bounded recovery expires after sixty seconds`() {
        val deadline = AppleMusicRetryPolicy.deadline(1_000L, retryUntilDisconnected = false)

        assertEquals(61_000L, deadline)
        assertTrue(
            AppleMusicRetryPolicy.shouldContinue(
                routeAvailable = true,
                result = AppleMusicResumeResult.AUDIO_NOT_ACTIVE,
                deadlineMillis = deadline,
                nowMillis = 60_999L,
            ),
        )
        assertFalse(
            AppleMusicRetryPolicy.shouldContinue(
                routeAvailable = true,
                result = AppleMusicResumeResult.AUDIO_NOT_ACTIVE,
                deadlineMillis = deadline,
                nowMillis = 61_000L,
            ),
        )
    }

    @Test
    fun `disconnect success and terminal failure stop retries`() {
        val deadline = Long.MAX_VALUE
        assertFalse(
            AppleMusicRetryPolicy.shouldContinue(
                routeAvailable = false,
                result = AppleMusicResumeResult.NO_SESSION,
                deadlineMillis = deadline,
                nowMillis = 0L,
            ),
        )
        assertFalse(
            AppleMusicRetryPolicy.shouldContinue(
                routeAvailable = true,
                result = AppleMusicResumeResult.STARTED,
                deadlineMillis = deadline,
                nowMillis = 0L,
            ),
        )
        assertFalse(
            AppleMusicRetryPolicy.shouldContinue(
                routeAvailable = true,
                result = AppleMusicResumeResult.NOT_INSTALLED,
                deadlineMillis = deadline,
                nowMillis = 0L,
            ),
        )
    }
}

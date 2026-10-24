package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.playback.VolumeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMathTest {
    @Test
    fun `percent converts to stream steps with rounding`() {
        assertEquals(0, VolumeMath.percentToStreamIndex(0, 15))
        assertEquals(8, VolumeMath.percentToStreamIndex(50, 15))
        assertEquals(15, VolumeMath.percentToStreamIndex(100, 15))
    }

    @Test
    fun `out of range values are clamped`() {
        assertEquals(0, VolumeMath.percentToStreamIndex(-20, 25))
        assertEquals(25, VolumeMath.percentToStreamIndex(140, 25))
        assertEquals(0f, VolumeMath.percentToPlayerGain(-1))
        assertEquals(1f, VolumeMath.percentToPlayerGain(101))
    }

    @Test
    fun `system volume restores only when user did not change applied value`() {
        assertTrue(VolumeMath.shouldRestoreStreamVolume(currentIndex = 8, appliedIndex = 8))
        assertFalse(VolumeMath.shouldRestoreStreamVolume(currentIndex = 9, appliedIndex = 8))
    }
}

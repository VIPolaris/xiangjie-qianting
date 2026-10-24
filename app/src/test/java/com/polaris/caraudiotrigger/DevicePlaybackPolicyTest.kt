package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.device.DevicePlaybackMode
import com.polaris.caraudiotrigger.device.DevicePlaybackPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePlaybackPolicyTest {
    @Test
    fun `both switches off disables the device`() {
        assertEquals(DevicePlaybackMode.DISABLED, DevicePlaybackPolicy.resolve(false, false))
    }

    @Test
    fun `announcement can run without music`() {
        assertEquals(
            DevicePlaybackMode.ANNOUNCEMENT_ONLY,
            DevicePlaybackPolicy.resolve(true, false),
        )
    }

    @Test
    fun `music can run without announcement`() {
        assertEquals(
            DevicePlaybackMode.MUSIC_ONLY,
            DevicePlaybackPolicy.resolve(false, true),
        )
    }

    @Test
    fun `both switches preserve announcement then music flow`() {
        assertEquals(
            DevicePlaybackMode.ANNOUNCEMENT_THEN_MUSIC,
            DevicePlaybackPolicy.resolve(true, true),
        )
    }
}

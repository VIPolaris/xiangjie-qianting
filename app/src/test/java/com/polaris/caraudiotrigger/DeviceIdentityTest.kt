package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.device.DeviceIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun `address matching is case insensitive and trimmed`() {
        assertTrue(DeviceIdentity.matches("AA:BB:CC:11:22:33", " aa:bb:cc:11:22:33 "))
    }

    @Test
    fun `empty or different addresses never match`() {
        assertFalse(DeviceIdentity.matches("", "AA:BB:CC:11:22:33"))
        assertFalse(DeviceIdentity.matches("AA:BB:CC:11:22:33", null))
        assertFalse(DeviceIdentity.matches("AA:BB:CC:11:22:33", "AA:BB:CC:11:22:34"))
    }
}


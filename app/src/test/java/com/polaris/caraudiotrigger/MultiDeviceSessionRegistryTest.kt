package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.data.TargetDevice
import com.polaris.caraudiotrigger.device.MultiDeviceSessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDeviceSessionRegistryTest {
    private val first = TargetDevice(slot = 1, address = "AA:BB:CC:00:00:01")
    private val second = TargetDevice(slot = 2, address = "AA:BB:CC:00:00:02")

    @Test
    fun `duplicate broadcasts are suppressed independently for each device`() {
        val registry = MultiDeviceSessionRegistry()

        assertTrue(registry.onConnected(first))
        assertFalse(registry.onConnected(first))
        assertTrue(registry.onConnected(second))
        assertFalse(registry.onConnected(second))

        registry.resetSession(first.address)
        assertTrue(registry.onConnected(first))
        assertFalse(registry.onConnected(second))
    }

    @Test
    fun `unrelated disconnect does not release active target`() {
        val registry = MultiDeviceSessionRegistry()
        registry.claimActive(first.address)

        assertFalse(registry.releaseActive(second.address))
        assertTrue(registry.isActive(first.address))
    }

    @Test
    fun `new active route replaces previous route`() {
        val registry = MultiDeviceSessionRegistry()

        assertNull(registry.claimActive(first.address))
        assertEquals("AA:BB:CC:00:00:01", registry.claimActive(second.address))
        assertFalse(registry.isActive(first.address))
        assertTrue(registry.isActive(second.address))
    }
}

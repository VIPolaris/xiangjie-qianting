package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.device.ConnectionSessionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSessionGateTest {
    @Test
    fun `only first event in a connection starts an attempt`() {
        val gate = ConnectionSessionGate()
        assertTrue(gate.onConnected())
        assertFalse(gate.onConnected())
        assertTrue(gate.isConnected)
        assertTrue(gate.hasAttempted)
    }

    @Test
    fun `real disconnect resets session`() {
        val gate = ConnectionSessionGate()
        gate.onConnected()
        gate.onDisconnected()
        assertFalse(gate.isConnected)
        assertFalse(gate.hasAttempted)
        assertTrue(gate.onConnected())
    }

    @Test
    fun `restored attempted session remains idempotent`() {
        val gate = ConnectionSessionGate(connected = true, attempted = true)
        assertFalse(gate.onConnected())
    }
}


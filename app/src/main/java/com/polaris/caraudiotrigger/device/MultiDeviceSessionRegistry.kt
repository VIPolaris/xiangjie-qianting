package com.polaris.caraudiotrigger.device

import com.polaris.caraudiotrigger.data.SettingsRepository
import com.polaris.caraudiotrigger.data.TargetDevice

class MultiDeviceSessionRegistry {
    private val gates = mutableMapOf<String, ConnectionSessionGate>()
    private var activeAddress: String? = null

    fun onConnected(device: TargetDevice): Boolean {
        val key = key(device.address)
        val gate = gates.getOrPut(key) {
            ConnectionSessionGate(
                connected = device.sessionConnected,
                attempted = device.sessionAttempted,
            )
        }
        return gate.onConnected()
    }

    fun resetSession(address: String) {
        gates.remove(key(address))?.onDisconnected()
    }

    fun claimActive(address: String): String? {
        val previous = activeAddress
        activeAddress = key(address)
        return previous
    }

    fun releaseActive(address: String): Boolean {
        if (!isActive(address)) return false
        activeAddress = null
        return true
    }

    fun isActive(address: String): Boolean =
        activeAddress?.let { DeviceIdentity.matches(it, address) } == true

    private fun key(address: String): String = SettingsRepository.normalizeAddress(address)
}

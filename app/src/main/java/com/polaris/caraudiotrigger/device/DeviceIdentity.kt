package com.polaris.caraudiotrigger.device

import com.polaris.caraudiotrigger.data.SettingsRepository

object DeviceIdentity {
    fun matches(storedAddress: String, candidateAddress: String?): Boolean {
        if (storedAddress.isBlank() || candidateAddress.isNullOrBlank()) return false
        return SettingsRepository.normalizeAddress(storedAddress) ==
            SettingsRepository.normalizeAddress(candidateAddress)
    }
}


package com.polaris.caraudiotrigger.device

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class A2dpRouteResolver(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    suspend fun awaitTarget(
        targetAddress: String,
        timeoutMillis: Long = ROUTE_TIMEOUT_MILLIS,
    ): AudioDeviceInfo? = withTimeoutOrNull(timeoutMillis) {
        while (true) {
            findTarget(targetAddress)?.let { return@withTimeoutOrNull it }
            delay(POLL_INTERVAL_MILLIS)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    fun findTarget(targetAddress: String): AudioDeviceInfo? {
        val a2dpDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        a2dpDevices.firstOrNull {
            DeviceIdentity.matches(targetAddress, it.address)
        }?.let { return it }

        // A few vendor audio HALs hide a Bluetooth address. Falling back is safe only
        // when there is exactly one A2DP sink and it exposes no conflicting address.
        return a2dpDevices.singleOrNull()?.takeIf { it.address.isBlank() }
    }

    companion object {
        const val ROUTE_TIMEOUT_MILLIS = 20_000L
        private const val POLL_INTERVAL_MILLIS = 250L
    }
}


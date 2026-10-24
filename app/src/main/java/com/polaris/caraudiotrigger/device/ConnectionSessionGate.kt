package com.polaris.caraudiotrigger.device

class ConnectionSessionGate(
    connected: Boolean = false,
    attempted: Boolean = false,
) {
    var isConnected: Boolean = connected
        private set
    var hasAttempted: Boolean = attempted
        private set

    fun onConnected(): Boolean {
        isConnected = true
        if (hasAttempted) return false
        hasAttempted = true
        return true
    }

    fun onDisconnected() {
        isConnected = false
        hasAttempted = false
    }
}


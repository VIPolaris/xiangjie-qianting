package com.polaris.caraudiotrigger.device

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import com.polaris.caraudiotrigger.CarAudioApplication

class CarCompanionService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        super.onDevicePresenceEvent(event)
        val coordinator = (application as CarAudioApplication).connectionCoordinator
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                ConnectionTriggerService.start(this, associationId = event.associationId)
            }
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> {
                coordinator.onCompanionDisconnected(event.associationId)
            }
        }
    }
}

package com.polaris.caraudiotrigger.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.polaris.caraudiotrigger.CarAudioApplication
import kotlinx.coroutines.launch

class RestoreObservationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val application = context.applicationContext as CarAudioApplication
        application.applicationScope.launch {
            try {
                // A connection session cannot survive a phone restart. Keeping the old
                // de-duplication flag would suppress the first connection after boot.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    application.settingsRepository.clearAllSessions()
                }
                val restored = application.companionDeviceController.restoreObservation()
                if (!restored) {
                    val settings = application.settingsRepository.current()
                    if (settings.targetDevices.any { it.associationId != null }) {
                        application.settingsRepository.setStatus("后台观察恢复失败，请打开 App 检查设置")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

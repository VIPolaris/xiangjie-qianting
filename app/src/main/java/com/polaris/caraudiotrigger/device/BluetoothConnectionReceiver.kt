package com.polaris.caraudiotrigger.device

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.polaris.caraudiotrigger.CarAudioApplication

class BluetoothConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val device = intent.getParcelableExtra(
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java,
        ) ?: return
        val coordinator = (context.applicationContext as CarAudioApplication).connectionCoordinator
        when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        ConnectionTriggerService.start(context, address = device.address)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        finishWhenComplete(
                            coordinator.onAddressDisconnected(device.address, clearSession = false),
                        )
                    }
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                ConnectionTriggerService.start(context, address = device.address)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                finishWhenComplete(
                    coordinator.onAddressDisconnected(device.address, clearSession = true),
                )
            }
        }
    }

    private fun finishWhenComplete(job: kotlinx.coroutines.Job) {
        val pendingResult = goAsync()
        job.invokeOnCompletion { pendingResult.finish() }
    }
}

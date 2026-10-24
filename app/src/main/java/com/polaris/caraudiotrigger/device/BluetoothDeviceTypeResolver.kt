package com.polaris.caraudiotrigger.device

import android.bluetooth.BluetoothClass
import com.polaris.caraudiotrigger.data.DeviceType

object BluetoothDeviceTypeResolver {
    fun resolve(deviceClass: Int?): DeviceType? = when (deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> DeviceType.CAR_AUDIO
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
        -> DeviceType.HEADPHONES
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
        -> DeviceType.SPEAKER
        else -> null
    }
}

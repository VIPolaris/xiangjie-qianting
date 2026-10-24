package com.polaris.caraudiotrigger

import android.bluetooth.BluetoothClass
import com.polaris.caraudiotrigger.data.DeviceType
import com.polaris.caraudiotrigger.device.BluetoothDeviceTypeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothDeviceTypeResolverTest {
    @Test
    fun `car audio class maps to car audio`() {
        assertEquals(
            DeviceType.CAR_AUDIO,
            BluetoothDeviceTypeResolver.resolve(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO),
        )
    }

    @Test
    fun `headphone and headset classes map to headphones`() {
        assertEquals(
            DeviceType.HEADPHONES,
            BluetoothDeviceTypeResolver.resolve(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES),
        )
        assertEquals(
            DeviceType.HEADPHONES,
            BluetoothDeviceTypeResolver.resolve(BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET),
        )
    }

    @Test
    fun `speaker classes map to speaker and unknown remains unresolved`() {
        assertEquals(
            DeviceType.SPEAKER,
            BluetoothDeviceTypeResolver.resolve(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER),
        )
        assertNull(BluetoothDeviceTypeResolver.resolve(null))
        assertNull(BluetoothDeviceTypeResolver.resolve(0))
    }
}

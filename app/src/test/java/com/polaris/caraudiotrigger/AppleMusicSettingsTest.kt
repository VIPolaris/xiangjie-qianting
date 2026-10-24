package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.data.AppSettings
import com.polaris.caraudiotrigger.data.DeviceType
import com.polaris.caraudiotrigger.data.DeviceTypeSource
import com.polaris.caraudiotrigger.data.MusicProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicSettingsTest {
    @Test
    fun `new installs default per-device music to disabled and Apple Music selected`() {
        assertTrue(AppSettings().targetDevices.all { !it.musicEnabled })
        assertTrue(AppSettings().targetDevices.all { it.announcementEnabled })
        assertEquals(MusicProvider.APPLE_MUSIC, AppSettings().selectedMusicProvider)
    }

    @Test
    fun `new and upgraded installs retry until device disconnects by default`() {
        assertTrue(AppSettings().musicRetryUntilDisconnected)
    }

    @Test
    fun `unknown devices use speaker fallback until detected or selected`() {
        assertTrue(AppSettings().targetDevices.all { it.deviceType == DeviceType.SPEAKER })
        assertTrue(
            AppSettings().targetDevices.all {
                it.deviceTypeSource == DeviceTypeSource.DEFAULT_FALLBACK
            },
        )
    }
}

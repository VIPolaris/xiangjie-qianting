package com.polaris.caraudiotrigger

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polaris.caraudiotrigger.data.AudioSource
import com.polaris.caraudiotrigger.data.CONNECTION_HISTORY_LIMIT
import com.polaris.caraudiotrigger.data.DeviceType
import com.polaris.caraudiotrigger.data.DeviceTypeSource
import com.polaris.caraudiotrigger.data.MusicProvider
import com.polaris.caraudiotrigger.data.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryInstrumentedTest {
    private val file = File(
        ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        "settings-${UUID.randomUUID()}.preferences_pb",
    )
    private val store = PreferenceDataStoreFactory.create { file }
    private val repository = SettingsRepository(store)

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun threeDevicesAndIndependentSwitchesSurviveRepositoryRecreation() = runTest {
        assertTrue(repository.setTargetDevice(1, 42, "Honda CR-V", "aa:bb:cc:11:22:33"))
        assertTrue(repository.setTargetDevice(2, null, "Headset", "11:22:33:44:55:66"))
        assertTrue(repository.setTargetDevice(3, 77, "Second Car", "22:33:44:55:66:77"))
        repository.setDeviceAnnouncementEnabled(1, false)
        repository.setDeviceMusicEnabled(1, true)
        repository.setDeviceMusicEnabled(3, true)
        repository.setDeviceType(1, DeviceType.CAR_AUDIO, DeviceTypeSource.USER_SELECTED)
        repository.setPlayerVolumePercent(63)
        repository.setSystemVolumeEnabled(true)
        repository.setSystemVolumePercent(47)
        repository.setAudioSource(AudioSource.CUSTOM)
        repository.setMusicRetryUntilDisconnected(false)
        repository.markSessionAttempted(2)

        val restored = SettingsRepository(store).current()
        assertEquals(3, restored.configuredDeviceCount())
        assertEquals("AA:BB:CC:11:22:33", restored.device(1)?.address)
        assertFalse(restored.device(1)!!.announcementEnabled)
        assertTrue(restored.device(1)!!.musicEnabled)
        assertEquals(DeviceType.CAR_AUDIO, restored.device(1)!!.deviceType)
        assertEquals(DeviceTypeSource.USER_SELECTED, restored.device(1)!!.deviceTypeSource)
        assertTrue(restored.device(2)!!.sessionConnected)
        assertTrue(restored.device(2)!!.sessionAttempted)
        assertEquals(63, restored.playerVolumePercent)
        assertEquals(47, restored.systemVolumePercent)
        assertTrue(restored.systemVolumeEnabled)
        assertFalse(restored.musicRetryUntilDisconnected)
        assertEquals(MusicProvider.APPLE_MUSIC, restored.selectedMusicProvider)

        repository.clearSession(2)
        val disconnected = repository.current().device(2)!!
        assertFalse(disconnected.sessionConnected)
        assertFalse(disconnected.sessionAttempted)
    }

    @Test
    fun duplicateAddressCannotOccupyAnotherSlot() = runTest {
        assertTrue(repository.setTargetDevice(1, 1, "Car", "AA:BB:CC:DD:EE:FF"))
        assertFalse(repository.setTargetDevice(2, 2, "Duplicate", "aa:bb:cc:dd:ee:ff"))
        assertFalse(repository.current().device(2)!!.isConfigured)
    }

    @Test
    fun selectedMusicProviderSurvivesRepositoryRecreation() = runTest {
        MusicProvider.entries.forEach { provider ->
            repository.setSelectedMusicProvider(provider)
            assertEquals(provider, SettingsRepository(store).current().selectedMusicProvider)
        }
    }

    @Test
    fun connectionHistoryUpdatesSessionAndKeepsLatestTen() = runTest {
        repository.setTargetDevice(1, 1, "Car", "AA:BB:CC:DD:EE:FF")
        val device = repository.current().device(1)!!
        repeat(CONNECTION_HISTORY_LIMIT + 2) { index ->
            val at = 1_000L + index
            val id = repository.beginConnectionSession(device, atMillis = at)
            repository.setStatus("结果 $index", atMillis = at + 1, historyId = id)
            repository.endConnectionSession(1, atMillis = at + 2)
        }

        val history = repository.current().connectionHistory
        assertEquals(CONNECTION_HISTORY_LIMIT, history.size)
        assertEquals("结果 11", history.first().status)
        assertEquals("结果 2", history.last().status)
        assertEquals(1_013L, history.first().disconnectedAtMillis)
    }

    @Test
    fun version115SettingsMigrateIntoFirstSlotAndIgnoreDelay() = runTest {
        store.edit { preferences ->
            preferences[intPreferencesKey("association_id")] = 42
            preferences[stringPreferencesKey("device_name")] = "Honda CR-V"
            preferences[stringPreferencesKey("device_address")] = "aa:bb:cc:11:22:33"
            preferences[intPreferencesKey("delay_seconds")] = 14
            preferences[booleanPreferencesKey("apple_music_enabled")] = true
            preferences[stringPreferencesKey("last_status")] = "播报成功"
            preferences[longPreferencesKey("last_status_at")] = 1234L
            preferences[stringPreferencesKey("apple_music_fallback_mode")] = "MEDIA_KEY"
            preferences[booleanPreferencesKey("apple_music_stability_mode")] = false
        }

        val migrated = repository.current()
        val first = migrated.device(1)!!
        assertEquals(42, first.associationId)
        assertEquals("Honda CR-V", first.name)
        assertEquals("AA:BB:CC:11:22:33", first.address)
        assertTrue(first.announcementEnabled)
        assertTrue(first.musicEnabled)
        assertFalse(migrated.device(2)!!.isConfigured)
        assertTrue(migrated.musicRetryUntilDisconnected)
        assertEquals(MusicProvider.APPLE_MUSIC, migrated.selectedMusicProvider)
        assertEquals(DeviceType.SPEAKER, first.deviceType)
        assertEquals(DeviceTypeSource.DEFAULT_FALLBACK, first.deviceTypeSource)
        assertEquals(1, migrated.connectionHistory.size)
        assertTrue(migrated.connectionHistory.first().status.startsWith("升级前状态"))
        assertNull(first.activeHistoryId)
    }

    @Test
    fun version120SettingsMigrateMusicKeysAndPreserveDevices() = runTest {
        store.edit { preferences ->
            preferences[intPreferencesKey("settings_schema_version")] = 2
            preferences[stringPreferencesKey("device_1_name")] = "Saved speaker"
            preferences[stringPreferencesKey("device_1_address")] = "12:34:56:78:9a:bc"
            preferences[booleanPreferencesKey("device_1_announcement_enabled")] = false
            preferences[booleanPreferencesKey("device_1_apple_music_enabled")] = true
            preferences[booleanPreferencesKey("apple_music_retry_until_disconnected")] = false
        }

        val migrated = repository.current()
        val first = migrated.device(1)!!
        assertEquals("Saved speaker", first.name)
        assertEquals("12:34:56:78:9A:BC", first.address)
        assertFalse(first.announcementEnabled)
        assertTrue(first.musicEnabled)
        assertEquals(MusicProvider.APPLE_MUSIC, migrated.selectedMusicProvider)
        assertFalse(migrated.musicRetryUntilDisconnected)
        assertEquals(DeviceType.SPEAKER, first.deviceType)
    }
}

package com.polaris.caraudiotrigger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "car_audio_settings",
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.settingsDataStore)

    private val migrationMutex = Mutex()
    @Volatile
    private var migrationChecked = false

    val settings: Flow<AppSettings> = flow {
        ensureMigrated()
        emitAll(dataStore.data.map(::toSettings))
    }

    suspend fun current(): AppSettings {
        ensureMigrated()
        return toSettings(dataStore.data.first())
    }

    suspend fun setEnabled(value: Boolean) = update { this[Keys.ENABLED] = value }

    suspend fun setPlayerVolumePercent(value: Int) = update {
        this[Keys.PLAYER_VOLUME_PERCENT] = value.coerceIn(0, 100)
    }

    suspend fun setSystemVolumeEnabled(value: Boolean) = update {
        this[Keys.SYSTEM_VOLUME_ENABLED] = value
    }

    suspend fun setSystemVolumePercent(value: Int) = update {
        this[Keys.SYSTEM_VOLUME_PERCENT] = value.coerceIn(0, 100)
    }

    suspend fun setAudioSource(value: AudioSource) = update {
        this[Keys.AUDIO_SOURCE] = value.name
    }

    suspend fun setMusicRetryUntilDisconnected(value: Boolean) = update {
        this[Keys.MUSIC_RETRY_UNTIL_DISCONNECTED] = value
    }

    suspend fun setSelectedMusicProvider(value: MusicProvider) = update {
        this[Keys.SELECTED_MUSIC_PROVIDER] = value.name
    }

    suspend fun setCustomAudio(path: String, displayName: String) = update {
        this[Keys.CUSTOM_AUDIO_PATH] = path
        this[Keys.CUSTOM_AUDIO_NAME] = displayName
        this[Keys.AUDIO_SOURCE] = AudioSource.CUSTOM.name
    }

    suspend fun setTargetDevice(
        slot: Int,
        associationId: Int?,
        name: String,
        address: String,
        deviceType: DeviceType = DeviceType.SPEAKER,
        deviceTypeSource: DeviceTypeSource = DeviceTypeSource.DEFAULT_FALLBACK,
    ): Boolean {
        requireValidSlot(slot)
        val normalized = normalizeAddress(address)
        if (normalized.isBlank()) return false
        var saved = false
        update {
            val duplicate = (1..TARGET_DEVICE_SLOT_COUNT).any { otherSlot ->
                otherSlot != slot && normalizeAddress(this[deviceAddressKey(otherSlot)].orEmpty()) == normalized
            }
            if (!duplicate) {
                if (associationId == null) remove(deviceAssociationIdKey(slot))
                else this[deviceAssociationIdKey(slot)] = associationId
                this[deviceNameKey(slot)] = name
                this[deviceAddressKey(slot)] = normalized
                if (!contains(deviceAnnouncementEnabledKey(slot))) {
                    this[deviceAnnouncementEnabledKey(slot)] = true
                }
                if (!contains(deviceMusicEnabledKey(slot))) {
                    this[deviceMusicEnabledKey(slot)] = false
                }
                this[deviceTypeKey(slot)] = deviceType.name
                this[deviceTypeSourceKey(slot)] = deviceTypeSource.name
                clearDeviceSession(this, slot)
                saved = true
            }
        }
        return saved
    }

    suspend fun clearTargetDevice(slot: Int) = update {
        requireValidSlot(slot)
        remove(deviceAssociationIdKey(slot))
        remove(deviceNameKey(slot))
        remove(deviceAddressKey(slot))
        remove(deviceAnnouncementEnabledKey(slot))
        remove(deviceMusicEnabledKey(slot))
        remove(deviceTypeKey(slot))
        remove(deviceTypeSourceKey(slot))
        clearDeviceSession(this, slot)
    }

    suspend fun setDeviceAnnouncementEnabled(slot: Int, value: Boolean) = update {
        requireValidSlot(slot)
        this[deviceAnnouncementEnabledKey(slot)] = value
    }

    suspend fun setDeviceMusicEnabled(slot: Int, value: Boolean) = update {
        requireValidSlot(slot)
        this[deviceMusicEnabledKey(slot)] = value
    }

    suspend fun setDeviceType(
        slot: Int,
        value: DeviceType,
        source: DeviceTypeSource,
    ) = update {
        requireValidSlot(slot)
        this[deviceTypeKey(slot)] = value.name
        this[deviceTypeSourceKey(slot)] = source.name
    }

    suspend fun markSessionConnected(slot: Int, attempted: Boolean) = update {
        requireValidSlot(slot)
        this[deviceSessionConnectedKey(slot)] = true
        this[deviceSessionAttemptedKey(slot)] = attempted
    }

    suspend fun markSessionAttempted(slot: Int) = markSessionConnected(slot, attempted = true)

    suspend fun clearSession(slot: Int) = update {
        requireValidSlot(slot)
        clearDeviceSession(this, slot)
    }

    suspend fun clearAllSessions() = update {
        (1..TARGET_DEVICE_SLOT_COUNT).forEach { slot -> clearDeviceSession(this, slot) }
    }

    suspend fun beginConnectionSession(
        device: TargetDevice,
        atMillis: Long = System.currentTimeMillis(),
    ): Long {
        requireValidSlot(device.slot)
        var historyId = atMillis
        update {
            val history = decodeHistory(this[Keys.CONNECTION_HISTORY].orEmpty()).toMutableList()
            historyId = maxOf(atMillis, (history.maxOfOrNull(ConnectionHistoryEntry::id) ?: 0L) + 1L)
            val entry = ConnectionHistoryEntry(
                id = historyId,
                deviceName = device.name.ifBlank { "蓝牙设备" },
                deviceAddress = normalizeAddress(device.address),
                connectedAtMillis = atMillis,
                updatedAtMillis = atMillis,
                status = "设备已连接，正在等待媒体通道",
            )
            this[Keys.CONNECTION_HISTORY] = encodeHistory(
                (listOf(entry) + history.filterNot { it.id == historyId })
                    .take(CONNECTION_HISTORY_LIMIT),
            )
            this[deviceActiveHistoryIdKey(device.slot)] = historyId
            this[Keys.LAST_STATUS] = entry.status
            this[Keys.LAST_STATUS_AT] = atMillis
        }
        return historyId
    }

    suspend fun endConnectionSession(
        slot: Int,
        atMillis: Long = System.currentTimeMillis(),
    ) = update {
        requireValidSlot(slot)
        val historyId = this[deviceActiveHistoryIdKey(slot)]
        if (historyId != null) {
            val updated = decodeHistory(this[Keys.CONNECTION_HISTORY].orEmpty()).map { entry ->
                if (entry.id == historyId) {
                    entry.copy(updatedAtMillis = atMillis, disconnectedAtMillis = atMillis)
                } else {
                    entry
                }
            }
            this[Keys.CONNECTION_HISTORY] = encodeHistory(updated.take(CONNECTION_HISTORY_LIMIT))
        }
        clearDeviceSession(this, slot)
    }

    suspend fun setStatus(
        message: String,
        atMillis: Long = System.currentTimeMillis(),
        historyId: Long? = null,
    ) = update {
        this[Keys.LAST_STATUS] = message
        this[Keys.LAST_STATUS_AT] = atMillis
        if (historyId != null) {
            val updated = decodeHistory(this[Keys.CONNECTION_HISTORY].orEmpty()).map { entry ->
                if (entry.id == historyId) {
                    entry.copy(status = message, updatedAtMillis = atMillis)
                } else {
                    entry
                }
            }
            this[Keys.CONNECTION_HISTORY] = encodeHistory(updated.take(CONNECTION_HISTORY_LIMIT))
        }
    }

    private suspend fun update(block: suspend MutablePreferences.() -> Unit) {
        ensureMigrated()
        dataStore.edit { preferences -> preferences.block() }
    }

    private suspend fun ensureMigrated() {
        if (migrationChecked) return
        migrationMutex.withLock {
            if (migrationChecked) return@withLock
            dataStore.edit { preferences ->
                val schemaVersion = preferences[Keys.SCHEMA_VERSION] ?: 1
                if (schemaVersion < 2) {
                    migrateLegacySettings(preferences)
                }
                if (schemaVersion < 3) {
                    migrateV2Settings(preferences)
                }
                if (schemaVersion < CURRENT_SCHEMA_VERSION) {
                    preferences[Keys.SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
                }
            }
            migrationChecked = true
        }
    }

    private fun migrateLegacySettings(preferences: MutablePreferences) {
        val legacyAddress = normalizeAddress(preferences[Keys.LEGACY_DEVICE_ADDRESS].orEmpty())
        if (legacyAddress.isNotBlank()) {
            preferences[Keys.LEGACY_ASSOCIATION_ID]?.let {
                preferences[deviceAssociationIdKey(1)] = it
            }
            preferences[deviceNameKey(1)] = preferences[Keys.LEGACY_DEVICE_NAME].orEmpty()
            preferences[deviceAddressKey(1)] = legacyAddress
            preferences[deviceAnnouncementEnabledKey(1)] = true
            preferences[legacyDeviceAppleMusicEnabledKey(1)] =
                preferences[Keys.LEGACY_APPLE_MUSIC_ENABLED] ?: false
        }
        (1..TARGET_DEVICE_SLOT_COUNT).forEach { slot -> clearDeviceSession(preferences, slot) }

        val legacyStatus = preferences[Keys.LAST_STATUS]
        val legacyStatusAt = preferences[Keys.LAST_STATUS_AT] ?: 0L
        if (!legacyStatus.isNullOrBlank() && legacyStatus != "尚未触发") {
            val historyId = if (legacyStatusAt > 0L) legacyStatusAt else System.currentTimeMillis()
            preferences[Keys.CONNECTION_HISTORY] = encodeHistory(
                listOf(
                    ConnectionHistoryEntry(
                        id = historyId,
                        deviceName = preferences[Keys.LEGACY_DEVICE_NAME]
                            ?.takeIf(String::isNotBlank) ?: "升级前状态",
                        deviceAddress = legacyAddress,
                        connectedAtMillis = historyId,
                        updatedAtMillis = historyId,
                        status = "升级前状态：$legacyStatus",
                    ),
                ),
            )
        }
    }

    private fun migrateV2Settings(preferences: MutablePreferences) {
        (1..TARGET_DEVICE_SLOT_COUNT).forEach { slot ->
            if (!preferences.contains(deviceMusicEnabledKey(slot))) {
                preferences[deviceMusicEnabledKey(slot)] =
                    preferences[legacyDeviceAppleMusicEnabledKey(slot)] ?: false
            }
            if (!preferences.contains(deviceTypeKey(slot))) {
                preferences[deviceTypeKey(slot)] = DeviceType.SPEAKER.name
            }
            if (!preferences.contains(deviceTypeSourceKey(slot))) {
                preferences[deviceTypeSourceKey(slot)] = DeviceTypeSource.DEFAULT_FALLBACK.name
            }
        }
        if (!preferences.contains(Keys.SELECTED_MUSIC_PROVIDER)) {
            preferences[Keys.SELECTED_MUSIC_PROVIDER] = MusicProvider.APPLE_MUSIC.name
        }
        if (!preferences.contains(Keys.MUSIC_RETRY_UNTIL_DISCONNECTED)) {
            preferences[Keys.MUSIC_RETRY_UNTIL_DISCONNECTED] =
                preferences[Keys.LEGACY_APPLE_MUSIC_RETRY_UNTIL_DISCONNECTED] ?: true
        }
    }

    private fun toSettings(preferences: Preferences): AppSettings = AppSettings(
        enabled = preferences[Keys.ENABLED] ?: true,
        targetDevices = (1..TARGET_DEVICE_SLOT_COUNT).map { slot ->
            TargetDevice(
                slot = slot,
                associationId = preferences[deviceAssociationIdKey(slot)],
                name = preferences[deviceNameKey(slot)].orEmpty(),
                address = normalizeAddress(preferences[deviceAddressKey(slot)].orEmpty()),
                announcementEnabled = preferences[deviceAnnouncementEnabledKey(slot)] ?: true,
                musicEnabled = preferences[deviceMusicEnabledKey(slot)]
                    ?: preferences[legacyDeviceAppleMusicEnabledKey(slot)]
                    ?: false,
                deviceType = enumValueOrDefault(
                    preferences[deviceTypeKey(slot)],
                    DeviceType.SPEAKER,
                ),
                deviceTypeSource = enumValueOrDefault(
                    preferences[deviceTypeSourceKey(slot)],
                    DeviceTypeSource.DEFAULT_FALLBACK,
                ),
                sessionConnected = preferences[deviceSessionConnectedKey(slot)] ?: false,
                sessionAttempted = preferences[deviceSessionAttemptedKey(slot)] ?: false,
                activeHistoryId = preferences[deviceActiveHistoryIdKey(slot)],
            )
        },
        playerVolumePercent = (
            preferences[Keys.PLAYER_VOLUME_PERCENT]
                ?: preferences[Keys.LEGACY_PLAYER_VOLUME]?.times(100)?.toInt()
                ?: 80
            ).coerceIn(0, 100),
        systemVolumeEnabled = preferences[Keys.SYSTEM_VOLUME_ENABLED] ?: false,
        systemVolumePercent = (preferences[Keys.SYSTEM_VOLUME_PERCENT] ?: 50).coerceIn(0, 100),
        audioSource = runCatching {
            AudioSource.valueOf(preferences[Keys.AUDIO_SOURCE] ?: AudioSource.BUILT_IN.name)
        }.getOrDefault(AudioSource.BUILT_IN),
        customAudioPath = preferences[Keys.CUSTOM_AUDIO_PATH].orEmpty(),
        customAudioName = preferences[Keys.CUSTOM_AUDIO_NAME].orEmpty(),
        selectedMusicProvider = enumValueOrDefault(
            preferences[Keys.SELECTED_MUSIC_PROVIDER],
            MusicProvider.APPLE_MUSIC,
        ),
        musicRetryUntilDisconnected = preferences[Keys.MUSIC_RETRY_UNTIL_DISCONNECTED]
            ?: preferences[Keys.LEGACY_APPLE_MUSIC_RETRY_UNTIL_DISCONNECTED]
            ?: true,
        connectionHistory = decodeHistory(preferences[Keys.CONNECTION_HISTORY].orEmpty())
            .sortedByDescending(ConnectionHistoryEntry::connectedAtMillis)
            .take(CONNECTION_HISTORY_LIMIT),
        lastStatus = preferences[Keys.LAST_STATUS] ?: "尚未触发",
        lastStatusAtMillis = preferences[Keys.LAST_STATUS_AT] ?: 0L,
    )

    private object Keys {
        val SCHEMA_VERSION = intPreferencesKey("settings_schema_version")
        val ENABLED = booleanPreferencesKey("enabled")
        val PLAYER_VOLUME_PERCENT = intPreferencesKey("player_volume_percent")
        val LEGACY_PLAYER_VOLUME = floatPreferencesKey("player_volume")
        val SYSTEM_VOLUME_ENABLED = booleanPreferencesKey("system_volume_enabled")
        val SYSTEM_VOLUME_PERCENT = intPreferencesKey("system_volume_percent")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val CUSTOM_AUDIO_PATH = stringPreferencesKey("custom_audio_path")
        val CUSTOM_AUDIO_NAME = stringPreferencesKey("custom_audio_name")
        val SELECTED_MUSIC_PROVIDER = stringPreferencesKey("selected_music_provider")
        val MUSIC_RETRY_UNTIL_DISCONNECTED =
            booleanPreferencesKey("music_retry_until_disconnected")
        val LEGACY_APPLE_MUSIC_RETRY_UNTIL_DISCONNECTED =
            booleanPreferencesKey("apple_music_retry_until_disconnected")
        val CONNECTION_HISTORY = stringPreferencesKey("connection_history_json")
        val LAST_STATUS = stringPreferencesKey("last_status")
        val LAST_STATUS_AT = longPreferencesKey("last_status_at")

        val LEGACY_ASSOCIATION_ID = intPreferencesKey("association_id")
        val LEGACY_DEVICE_NAME = stringPreferencesKey("device_name")
        val LEGACY_DEVICE_ADDRESS = stringPreferencesKey("device_address")
        val LEGACY_APPLE_MUSIC_ENABLED = booleanPreferencesKey("apple_music_enabled")
    }

    companion object {
        private const val CURRENT_SCHEMA_VERSION = 3

        fun normalizeAddress(address: String): String = address.trim().uppercase()

        private fun requireValidSlot(slot: Int) {
            require(slot in 1..TARGET_DEVICE_SLOT_COUNT) { "Invalid target device slot: $slot" }
        }

        private fun deviceAssociationIdKey(slot: Int) = intPreferencesKey("device_${slot}_association_id")
        private fun deviceNameKey(slot: Int) = stringPreferencesKey("device_${slot}_name")
        private fun deviceAddressKey(slot: Int) = stringPreferencesKey("device_${slot}_address")
        private fun deviceAnnouncementEnabledKey(slot: Int) =
            booleanPreferencesKey("device_${slot}_announcement_enabled")
        private fun deviceMusicEnabledKey(slot: Int) =
            booleanPreferencesKey("device_${slot}_music_enabled")
        private fun legacyDeviceAppleMusicEnabledKey(slot: Int) =
            booleanPreferencesKey("device_${slot}_apple_music_enabled")
        private fun deviceTypeKey(slot: Int) = stringPreferencesKey("device_${slot}_type")
        private fun deviceTypeSourceKey(slot: Int) =
            stringPreferencesKey("device_${slot}_type_source")
        private fun deviceSessionConnectedKey(slot: Int) =
            booleanPreferencesKey("device_${slot}_session_connected")
        private fun deviceSessionAttemptedKey(slot: Int) =
            booleanPreferencesKey("device_${slot}_session_attempted")
        private fun deviceActiveHistoryIdKey(slot: Int) =
            longPreferencesKey("device_${slot}_active_history_id")

        private fun clearDeviceSession(preferences: MutablePreferences, slot: Int) {
            preferences[deviceSessionConnectedKey(slot)] = false
            preferences[deviceSessionAttemptedKey(slot)] = false
            preferences.remove(deviceActiveHistoryIdKey(slot))
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(
            value: String?,
            default: T,
        ): T = runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)

        private fun encodeHistory(history: List<ConnectionHistoryEntry>): String {
            val array = JSONArray()
            history.take(CONNECTION_HISTORY_LIMIT).forEach { entry ->
                array.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("deviceName", entry.deviceName)
                        .put("deviceAddress", entry.deviceAddress)
                        .put("connectedAtMillis", entry.connectedAtMillis)
                        .put("updatedAtMillis", entry.updatedAtMillis)
                        .put("status", entry.status)
                        .put("disconnectedAtMillis", entry.disconnectedAtMillis ?: JSONObject.NULL),
                )
            }
            return array.toString()
        }

        private fun decodeHistory(encoded: String): List<ConnectionHistoryEntry> {
            if (encoded.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(encoded)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optLong("id", 0L)
                        if (id <= 0L) continue
                        add(
                            ConnectionHistoryEntry(
                                id = id,
                                deviceName = item.optString("deviceName"),
                                deviceAddress = normalizeAddress(item.optString("deviceAddress")),
                                connectedAtMillis = item.optLong("connectedAtMillis", id),
                                updatedAtMillis = item.optLong("updatedAtMillis", id),
                                status = item.optString("status"),
                                disconnectedAtMillis = if (item.isNull("disconnectedAtMillis")) {
                                    null
                                } else {
                                    item.optLong("disconnectedAtMillis")
                                },
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}

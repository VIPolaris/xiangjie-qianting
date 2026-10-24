package com.polaris.caraudiotrigger.data

enum class AudioSource {
    BUILT_IN,
    CUSTOM,
}

enum class DeviceType {
    CAR_AUDIO,
    HEADPHONES,
    SPEAKER,
}

enum class DeviceTypeSource {
    AUTO_DETECTED,
    DEFAULT_FALLBACK,
    USER_SELECTED,
}

enum class MusicProvider(val displayName: String) {
    APPLE_MUSIC("Apple Music"),
    SPOTIFY("Spotify"),
    QQ_MUSIC("QQ音乐"),
    NETEASE_CLOUD_MUSIC("网易云音乐"),
    KUGOU_MUSIC("酷狗音乐"),
}

data class TargetDevice(
    val slot: Int,
    val associationId: Int? = null,
    val name: String = "",
    val address: String = "",
    val announcementEnabled: Boolean = true,
    val musicEnabled: Boolean = false,
    val deviceType: DeviceType = DeviceType.SPEAKER,
    val deviceTypeSource: DeviceTypeSource = DeviceTypeSource.DEFAULT_FALLBACK,
    val sessionConnected: Boolean = false,
    val sessionAttempted: Boolean = false,
    val activeHistoryId: Long? = null,
) {
    val isConfigured: Boolean
        get() = address.isNotBlank()
}

data class ConnectionHistoryEntry(
    val id: Long,
    val deviceName: String,
    val deviceAddress: String,
    val connectedAtMillis: Long,
    val updatedAtMillis: Long,
    val status: String,
    val disconnectedAtMillis: Long? = null,
)

data class AppSettings(
    val enabled: Boolean = true,
    val targetDevices: List<TargetDevice> = defaultTargetDevices(),
    val playerVolumePercent: Int = 80,
    val systemVolumeEnabled: Boolean = false,
    val systemVolumePercent: Int = 50,
    val audioSource: AudioSource = AudioSource.BUILT_IN,
    val customAudioPath: String = "",
    val customAudioName: String = "",
    val selectedMusicProvider: MusicProvider = MusicProvider.APPLE_MUSIC,
    val musicRetryUntilDisconnected: Boolean = true,
    val connectionHistory: List<ConnectionHistoryEntry> = emptyList(),
    val lastStatus: String = "尚未触发",
    val lastStatusAtMillis: Long = 0L,
) {
    fun device(slot: Int): TargetDevice? = targetDevices.firstOrNull { it.slot == slot }

    fun configuredDeviceCount(): Int = targetDevices.count(TargetDevice::isConfigured)
}

const val TARGET_DEVICE_SLOT_COUNT = 3
const val CONNECTION_HISTORY_LIMIT = 10

fun defaultTargetDevices(): List<TargetDevice> =
    (1..TARGET_DEVICE_SLOT_COUNT).map(::TargetDevice)

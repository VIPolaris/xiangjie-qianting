package com.polaris.caraudiotrigger.ui

import com.polaris.caraudiotrigger.data.AppSettings
import com.polaris.caraudiotrigger.device.PairedBluetoothDevice

data class ReadinessState(
    val bluetoothPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val companionAssociationCount: Int = 0,
    val androidBatteryOptimizationExempt: Boolean = false,
    val selectedMusicProviderInstalled: Boolean = false,
    val musicNotificationAccessGranted: Boolean = false,
)

data class DevicePickerState(
    val visible: Boolean = false,
    val slot: Int = 1,
    val devices: List<PairedBluetoothDevice> = emptyList(),
    val message: String? = null,
    val loading: Boolean = false,
)

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val readiness: ReadinessState = ReadinessState(),
    val devicePicker: DevicePickerState = DevicePickerState(),
    val isImportingAudio: Boolean = false,
)

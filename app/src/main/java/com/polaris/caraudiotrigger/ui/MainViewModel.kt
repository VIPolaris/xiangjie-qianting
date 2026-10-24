package com.polaris.caraudiotrigger.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.polaris.caraudiotrigger.CarAudioApplication
import com.polaris.caraudiotrigger.data.AudioSource
import com.polaris.caraudiotrigger.data.DeviceType
import com.polaris.caraudiotrigger.data.DeviceTypeSource
import com.polaris.caraudiotrigger.data.MusicProvider
import com.polaris.caraudiotrigger.playback.MusicProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CarAudioApplication
    private val readiness = MutableStateFlow(ReadinessState())
    private val devicePicker = MutableStateFlow(DevicePickerState())
    private val importingAudio = MutableStateFlow(false)

    val uiState = combine(
        app.settingsRepository.settings,
        readiness,
        devicePicker,
        importingAudio,
    ) { settings, readinessState, picker, importing ->
        MainUiState(settings, readinessState, picker, importing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        refreshReadiness()
        viewModelScope.launch(Dispatchers.IO) {
            app.companionDeviceController.restoreObservation()
        }
    }

    fun refreshReadiness() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val bluetoothGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (bluetoothGranted) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        app.companionDeviceController.refreshConfiguredDeviceTypes()
                    }
                }
            }
            val settings = app.settingsRepository.current()
            val powerManager = context.getSystemService(PowerManager::class.java)
            val musicController = MusicProviderRegistry.create(
                context,
                settings.selectedMusicProvider,
            )
            readiness.value = ReadinessState(
                bluetoothPermissionGranted = bluetoothGranted,
                notificationPermissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
                companionAssociationCount = app.companionDeviceController.activeAssociationCount(
                    settings.targetDevices,
                ),
                androidBatteryOptimizationExempt =
                    powerManager.isIgnoringBatteryOptimizations(context.packageName),
                selectedMusicProviderInstalled = musicController.isInstalled(),
                musicNotificationAccessGranted = musicController.hasControlAccess(),
            )
        }
    }

    fun setEnabled(value: Boolean) = launchUpdate { app.settingsRepository.setEnabled(value) }

    fun setPlayerVolume(value: Int) = launchUpdate {
        app.settingsRepository.setPlayerVolumePercent(value)
    }

    fun setSystemVolumeEnabled(value: Boolean) = launchUpdate {
        app.settingsRepository.setSystemVolumeEnabled(value)
    }

    fun setSystemVolume(value: Int) = launchUpdate {
        app.settingsRepository.setSystemVolumePercent(value)
    }

    fun setAudioSource(source: AudioSource) = launchUpdate {
        app.settingsRepository.setAudioSource(source)
    }

    fun setDeviceAnnouncementEnabled(slot: Int, value: Boolean) = launchUpdate {
        app.settingsRepository.setDeviceAnnouncementEnabled(slot, value)
    }

    fun setDeviceMusicEnabled(slot: Int, value: Boolean) = launchUpdate {
        app.settingsRepository.setDeviceMusicEnabled(slot, value)
    }

    fun setDeviceType(slot: Int, value: DeviceType) = launchUpdate {
        app.settingsRepository.setDeviceType(slot, value, DeviceTypeSource.USER_SELECTED)
    }

    fun setMusicProvider(value: MusicProvider) {
        viewModelScope.launch {
            app.settingsRepository.setSelectedMusicProvider(value)
            refreshReadiness()
        }
    }

    fun setMusicRetryUntilDisconnected(value: Boolean) = launchUpdate {
        app.settingsRepository.setMusicRetryUntilDisconnected(value)
    }

    fun openDevicePicker(slot: Int) {
        devicePicker.value = DevicePickerState(visible = true, slot = slot, loading = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { app.companionDeviceController.pairedDevices() }
            }.onSuccess { devices ->
                devicePicker.value = DevicePickerState(
                    visible = true,
                    slot = slot,
                    devices = devices,
                    message = if (devices.isEmpty()) "手机中没有已配对的蓝牙设备" else null,
                )
            }.onFailure { error ->
                devicePicker.value = DevicePickerState(
                    visible = true,
                    slot = slot,
                    message = error.localizedMessage ?: "无法读取已配对设备",
                )
            }
        }
    }

    fun dismissDevicePicker() {
        devicePicker.value = DevicePickerState()
    }

    fun selectPairedDevice(slot: Int, address: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val previous = app.settingsRepository.current().device(slot)
                    if (previous?.isConfigured == true &&
                        !com.polaris.caraudiotrigger.device.DeviceIdentity.matches(
                            previous.address,
                            address,
                        )
                    ) {
                        app.connectionCoordinator.onAddressDisconnected(
                            previous.address,
                            clearSession = true,
                        ).join()
                    }
                    app.companionDeviceController.selectPairedDevice(slot, address)
                }
            }.onSuccess { message ->
                dismissDevicePicker()
                showMessage(message)
            }.onFailure { error ->
                devicePicker.value = devicePicker.value.copy(
                    loading = false,
                    message = error.localizedMessage ?: "选择设备失败",
                )
            }
            refreshReadiness()
        }
    }

    fun removeDevice(slot: Int) {
        viewModelScope.launch {
            val previous = app.settingsRepository.current().device(slot)
            if (previous?.isConfigured == true) {
                app.connectionCoordinator.onAddressDisconnected(
                    previous.address,
                    clearSession = true,
                ).join()
            }
            app.companionDeviceController.removeDevice(slot)
            refreshReadiness()
        }
    }

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            importingAudio.value = true
            runCatching {
                withContext(Dispatchers.IO) { app.audioFileStore.import(uri) }
            }.onSuccess { imported ->
                app.settingsRepository.setCustomAudio(imported.path, imported.displayName)
                app.settingsRepository.setStatus("已导入音频：${imported.displayName}")
            }.onFailure { error ->
                app.settingsRepository.setStatus(
                    "音频导入失败：${error.localizedMessage ?: "未知错误"}",
                )
            }
            importingAudio.value = false
        }
    }

    fun testPlayback(slot: Int) = app.connectionCoordinator.requestTest(slot)

    fun showMessage(message: String) = launchUpdate {
        app.settingsRepository.setStatus(message)
        refreshReadiness()
    }

    private fun launchUpdate(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(application) as T
        }
    }
}

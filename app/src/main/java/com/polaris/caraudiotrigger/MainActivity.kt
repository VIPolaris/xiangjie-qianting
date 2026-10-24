package com.polaris.caraudiotrigger

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polaris.caraudiotrigger.data.MusicProvider
import com.polaris.caraudiotrigger.playback.AppleMusicNotificationListener
import com.polaris.caraudiotrigger.playback.MusicProviderRegistry
import com.polaris.caraudiotrigger.ui.MainScreen
import com.polaris.caraudiotrigger.ui.MainScreenActions
import com.polaris.caraudiotrigger.ui.MainViewModel
import com.polaris.caraudiotrigger.ui.theme.CarAudioTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> {
        MainViewModel.Factory(application)
    }

    private val associationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        viewModel.refreshReadiness()
    }

    private var pendingBluetoothDeviceSlot: Int? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshReadiness()
        if (granted) {
            pendingBluetoothDeviceSlot?.let(viewModel::openDevicePicker)
            pendingBluetoothDeviceSlot = null
        } else {
            pendingBluetoothDeviceSlot = null
            viewModel.showMessage("未授予附近设备权限，无法识别蓝牙设备")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshReadiness()
        if (!granted) viewModel.showMessage("未授予通知权限；播放服务仍会运行，但通知可能不可见")
    }

    private val audioPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importAudio(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CarAudioTheme {
                MainScreen(
                    state = state,
                    actions = MainScreenActions(
                        onEnabledChange = viewModel::setEnabled,
                        onChooseDevice = ::chooseDevice,
                        onSelectPairedDevice = viewModel::selectPairedDevice,
                        onDismissDevicePicker = viewModel::dismissDevicePicker,
                        onDiscoverNewDevice = ::startCompanionAssociation,
                        onRemoveDevice = viewModel::removeDevice,
                        onDeviceAnnouncementEnabledChange =
                            viewModel::setDeviceAnnouncementEnabled,
                        onDeviceMusicEnabledChange = viewModel::setDeviceMusicEnabled,
                        onDeviceTypeChange = viewModel::setDeviceType,
                        onChooseAudio = { audioPicker.launch(arrayOf("audio/*")) },
                        onAudioSourceChange = viewModel::setAudioSource,
                        onPlayerVolumeChange = viewModel::setPlayerVolume,
                        onSystemVolumeEnabledChange = viewModel::setSystemVolumeEnabled,
                        onSystemVolumeChange = viewModel::setSystemVolume,
                        onMusicProviderChange = viewModel::setMusicProvider,
                        onMusicRetryUntilDisconnectedChange =
                            viewModel::setMusicRetryUntilDisconnected,
                        onTestPlayback = viewModel::testPlayback,
                        onRequestBluetoothPermission = ::requestBluetoothPermission,
                        onRequestNotificationPermission = ::requestNotificationPermission,
                        onRequestBatteryUnrestricted = ::requestBatteryUnrestricted,
                        onOpenMusicAccessSettings = ::openMusicAccessSettings,
                        onOpenMusicProviderStore = ::openMusicProviderStore,
                        onOpenAppSettings = ::openAppSettings,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshReadiness()
    }

    private fun chooseDevice(slot: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingBluetoothDeviceSlot = slot
            requestBluetoothPermission()
        } else {
            viewModel.openDevicePicker(slot)
        }
    }

    private fun requestBluetoothPermission() {
        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startCompanionAssociation(slot: Int) {
        viewModel.dismissDevicePicker()
        (application as CarAudioApplication).companionDeviceController.associate(
            slot = slot,
            activity = this,
            launchIntentSender = { sender ->
                associationLauncher.launch(IntentSenderRequest.Builder(sender).build())
            },
            onMessage = viewModel::showMessage,
        )
    }

    private fun requestBatteryUnrestricted() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun openMusicAccessSettings() {
        val component = ComponentName(this, AppleMusicNotificationListener::class.java)
        val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component.flattenToString(),
            )
        runCatching { startActivity(detailIntent) }
            .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun openMusicProviderStore(provider: MusicProvider) {
        val descriptor = MusicProviderRegistry.descriptor(provider)
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${descriptor.packageName}"),
        )
        runCatching { startActivity(marketIntent) }
            .onFailure {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(descriptor.installUrl),
                    ),
                )
            }
    }
}

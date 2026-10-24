package com.polaris.caraudiotrigger.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.polaris.caraudiotrigger.data.DeviceType
import com.polaris.caraudiotrigger.data.DeviceTypeSource
import com.polaris.caraudiotrigger.data.SettingsRepository
import com.polaris.caraudiotrigger.data.TargetDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class PairedBluetoothDevice(
    val name: String,
    val address: String,
    val associationId: Int?,
    val deviceType: DeviceType,
    val deviceTypeSource: DeviceTypeSource,
)

class CompanionDeviceController(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pairedDevices(): List<PairedBluetoothDevice> {
        checkBluetoothPermission()
        val adapter = bluetoothManager.adapter ?: error("当前设备不支持蓝牙")
        if (!adapter.isEnabled) error("请先开启手机蓝牙")

        val associations = manager.myAssociations.associateBy {
            SettingsRepository.normalizeAddress(associationAddress(it))
        }
        return adapter.bondedDevices.map { device ->
            val address = SettingsRepository.normalizeAddress(device.address)
            val displayName = runCatching { device.alias ?: device.name }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: "已配对蓝牙设备"
            val detectedType = BluetoothDeviceTypeResolver.resolve(
                runCatching { device.bluetoothClass?.deviceClass }.getOrNull(),
            )
            PairedBluetoothDevice(
                name = displayName,
                address = address,
                associationId = associations[address]?.id,
                deviceType = detectedType ?: DeviceType.SPEAKER,
                deviceTypeSource = if (detectedType == null) {
                    DeviceTypeSource.DEFAULT_FALLBACK
                } else {
                    DeviceTypeSource.AUTO_DETECTED
                },
            )
        }.sortedWith(
            compareBy<PairedBluetoothDevice> { it.name.lowercase() }.thenBy { it.address },
        )
    }

    suspend fun selectPairedDevice(slot: Int, address: String): String {
        val normalizedAddress = SettingsRepository.normalizeAddress(address)
        val selected = pairedDevices().firstOrNull {
            DeviceIdentity.matches(it.address, normalizedAddress)
        } ?: error("该设备已不在手机的已配对列表中")
        val previousId = settingsRepository.current().device(slot)?.associationId
        val saved = settingsRepository.setTargetDevice(
            slot = slot,
            associationId = selected.associationId,
            name = selected.name,
            address = selected.address,
            deviceType = selected.deviceType,
            deviceTypeSource = selected.deviceTypeSource,
        )
        if (!saved) return "该蓝牙设备已经用于其他播放设备"

        selected.associationId?.let { runCatching { startObserving(it) } }
        cleanupAssociationIfUnused(previousId)
        val message = if (selected.associationId != null) {
            "已选择蓝牙设备：${selected.name}"
        } else {
            "已选择 ${selected.name}；请保持应用不受电池优化限制以提高锁屏触发可靠性"
        }
        settingsRepository.setStatus(message)
        return message
    }

    fun associate(
        slot: Int,
        activity: Activity,
        launchIntentSender: (IntentSender) -> Unit,
        onMessage: (String) -> Unit,
    ) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            onMessage("当前系统不支持配套设备关联")
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()

        manager.associate(
            request,
            activity.mainExecutor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    launchIntentSender(intentSender)
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    acceptAssociation(slot, associationInfo, onMessage)
                }

                override fun onFailure(errorMessage: CharSequence?) {
                    onMessage(errorMessage?.toString() ?: "没有完成设备关联")
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    fun acceptAssociation(
        slot: Int,
        associationInfo: AssociationInfo,
        onMessage: (String) -> Unit,
    ) {
        val bluetoothDevice = associationInfo.associatedDevice?.bluetoothDevice
        val address = associationAddress(associationInfo)
        if (address.isBlank()) {
            onMessage("系统未返回蓝牙地址，请重新选择")
            return
        }
        val displayName = if (hasBluetoothPermission()) {
            runCatching { bluetoothDevice?.alias ?: bluetoothDevice?.name }.getOrNull()
        } else {
            null
        }
            ?.takeIf(String::isNotBlank)
            ?: associationInfo.displayName?.toString()?.takeIf(String::isNotBlank)
            ?: "已关联设备"
        scope.launch {
            val detectedType = BluetoothDeviceTypeResolver.resolve(
                runCatching { bluetoothDevice?.bluetoothClass?.deviceClass }.getOrNull(),
            )
            val previousId = settingsRepository.current().device(slot)?.associationId
            val saved = settingsRepository.setTargetDevice(
                slot = slot,
                associationId = associationInfo.id,
                name = displayName,
                address = address,
                deviceType = detectedType ?: DeviceType.SPEAKER,
                deviceTypeSource = if (detectedType == null) {
                    DeviceTypeSource.DEFAULT_FALLBACK
                } else {
                    DeviceTypeSource.AUTO_DETECTED
                },
            )
            if (!saved) {
                runCatching { manager.disassociate(associationInfo.id) }
                onMessage("该蓝牙设备已经用于其他播放设备")
                return@launch
            }
            runCatching { startObserving(associationInfo.id) }
                .onSuccess {
                    settingsRepository.setStatus("蓝牙设备已关联：$displayName")
                    onMessage("设备关联成功")
                }
                .onFailure {
                    settingsRepository.setStatus("设备已关联，但后台观察启用失败")
                    onMessage("设备已关联，请检查后台权限")
                }
            cleanupAssociationIfUnused(previousId)
        }
    }

    suspend fun removeDevice(slot: Int): String {
        val previousId = settingsRepository.current().device(slot)?.associationId
        settingsRepository.clearTargetDevice(slot)
        cleanupAssociationIfUnused(previousId)
        val message = "已移除蓝牙设备"
        settingsRepository.setStatus(message)
        return message
    }

    fun startObserving(associationId: Int) {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        runCatching { manager.stopObservingDevicePresence(request) }
        manager.startObservingDevicePresence(request)
    }

    suspend fun restoreObservation(): Boolean {
        val ids = settingsRepository.current().targetDevices
            .mapNotNull(TargetDevice::associationId)
            .distinct()
        if (ids.isEmpty()) return false
        return ids.map { id -> runCatching { startObserving(id) }.isSuccess }.all { it }
    }

    suspend fun refreshConfiguredDeviceTypes() {
        val configured = settingsRepository.current().targetDevices
            .filter { it.isConfigured && it.deviceTypeSource != DeviceTypeSource.USER_SELECTED }
        if (configured.isEmpty()) return
        val pairedByAddress = pairedDevices().associateBy {
            SettingsRepository.normalizeAddress(it.address)
        }
        configured.forEach { target ->
            val paired = pairedByAddress[SettingsRepository.normalizeAddress(target.address)]
                ?: return@forEach
            if (target.deviceType != paired.deviceType ||
                target.deviceTypeSource != paired.deviceTypeSource
            ) {
                settingsRepository.setDeviceType(
                    target.slot,
                    paired.deviceType,
                    paired.deviceTypeSource,
                )
            }
        }
    }

    fun activeAssociationCount(devices: List<TargetDevice>): Int {
        val existingIds = manager.myAssociations.map(AssociationInfo::getId).toSet()
        return devices.mapNotNull(TargetDevice::associationId).distinct().count(existingIds::contains)
    }

    private suspend fun cleanupAssociationIfUnused(associationId: Int?) {
        if (associationId == null) return
        val stillUsed = settingsRepository.current().targetDevices.any {
            it.associationId == associationId
        }
        if (!stillUsed) runCatching { manager.disassociate(associationId) }
    }

    private fun associationAddress(info: AssociationInfo): String =
        info.deviceMacAddress?.toString()
            ?: runCatching { info.associatedDevice?.bluetoothDevice?.address }.getOrNull()
            ?: ""

    private fun checkBluetoothPermission() {
        if (!hasBluetoothPermission()) throw SecurityException("未授予附近设备权限")
    }

    private fun hasBluetoothPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

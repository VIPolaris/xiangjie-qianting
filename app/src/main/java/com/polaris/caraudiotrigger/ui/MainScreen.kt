package com.polaris.caraudiotrigger.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polaris.caraudiotrigger.R
import com.polaris.caraudiotrigger.data.AudioSource
import com.polaris.caraudiotrigger.data.ConnectionHistoryEntry
import com.polaris.caraudiotrigger.data.DeviceType
import com.polaris.caraudiotrigger.data.MusicProvider
import com.polaris.caraudiotrigger.data.SettingsRepository
import com.polaris.caraudiotrigger.data.TargetDevice
import com.polaris.caraudiotrigger.playback.MusicProviderRegistry
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

data class MainScreenActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onChooseDevice: (Int) -> Unit,
    val onSelectPairedDevice: (Int, String) -> Unit,
    val onDismissDevicePicker: () -> Unit,
    val onDiscoverNewDevice: (Int) -> Unit,
    val onRemoveDevice: (Int) -> Unit,
    val onDeviceAnnouncementEnabledChange: (Int, Boolean) -> Unit,
    val onDeviceMusicEnabledChange: (Int, Boolean) -> Unit,
    val onDeviceTypeChange: (Int, DeviceType) -> Unit,
    val onChooseAudio: () -> Unit,
    val onAudioSourceChange: (AudioSource) -> Unit,
    val onPlayerVolumeChange: (Int) -> Unit,
    val onSystemVolumeEnabledChange: (Boolean) -> Unit,
    val onSystemVolumeChange: (Int) -> Unit,
    val onMusicProviderChange: (MusicProvider) -> Unit,
    val onMusicRetryUntilDisconnectedChange: (Boolean) -> Unit,
    val onTestPlayback: (Int) -> Unit,
    val onRequestBluetoothPermission: () -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onRequestBatteryUnrestricted: () -> Unit,
    val onOpenMusicAccessSettings: () -> Unit,
    val onOpenMusicProviderStore: (MusicProvider) -> Unit,
    val onOpenAppSettings: () -> Unit,
)

private enum class AppSection(val label: String) {
    HOME("首页"),
    AUDIO("音频"),
    MUSIC("音乐"),
    BACKGROUND("后台"),
}

@Composable
fun MainScreen(
    state: MainUiState,
    actions: MainScreenActions,
) {
    var sectionName by rememberSaveable { mutableStateOf(AppSection.HOME.name) }
    val selectedSection = runCatching { AppSection.valueOf(sectionName) }
        .getOrDefault(AppSection.HOME)
    val homeScroll = rememberScrollState()
    val audioScroll = rememberScrollState()
    val musicScroll = rememberScrollState()
    val backgroundScroll = rememberScrollState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AppBottomNavigation(
                selected = selectedSection,
                onSelect = { sectionName = it.name },
            )
        },
    ) { innerPadding ->
        val pageModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (selectedSection) {
            AppSection.HOME -> HomePage(
                state = state,
                actions = actions,
                modifier = pageModifier.verticalScroll(homeScroll),
            )
            AppSection.AUDIO -> AudioPage(
                state = state,
                actions = actions,
                modifier = pageModifier.verticalScroll(audioScroll),
            )
            AppSection.MUSIC -> MusicPage(
                state = state,
                actions = actions,
                modifier = pageModifier.verticalScroll(musicScroll),
            )
            AppSection.BACKGROUND -> BackgroundPage(
                state = state,
                actions = actions,
                modifier = pageModifier.verticalScroll(backgroundScroll),
            )
        }
    }

    if (state.devicePicker.visible) {
        DevicePickerDialog(state, actions)
    }
}

@Composable
private fun AppBottomNavigation(
    selected: AppSection,
    onSelect: (AppSection) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        AppSection.entries.forEach { section ->
            NavigationBarItem(
                selected = section == selected,
                onClick = { onSelect(section) },
                icon = {
                    Icon(
                        painter = painterResource(sectionIcon(section)),
                        contentDescription = null,
                    )
                },
                label = { Text(section.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            )
        }
    }
}

@DrawableRes
private fun sectionIcon(section: AppSection): Int = when (section) {
    AppSection.HOME -> R.drawable.ic_ui_home
    AppSection.AUDIO -> R.drawable.ic_ui_volume
    AppSection.MUSIC -> R.drawable.ic_ui_music
    AppSection.BACKGROUND -> R.drawable.ic_ui_settings
}

@Composable
private fun HomePage(
    state: MainUiState,
    actions: MainScreenActions,
    modifier: Modifier,
) {
    val settings = state.settings
    val configuredCount = settings.configuredDeviceCount()
    val firstAvailableSlot = settings.targetDevices.firstOrNull { !it.isConfigured }?.slot
    Page(modifier) {
        PageHeader("相接浅听", "连接任一蓝牙播放设备后，按设备配置自动运行")
        StatusHero(
            enabled = settings.enabled,
            deviceName = "已添加 $configuredCount/3 台设备",
            status = settings.lastStatus,
            onEnabledChange = actions.onEnabledChange,
        )

        SectionLabel("播放设备")
        settings.targetDevices.forEach { device ->
            AnimatedVisibility(
                visible = device.isConfigured,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                TargetDeviceCard(
                    device = device,
                    musicProviderName = settings.selectedMusicProvider.displayName,
                    actions = actions,
                )
            }
        }
        if (firstAvailableSlot != null) {
            OutlinedButton(
                onClick = { actions.onChooseDevice(firstAvailableSlot) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painterResource(R.drawable.ic_ui_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(" 增加设备")
            }
        } else {
            Text(
                "已达到 3 台设备上限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        SectionLabel("设备与状态")
        ConnectionHistoryCard(
            latestStatus = buildStatusText(settings.lastStatus, settings.lastStatusAtMillis),
            history = settings.connectionHistory,
        )
        Text(
            "测试会使用该设备自己的音频播报和播放音乐开关，并且不会回退到手机扬声器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun TargetDeviceCard(
    device: TargetDevice,
    musicProviderName: String,
    actions: MainScreenActions,
) {
    var showTypePicker by rememberSaveable(device.slot) { mutableStateOf(false) }
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { showTypePicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(deviceTypeIcon(device.deviceType)),
                    contentDescription = "设备类型：${deviceTypeLabel(device.deviceType)}，点击修改",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    device.name.ifBlank { "已保存蓝牙设备" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()
        SwitchSettingRow(
            title = "音频播报",
            subtitle = "连接此设备时播放所选提示音",
            checked = device.announcementEnabled,
            onCheckedChange = { actions.onDeviceAnnouncementEnabledChange(device.slot, it) },
        )
        SwitchSettingRow(
            title = "播放音乐",
            subtitle = if (device.announcementEnabled) {
                "播报结束后播放 $musicProviderName"
            } else {
                "连接后直接播放 $musicProviderName"
            },
            checked = device.musicEnabled,
            onCheckedChange = { actions.onDeviceMusicEnabledChange(device.slot, it) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { actions.onTestPlayback(device.slot) },
                enabled = device.announcementEnabled || device.musicEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("测试此设备")
            }
            TextButton(onClick = { actions.onChooseDevice(device.slot) }) { Text("更换") }
            TextButton(onClick = { actions.onRemoveDevice(device.slot) }) { Text("移除") }
        }
    }
    if (showTypePicker) {
        DeviceTypeDialog(
            selected = device.deviceType,
            onDismiss = { showTypePicker = false },
            onSelected = {
                actions.onDeviceTypeChange(device.slot, it)
                showTypePicker = false
            },
        )
    }
}

@Composable
private fun DeviceTypeDialog(
    selected: DeviceType,
    onDismiss: () -> Unit,
    onSelected: (DeviceType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择设备类型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DeviceType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(type) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painterResource(deviceTypeIcon(type)),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(deviceTypeLabel(type), modifier = Modifier.weight(1f))
                        RadioButton(selected = selected == type, onClick = { onSelected(type) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConnectionHistoryCard(
    latestStatus: String,
    history: List<ConnectionHistoryEntry>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = flatCardElevation(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_ui_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("最近状态", style = MaterialTheme.typography.titleMedium)
                    Text(
                        latestStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "收起" else "展开")
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider()
                Text(
                    "最近 10 次连接",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (history.isEmpty()) {
                    Text(
                        "暂无连接记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    history.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider()
                        HistoryEntry(entry)
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntry(entry: ConnectionHistoryEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            entry.deviceName.ifBlank { "蓝牙设备" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (entry.deviceAddress.isNotBlank()) {
            Text(
                entry.deviceAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(entry.status, style = MaterialTheme.typography.bodyMedium)
        Text(
            buildString {
                append("连接：")
                append(formatDateTime(entry.connectedAtMillis))
                entry.disconnectedAtMillis?.let {
                    append(" · 断开：")
                    append(formatDateTime(it))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DevicePickerDialog(state: MainUiState, actions: MainScreenActions) {
    val picker = state.devicePicker
    val otherAddresses = state.settings.targetDevices
        .filter { it.slot != picker.slot && it.isConfigured }
        .map { SettingsRepository.normalizeAddress(it.address) }
        .toSet()
    AlertDialog(
        onDismissRequest = actions.onDismissDevicePicker,
        title = { Text("选择蓝牙设备") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "手机中已保存的蓝牙设备",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (picker.loading) {
                    Text("正在读取已配对设备…")
                }
                picker.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                picker.devices.forEach { device ->
                    val usedByOther = SettingsRepository.normalizeAddress(device.address) in otherAddresses
                    Card(
                        onClick = { actions.onSelectPairedDevice(picker.slot, device.address) },
                        enabled = !usedByOther,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        elevation = flatCardElevation(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painterResource(deviceTypeIcon(device.deviceType)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(device.name, fontWeight = FontWeight.Bold)
                                Text(device.address, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    when {
                                        usedByOther -> "已用于其他播放设备"
                                        device.associationId != null -> "已配对 · 已建立后台关联"
                                        else -> "已配对 · 无需重新配对"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onDiscoverNewDevice(picker.slot) }) {
                Text("发现新设备")
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissDevicePicker) { Text("取消") }
        },
    )
}

@Composable
private fun AudioPage(
    state: MainUiState,
    actions: MainScreenActions,
    modifier: Modifier,
) {
    val settings = state.settings
    var playerVolumePreview by remember(settings.playerVolumePercent) {
        mutableIntStateOf(settings.playerVolumePercent)
    }
    var systemVolumePreview by remember(settings.systemVolumePercent) {
        mutableIntStateOf(settings.systemVolumePercent)
    }
    Page(modifier) {
        PageHeader("音频", "配置所有设备共用的播报内容和音量")
        Text(
            "是否播报由首页每台设备的“音频播报”开关决定。连接后会立即等待目标媒体通道，无需固定延时。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionLabel("播放内容")
        SettingsGroup {
            SettingTitle(R.drawable.ic_ui_music, "播报音频")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.audioSource == AudioSource.BUILT_IN,
                    onClick = { actions.onAudioSourceChange(AudioSource.BUILT_IN) },
                    label = { Text("内置提示音") },
                )
                FilterChip(
                    selected = settings.audioSource == AudioSource.CUSTOM,
                    onClick = { actions.onAudioSourceChange(AudioSource.CUSTOM) },
                    enabled = settings.customAudioPath.isNotBlank(),
                    label = { Text("自选音频") },
                )
            }
            Text(
                if (settings.customAudioName.isBlank()) "尚未导入自选音频"
                else "已导入：${settings.customAudioName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = actions.onChooseAudio,
                enabled = !state.isImportingAudio,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isImportingAudio) "正在导入…" else "选择手机中的音频")
            }
        }

        SectionLabel("播放音量")
        SettingsGroup {
            SettingTitle(R.drawable.ic_ui_volume, "本音频 $playerVolumePreview%")
            ValueSlider(
                value = playerVolumePreview,
                range = 0..100,
                onValueChange = { playerVolumePreview = it },
                onCommitted = actions.onPlayerVolumeChange,
                semanticsLabel = "本音频音量",
            )
            HorizontalDivider()
            SwitchSettingRow(
                title = "设置系统媒体音量",
                subtitle = "播完后恢复；播放中手动调整则保留新值",
                checked = settings.systemVolumeEnabled,
                onCheckedChange = actions.onSystemVolumeEnabledChange,
            )
            if (settings.systemVolumeEnabled) {
                Text(
                    "系统媒体 $systemVolumePreview%",
                    style = MaterialTheme.typography.titleMedium,
                )
                ValueSlider(
                    value = systemVolumePreview,
                    range = 0..100,
                    onValueChange = { systemVolumePreview = it },
                    onCommitted = actions.onSystemVolumeChange,
                    semanticsLabel = "系统媒体音量",
                )
            }
        }
    }
}

@Composable
private fun MusicPage(
    state: MainUiState,
    actions: MainScreenActions,
    modifier: Modifier,
) {
    val settings = state.settings
    val enabledCount = settings.targetDevices.count { it.isConfigured && it.musicEnabled }
    Page(modifier) {
        PageHeader("音乐", "选择所有设备共用的音乐平台和恢复方式")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            elevation = flatCardElevation(),
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_ui_music),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "播放音乐",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${settings.selectedMusicProvider.displayName} · 已在 $enabledCount 台设备上启用",
                    )
                }
            }
        }

        SectionLabel("音乐平台")
        SettingsGroup {
            MusicProviderRegistry.providers.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { actions.onMusicProviderChange(provider) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_ui_music),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (settings.selectedMusicProvider == provider) {
                                "当前选择"
                            } else {
                                "点击切换到此平台"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(
                        selected = settings.selectedMusicProvider == provider,
                        onClick = { actions.onMusicProviderChange(provider) },
                    )
                }
            }
            Text(
                "所有设备共用这里选择的平台；首页每台设备只需控制是否播放音乐。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel("恢复方式")
        SettingsGroup {
            SwitchSettingRow(
                title = "持续重试至设备断开",
                subtitle = "关闭后最多重试 60 秒",
                checked = settings.musicRetryUntilDisconnected,
                onCheckedChange = actions.onMusicRetryUntilDisconnectedChange,
            )
            Text(
                "当前平台会依次尝试专属会话、媒体服务、显式启动和系统播放键。可能唤醒或打断其他播放器；应用会继续尝试，直到确认音乐出声。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel("准备情况")
        SettingsGroup {
            ReadinessSettingRow(
                icon = R.drawable.ic_ui_music,
                title = "${settings.selectedMusicProvider.displayName} 已安装",
                ready = state.readiness.selectedMusicProviderInstalled,
                actionLabel = "安装",
                onAction = {
                    actions.onOpenMusicProviderStore(settings.selectedMusicProvider)
                },
            )
            HorizontalDivider()
            ReadinessSettingRow(
                icon = R.drawable.ic_notification,
                title = "${settings.selectedMusicProvider.displayName} 媒体会话控制授权",
                subtitle = "通知使用权；本应用不读取或保存通知内容",
                ready = state.readiness.musicNotificationAccessGranted,
                actionLabel = "授权",
                onAction = actions.onOpenMusicAccessSettings,
            )
        }
    }
}

@Composable
private fun BackgroundPage(
    state: MainUiState,
    actions: MainScreenActions,
    modifier: Modifier,
) {
    val configuredCount = state.settings.configuredDeviceCount()
    val firstAvailableSlot = state.settings.targetDevices.firstOrNull { !it.isConfigured }?.slot ?: 1
    Page(modifier) {
        PageHeader("后台", "检查锁屏自动运行所需的系统设置")
        SectionLabel("必要权限")
        SettingsGroup {
            ReadinessSettingRow(
                icon = R.drawable.ic_ui_settings,
                title = "附近设备权限",
                ready = state.readiness.bluetoothPermissionGranted,
                actionLabel = "授权",
                onAction = actions.onRequestBluetoothPermission,
            )
            HorizontalDivider()
            ReadinessSettingRow(
                icon = R.drawable.ic_notification,
                title = "播放通知权限",
                ready = state.readiness.notificationPermissionGranted,
                actionLabel = "授权",
                onAction = actions.onRequestNotificationPermission,
            )
            HorizontalDivider()
            ReadinessSettingRow(
                icon = R.drawable.ic_ui_car,
                title = "系统配套设备关联",
                subtitle = if (configuredCount > 0) {
                    "已添加 $configuredCount 台播放设备；系统关联可进一步提高锁屏可靠性"
                } else {
                    "增加至少一台蓝牙设备后完成"
                },
                ready = configuredCount > 0,
                actionLabel = "增加",
                onAction = { actions.onChooseDevice(firstAvailableSlot) },
                optional = true,
            )
        }

        SectionLabel("后台行为")
        SettingsGroup {
            ReadinessSettingRow(
                icon = R.drawable.ic_ui_settings,
                title = "Android 标准电池优化白名单",
                subtitle = "已配对直选设备建议开启；不代表系统后台运行权限",
                ready = state.readiness.androidBatteryOptimizationExempt,
                actionLabel = "建议设置",
                onAction = actions.onRequestBatteryUnrestricted,
                optional = true,
            )
            HorizontalDivider()
            SettingRow(
                icon = R.drawable.ic_ui_settings,
                title = "系统后台运行权限",
                subtitle = "系统不允许第三方应用读取全部后台状态；已手动开启且锁屏触发正常时，无需反复设置。",
                onClick = actions.onOpenAppSettings,
            )
        }
        OutlinedButton(
            onClick = actions.onOpenAppSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开应用设置")
        }
    }
}

@Composable
private fun Page(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Text(
        text = title,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun StatusHero(
    enabled: Boolean,
    deviceName: String,
    status: String,
    onEnabledChange: (Boolean) -> Unit,
) {
    val startColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "heroStart",
    )
    val endColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "heroEnd",
    )
    val contentColor = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(startColor, endColor))),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_ui_speaker),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = contentColor,
                )
                Text(
                    " 自动播报",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = Color.White,
                        uncheckedThumbColor = contentColor.copy(alpha = 0.8f),
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedBorderColor = contentColor.copy(alpha = 0.7f),
                    ),
                )
            }
            Text(
                if (enabled) "运行中" else "已暂停",
                fontSize = 36.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(deviceName, style = MaterialTheme.typography.titleMedium, color = contentColor)
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.86f),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = flatCardElevation(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun flatCardElevation() = CardDefaults.cardElevation(
    defaultElevation = 0.dp,
    pressedElevation = 0.dp,
    focusedElevation = 0.dp,
    hoveredElevation = 0.dp,
    draggedElevation = 0.dp,
    disabledElevation = 0.dp,
)

@Composable
private fun SettingTitle(@DrawableRes icon: Int, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) {
            Icon(painterResource(R.drawable.ic_ui_chevron_right), contentDescription = null)
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ReadinessSettingRow(
    @DrawableRes icon: Int,
    title: String,
    ready: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    subtitle: String? = null,
    optional: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (ready) {
            Icon(
                painterResource(R.drawable.ic_ui_check),
                contentDescription = "已就绪",
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onAction) {
                Icon(
                    painterResource(
                        if (optional) R.drawable.ic_ui_settings else R.drawable.ic_ui_warning,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ValueSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    onCommitted: (Int) -> Unit,
    semanticsLabel: String,
) {
    var pendingCommitValue by remember(value) { mutableIntStateOf(value) }
    Slider(
        value = value.toFloat(),
        onValueChange = {
            val roundedValue = it.roundToInt().coerceIn(range)
            pendingCommitValue = roundedValue
            onValueChange(roundedValue)
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = 0,
        onValueChangeFinished = { onCommitted(pendingCommitValue) },
        modifier = Modifier.semantics { contentDescription = semanticsLabel },
    )
}

@DrawableRes
private fun deviceTypeIcon(type: DeviceType): Int = when (type) {
    DeviceType.CAR_AUDIO -> R.drawable.ic_ui_car
    DeviceType.HEADPHONES -> R.drawable.ic_ui_headphones
    DeviceType.SPEAKER -> R.drawable.ic_ui_speaker
}

private fun deviceTypeLabel(type: DeviceType): String = when (type) {
    DeviceType.CAR_AUDIO -> "车载音响"
    DeviceType.HEADPHONES -> "耳机"
    DeviceType.SPEAKER -> "音响"
}

private fun buildStatusText(status: String, atMillis: Long): String {
    if (atMillis <= 0L) return status
    return "$status · ${formatDateTime(atMillis)}"
}

private fun formatDateTime(atMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(atMillis))

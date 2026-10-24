package com.polaris.caraudiotrigger

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polaris.caraudiotrigger.data.AppSettings
import com.polaris.caraudiotrigger.data.ConnectionHistoryEntry
import com.polaris.caraudiotrigger.data.DeviceType
import com.polaris.caraudiotrigger.data.MusicProvider
import com.polaris.caraudiotrigger.data.TargetDevice
import com.polaris.caraudiotrigger.ui.MainScreen
import com.polaris.caraudiotrigger.ui.MainScreenActions
import com.polaris.caraudiotrigger.ui.MainUiState
import com.polaris.caraudiotrigger.ui.ReadinessState
import com.polaris.caraudiotrigger.ui.theme.CarAudioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptySlotsAreHiddenAndSingleAddButtonIsVisible() {
        composeRule.setContent {
            CarAudioTheme {
                MainScreen(MainUiState(), emptyActions())
            }
        }

        composeRule.onNodeWithText("相接浅听").assertIsDisplayed()
        composeRule.onAllNodesWithText("尚未选择").assertCountEquals(0)
        composeRule.onAllNodesWithText("增加设备").assertCountEquals(1)
        composeRule.onNodeWithText("后台").performClick()
        composeRule.onNodeWithText("附近设备权限", substring = true).assertIsDisplayed()
    }

    @Test
    fun bottomNavigationShowsAllFourPagesAndNoTriggerTiming() {
        composeRule.setContent {
            CarAudioTheme(darkTheme = true) {
                MainScreen(MainUiState(), emptyActions())
            }
        }

        composeRule.onNodeWithText("首页").assertIsDisplayed()
        composeRule.onNodeWithText("音频").performClick()
        composeRule.onNodeWithText("播放内容").assertIsDisplayed()
        composeRule.onAllNodesWithText("触发时机").assertCountEquals(0)
        composeRule.onNodeWithText("音乐").performClick()
        composeRule.onNodeWithText("播放音乐").assertIsDisplayed()
        composeRule.onNodeWithText("Apple Music").assertIsDisplayed()
        composeRule.onNodeWithText("后台").performClick()
        composeRule.onNodeWithText("必要权限").assertIsDisplayed()
    }

    @Test
    fun configuredDeviceShowsFullAddressAndIndependentSwitches() {
        composeRule.setContent {
            CarAudioTheme {
                MainScreen(
                    MainUiState(
                        settings = AppSettings(
                            targetDevices = listOf(
                                TargetDevice(
                                    slot = 1,
                                    associationId = 1,
                                    name = "Honda HFT",
                                    address = "AA:BB:CC:DF:95:CC",
                                    announcementEnabled = false,
                                    musicEnabled = true,
                                    deviceType = DeviceType.CAR_AUDIO,
                                ),
                                TargetDevice(slot = 2),
                                TargetDevice(slot = 3),
                            ),
                        ),
                    ),
                    emptyActions(),
                )
            }
        }

        composeRule.onNodeWithText("AA:BB:CC:DF:95:CC").assertIsDisplayed()
        composeRule.onNodeWithText("音频播报").assertIsDisplayed()
        composeRule.onNodeWithText("播放音乐").assertIsDisplayed()
        composeRule.onNodeWithText("测试此设备").assertIsDisplayed()
        composeRule.onAllNodesWithText("手动选择", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("自动识别", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("默认类型", substring = true).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("设备类型：车载音响，点击修改").performClick()
        composeRule.onNodeWithText("选择设备类型").assertIsDisplayed()
        composeRule.onNodeWithText("耳机").assertIsDisplayed()
    }

    @Test
    fun audioVolumeSlidersUseContinuousRanges() {
        composeRule.setContent {
            CarAudioTheme {
                MainScreen(
                    MainUiState(
                        settings = AppSettings(
                            playerVolumePercent = 73,
                            systemVolumeEnabled = true,
                            systemVolumePercent = 42,
                        ),
                    ),
                    emptyActions(),
                )
            }
        }

        composeRule.onNodeWithText("音频").performClick()
        composeRule.onNodeWithContentDescription("本音频音量")
            .assertRangeInfoEquals(ProgressBarRangeInfo(73f, 0f..100f, 0))
        composeRule.onNodeWithContentDescription("系统媒体音量")
            .assertRangeInfoEquals(ProgressBarRangeInfo(42f, 0f..100f, 0))
    }

    @Test
    fun audioVolumeNumberUpdatesBeforeCommitAndCommitsOnlyOnGestureEnd() {
        val committedValues = mutableListOf<Int>()
        composeRule.setContent {
            CarAudioTheme {
                MainScreen(
                    MainUiState(settings = AppSettings(playerVolumePercent = 73)),
                    emptyActions(onPlayerVolumeChange = committedValues::add),
                )
            }
        }

        composeRule.onNodeWithText("音频").performClick()
        composeRule.onNodeWithContentDescription("本音频音量")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(36f)
            }
        composeRule.onNodeWithText("本音频 36%").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(committedValues.singleOrNull()) }

        composeRule.onNodeWithContentDescription("本音频音量")
            .performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(1, committedValues.size) }
    }

    @Test
    fun recentStatusExpandsToConnectionHistory() {
        composeRule.setContent {
            CarAudioTheme {
                MainScreen(
                    MainUiState(
                        settings = AppSettings(
                            connectionHistory = listOf(
                                ConnectionHistoryEntry(
                                    id = 1,
                                    deviceName = "Honda HFT",
                                    deviceAddress = "AA:BB:CC:DF:95:CC",
                                    connectedAtMillis = 1_000,
                                    updatedAtMillis = 2_000,
                                    status = "播报成功；Apple Music 已确认播放",
                                ),
                            ),
                        ),
                    ),
                    emptyActions(),
                )
            }
        }

        composeRule.onNodeWithText("最近状态").performClick()
        composeRule.onNodeWithText("最近 10 次连接").assertIsDisplayed()
        composeRule.onNodeWithText("播报成功；Apple Music 已确认播放").assertIsDisplayed()
    }

    @Test
    fun musicPageShowsSharedForcedRecoverySettings() {
        composeRule.setContent {
            CarAudioTheme {
                MainScreen(MainUiState(), emptyActions())
            }
        }

        composeRule.onNodeWithText("音乐").performClick()
        composeRule.onNodeWithText("持续重试至设备断开").assertIsDisplayed()
        composeRule.onNodeWithText("系统播放键", substring = true).assertIsDisplayed()
        listOf("Apple Music", "Spotify", "QQ音乐", "网易云音乐", "酷狗音乐")
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("所有设备共用这里选择的平台", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun selectedProviderDrivesReadinessAndInstallAction() {
        var requestedProvider: MusicProvider? = null
        composeRule.setContent {
            CarAudioTheme {
                MainScreen(
                    MainUiState(
                        settings = AppSettings(
                            selectedMusicProvider = MusicProvider.NETEASE_CLOUD_MUSIC,
                        ),
                        readiness = ReadinessState(
                            selectedMusicProviderInstalled = false,
                            musicNotificationAccessGranted = true,
                        ),
                    ),
                    emptyActions(onOpenMusicProviderStore = { requestedProvider = it }),
                )
            }
        }

        composeRule.onNodeWithText("音乐").performClick()
        composeRule.onNodeWithText("网易云音乐 已安装").assertIsDisplayed()
        composeRule.onNodeWithText("安装").performClick()
        composeRule.runOnIdle {
            assertEquals(MusicProvider.NETEASE_CLOUD_MUSIC, requestedProvider)
        }
    }

    @Test
    fun oneConfiguredDeviceCompletesCompanionSetupStatusWithGenericSystemWording() {
        composeRule.setContent {
            CarAudioTheme {
                MainScreen(
                    MainUiState(
                        settings = AppSettings(
                            targetDevices = listOf(
                                TargetDevice(
                                    slot = 1,
                                    name = "Living room speaker",
                                    address = "10:20:30:40:50:60",
                                ),
                                TargetDevice(slot = 2),
                                TargetDevice(slot = 3),
                            ),
                        ),
                    ),
                    emptyActions(),
                )
            }
        }

        composeRule.onNodeWithText("后台").performClick()
        composeRule.onNodeWithText("系统配套设备关联").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("已就绪").assertIsDisplayed()
        composeRule.onNodeWithText("系统后台运行权限").assertIsDisplayed()
    }

    private fun emptyActions(
        onPlayerVolumeChange: (Int) -> Unit = {},
        onOpenMusicProviderStore: (MusicProvider) -> Unit = {},
    ) = MainScreenActions(
        onEnabledChange = {},
        onChooseDevice = {},
        onSelectPairedDevice = { _, _ -> },
        onDismissDevicePicker = {},
        onDiscoverNewDevice = {},
        onRemoveDevice = {},
        onDeviceAnnouncementEnabledChange = { _, _ -> },
        onDeviceMusicEnabledChange = { _, _ -> },
        onDeviceTypeChange = { _, _ -> },
        onChooseAudio = {},
        onAudioSourceChange = {},
        onPlayerVolumeChange = onPlayerVolumeChange,
        onSystemVolumeEnabledChange = {},
        onSystemVolumeChange = {},
        onMusicProviderChange = {},
        onMusicRetryUntilDisconnectedChange = {},
        onTestPlayback = {},
        onRequestBluetoothPermission = {},
        onRequestNotificationPermission = {},
        onRequestBatteryUnrestricted = {},
        onOpenMusicAccessSettings = {},
        onOpenMusicProviderStore = onOpenMusicProviderStore,
        onOpenAppSettings = {},
    )
}

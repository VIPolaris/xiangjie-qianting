package com.polaris.caraudiotrigger.device

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import com.polaris.caraudiotrigger.data.AppSettings
import com.polaris.caraudiotrigger.data.AudioFileStore
import com.polaris.caraudiotrigger.data.SettingsRepository
import com.polaris.caraudiotrigger.data.TargetDevice
import com.polaris.caraudiotrigger.playback.PlaybackRequest
import com.polaris.caraudiotrigger.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConnectionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val audioFileStore: AudioFileStore,
    private val routeResolver: A2dpRouteResolver,
) {
    private val mutex = Mutex()
    private val playbackHandoffMutex = Mutex()
    private val sessionRegistry = MultiDeviceSessionRegistry()
    private val pendingJobs = mutableMapOf<String, Job>()
    private var playbackClaim = 0L

    fun onCompanionConnected(associationId: Int): Job =
        scope.launch { handleAutomaticConnect(associationId = associationId, address = null) }

    fun onAddressConnected(address: String): Job =
        scope.launch { handleAutomaticConnect(associationId = null, address = address) }

    fun onCompanionDisconnected(associationId: Int): Job = scope.launch {
        handleDisconnect(associationId = associationId, address = null, clearSession = true)
    }

    fun onAddressDisconnected(address: String, clearSession: Boolean): Job = scope.launch {
        handleDisconnect(associationId = null, address = address, clearSession = clearSession)
    }

    fun requestTest(slot: Int) {
        scope.launch {
            val settings = settingsRepository.current()
            val device = settings.device(slot)
            if (device == null || !device.isConfigured) {
                settingsRepository.setStatus("请先增加蓝牙设备")
                return@launch
            }
            if (!DevicePlaybackPolicy.resolve(
                    device.announcementEnabled,
                    device.musicEnabled,
                ).shouldRun
            ) {
                settingsRepository.setStatus("该设备的音频播报和播放音乐均已关闭")
                return@launch
            }
            val key = deviceKey(device.address)
            val job = scope.launch { runTrigger(device, historyId = null, isTest = true) }
            registerPendingJob(key, job)
        }
    }

    private suspend fun handleAutomaticConnect(associationId: Int?, address: String?) {
        val settings = settingsRepository.current()
        val matched = findDevice(settings, associationId, address) ?: return
        val key = deviceKey(matched.address)

        val triggerJob = mutex.withLock {
            val latestSettings = settingsRepository.current()
            val latestDevice = findDevice(latestSettings, associationId, address) ?: return@withLock null
            if (!sessionRegistry.onConnected(latestDevice)) return@withLock null

            settingsRepository.markSessionAttempted(latestDevice.slot)
            val historyId = settingsRepository.beginConnectionSession(latestDevice)
            if (!latestSettings.enabled) {
                settingsRepository.setStatus(
                    "${deviceLabel(latestDevice)} 已连接；自动播放当前已关闭",
                    historyId = historyId,
                )
                return@withLock null
            }
            if (!DevicePlaybackPolicy.resolve(
                    latestDevice.announcementEnabled,
                    latestDevice.musicEnabled,
                ).shouldRun
            ) {
                settingsRepository.setStatus(
                    "${deviceLabel(latestDevice)} 已连接；该设备的音频播报和播放音乐均已关闭",
                    historyId = historyId,
                )
                return@withLock null
            }

            pendingJobs.remove(key)?.cancel()
            scope.launch {
                runTrigger(latestDevice, historyId = historyId, isTest = false)
            }.also { pendingJobs[key] = it }
        }
        try {
            triggerJob?.join()
        } catch (error: CancellationException) {
            triggerJob?.cancel()
            throw error
        } finally {
            if (triggerJob != null) {
                mutex.withLock {
                    if (pendingJobs[key] == triggerJob) pendingJobs.remove(key)
                }
            }
        }
    }

    private suspend fun handleDisconnect(
        associationId: Int?,
        address: String?,
        clearSession: Boolean,
    ) {
        val settings = settingsRepository.current()
        val device = findDevice(settings, associationId, address) ?: return
        val key = deviceKey(device.address)
        mutex.withLock {
            pendingJobs.remove(key)?.cancel()
            if (sessionRegistry.releaseActive(device.address)) {
                playbackClaim += 1L
                if (PlaybackService.isActive()) {
                    PlaybackService.requestStop(
                        context,
                        if (clearSession) "${deviceLabel(device)} 已断开，播放已停止"
                        else "${deviceLabel(device)} 媒体通道已断开，播放已停止",
                    )
                }
            }

            if (clearSession) {
                sessionRegistry.resetSession(device.address)
                settingsRepository.endConnectionSession(device.slot)
                settingsRepository.setStatus("${deviceLabel(device)} 已断开，等待下次连接")
            } else {
                settingsRepository.setStatus(
                    "${deviceLabel(device)} 媒体通道已断开",
                    historyId = device.activeHistoryId,
                )
            }
        }
    }

    private suspend fun runTrigger(
        initialDevice: TargetDevice,
        historyId: Long?,
        isTest: Boolean,
    ) {
        try {
            settingsRepository.setStatus(
                "正在等待 ${deviceLabel(initialDevice)} 的媒体音频通道",
                historyId = historyId,
            )
            val audioDevice = routeResolver.awaitTarget(initialDevice.address)
            if (audioDevice == null) {
                settingsRepository.setStatus(
                    "20 秒内未找到 ${deviceLabel(initialDevice)} 的媒体通道，本次已取消",
                    historyId = historyId,
                )
                return
            }

            val settings = settingsRepository.current()
            val device = settings.device(initialDevice.slot)
            if (device == null || !sameConfiguredDevice(initialDevice, device)) {
                settingsRepository.setStatus("蓝牙设备已更换，本次播放已取消", historyId = historyId)
                return
            }
            if (!isTest && (!device.sessionConnected || !settings.enabled)) {
                settingsRepository.setStatus(
                    if (!settings.enabled) "自动播放已关闭，本次已取消"
                    else "设备已经断开，本次播放已取消",
                    historyId = historyId,
                )
                return
            }
            val playbackMode = DevicePlaybackPolicy.resolve(
                device.announcementEnabled,
                device.musicEnabled,
            )
            if (!playbackMode.shouldRun) {
                settingsRepository.setStatus(
                    "${deviceLabel(device)} 的音频播报和播放音乐均已关闭",
                    historyId = historyId,
                )
                return
            }

            val audioFile = if (playbackMode.playAnnouncement) audioFileStore.resolve(settings) else null
            val fallbackAudio = if (playbackMode.playAnnouncement) {
                audioFileStore.ensureBuiltInChime()
            } else {
                null
            }
            val request = PlaybackRequest(
                targetSlot = device.slot,
                historyId = historyId,
                playAnnouncement = playbackMode.playAnnouncement,
                audioPath = audioFile?.absolutePath.orEmpty(),
                fallbackAudioPath = fallbackAudio?.absolutePath.orEmpty(),
                targetAddress = device.address,
                preferredAudioDeviceId = audioDevice.id,
                playerVolumePercent = settings.playerVolumePercent,
                systemVolumeEnabled = settings.systemVolumeEnabled,
                systemVolumePercent = settings.systemVolumePercent,
                testPlayback = isTest,
                playMusic = playbackMode.playMusic,
                musicProvider = settings.selectedMusicProvider,
                musicRetryUntilDisconnected = settings.musicRetryUntilDisconnected,
            )

            playbackHandoffMutex.withLock {
                val claim = claimPlayback(device)
                if (!awaitPreviousPlaybackStop(claim)) {
                    settingsRepository.setStatus("正在切换播放设备，请稍后重试", historyId = historyId)
                    return@withLock
                }
                val stillClaimed = mutex.withLock {
                    playbackClaim == claim &&
                        sessionRegistry.isActive(device.address)
                }
                if (!stillClaimed) return@withLock

                settingsRepository.setStatus(
                    when {
                        !device.announcementEnabled ->
                            "正在通过 ${deviceLabel(device)} 播放${settings.selectedMusicProvider.displayName}"
                        isTest -> "正在测试 ${deviceLabel(device)} 播放"
                        else -> "正在通过 ${deviceLabel(device)} 播报"
                    },
                    historyId = historyId,
                )
                try {
                    PlaybackService.start(context, request)
                    awaitPlaybackServiceStart(claim)
                } catch (_: ForegroundServiceStartNotAllowedException) {
                    settingsRepository.setStatus(
                        "系统阻止后台启动；请将应用设为不受电池优化限制后重试",
                        historyId = historyId,
                    )
                } catch (error: SecurityException) {
                    settingsRepository.setStatus(
                        "播放服务启动受限：${error.localizedMessage ?: "请检查后台权限"}",
                        historyId = historyId,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            settingsRepository.setStatus(
                "播放启动失败：${error.localizedMessage ?: "未知错误"}",
                historyId = historyId,
            )
        }
    }

    private suspend fun claimPlayback(device: TargetDevice): Long = mutex.withLock {
        playbackClaim += 1L
        val claim = playbackClaim
        if (PlaybackService.isActive()) {
            PlaybackService.requestStop(context, "活动媒体路由已切换到 ${deviceLabel(device)}")
        }
        sessionRegistry.claimActive(device.address)
        claim
    }

    private suspend fun awaitPreviousPlaybackStop(claim: Long): Boolean {
        repeat(PLAYBACK_STOP_POLLS) {
            val stillClaimed = mutex.withLock { playbackClaim == claim }
            if (!stillClaimed) return false
            if (!PlaybackService.isActive()) return true
            delay(PLAYBACK_STOP_POLL_MILLIS)
        }
        return !PlaybackService.isActive()
    }

    private suspend fun awaitPlaybackServiceStart(claim: Long) {
        repeat(PLAYBACK_START_POLLS) {
            if (PlaybackService.isActive()) return
            if (mutex.withLock { playbackClaim != claim }) return
            delay(PLAYBACK_START_POLL_MILLIS)
        }
    }

    private suspend fun registerPendingJob(key: String, job: Job) {
        mutex.withLock {
            pendingJobs.remove(key)?.cancel()
            pendingJobs[key] = job
        }
        job.invokeOnCompletion {
            scope.launch {
                mutex.withLock {
                    if (pendingJobs[key] == job) pendingJobs.remove(key)
                }
            }
        }
    }

    private fun findDevice(
        settings: AppSettings,
        associationId: Int?,
        address: String?,
    ): TargetDevice? = settings.targetDevices.firstOrNull { device ->
        device.isConfigured && when {
            associationId != null -> device.associationId == associationId
            address != null -> DeviceIdentity.matches(device.address, address)
            else -> false
        }
    }

    private fun sameConfiguredDevice(first: TargetDevice, second: TargetDevice): Boolean =
        first.slot == second.slot && DeviceIdentity.matches(first.address, second.address)

    private fun deviceKey(address: String): String = SettingsRepository.normalizeAddress(address)

    private fun deviceLabel(device: TargetDevice): String =
        device.name.ifBlank { "蓝牙设备" }

    companion object {
        private const val PLAYBACK_STOP_POLL_MILLIS = 100L
        private const val PLAYBACK_STOP_POLLS = 30
        private const val PLAYBACK_START_POLL_MILLIS = 50L
        private const val PLAYBACK_START_POLLS = 20
    }
}

package com.polaris.caraudiotrigger.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.polaris.caraudiotrigger.CarAudioApplication
import com.polaris.caraudiotrigger.MainActivity
import com.polaris.caraudiotrigger.R
import com.polaris.caraudiotrigger.data.MusicProvider
import com.polaris.caraudiotrigger.device.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackService : Service(), Player.Listener {
    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var focusRequest: AudioFocusRequest? = null
    private var volumeGuard: SystemVolumeGuard? = null
    private var request: PlaybackRequest? = null
    private var fallbackAttempted = false
    private var finishing = false
    private var basePlayerVolume = 0.8f
    private var ducking = false
    private var warmupActive = false
    private var warmupScheduled = false
    private var playerReleased = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var postPlaybackJob: Job? = null
    private var recoveryWakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finishWarmup = Runnable {
        warmupScheduled = false
        if (!warmupActive || finishing) return@Runnable

        // Keep the same decoder and AudioTrack alive while rewinding. This lets slow car A2DP
        // paths wake up during the muted pass without clipping the beginning of the real pass.
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.seekTo(0L)
        warmupActive = false
        applyPlayerVolume()
        player.play()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
            .build()
        player = ExoPlayer.Builder(this)
            .build()
            .apply {
                setAudioAttributes(attributes, false)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(this@PlaybackService)
            }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> handlePlay(intent.toPlaybackRequest())
            ACTION_STOP -> finishPlayback(
                intent.getStringExtra(EXTRA_STOP_REASON) ?: "播放已手动停止",
            )
        }
        return START_NOT_STICKY
    }

    private fun handlePlay(playbackRequest: PlaybackRequest) {
        if (finishing) return
        if (player.mediaItemCount > 0) {
            updateStatus("当前已有音频正在播放，请稍后重试")
            return
        }
        request = playbackRequest
        fallbackAttempted = false
        if (playbackRequest.playAnnouncement) {
            startAsForeground("正在准备蓝牙音频")
        } else {
            startRecoveryForeground(
                "正在强制启动${playbackRequest.musicProvider.displayName}",
            )
        }

        val preferredDevice = findPreferredDevice(playbackRequest)
        if (preferredDevice == null) {
            finishPlayback("蓝牙设备媒体通道已不可用，本次未播放")
            return
        }
        if (!playbackRequest.playAnnouncement) {
            startMusicOnlyRecovery(playbackRequest)
            return
        }
        var source = File(playbackRequest.audioPath)
        if (!source.isFile || source.length() == 0L) {
            val fallback = File(playbackRequest.fallbackAudioPath)
            if (!fallback.isFile || fallback.length() == 0L) {
                finishPlayback("所选音频和内置提示音均不可用，本次已取消")
                return
            }
            fallbackAttempted = true
            source = fallback
            updateStatus("自选音频不可用，正在播放内置提示音")
        }

        if (!requestAudioFocus()) {
            finishPlayback("未取得音频焦点，本次未播放")
            return
        }
        volumeGuard = runCatching {
            SystemVolumeGuard.applyIfEnabled(
                audioManager = audioManager,
                enabled = playbackRequest.systemVolumeEnabled,
                targetPercent = playbackRequest.systemVolumePercent,
            )
        }.getOrNull()

        basePlayerVolume = VolumeMath.percentToPlayerGain(playbackRequest.playerVolumePercent)
        player.setPreferredAudioDevice(preferredDevice)
        playFile(source)
    }

    private fun playFile(file: File) {
        cancelWarmup()
        warmupActive = true
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ONE
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("相接浅听")
                    .setArtist("蓝牙设备自动播放")
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && warmupActive && !warmupScheduled) {
            warmupScheduled = true
            mainHandler.postDelayed(finishWarmup, AUDIO_PATH_WARMUP_MILLIS)
        }
    }

    private fun applyPlayerVolume() {
        player.volume = when {
            warmupActive -> 0f
            ducking -> basePlayerVolume * 0.2f
            else -> basePlayerVolume
        }
    }

    private fun cancelWarmup() {
        mainHandler.removeCallbacks(finishWarmup)
        warmupActive = false
        warmupScheduled = false
        if (::player.isInitialized && !playerReleased) {
            player.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    private fun playFallbackOrStop(reason: String) {
        val fallbackPath = request?.fallbackAudioPath.orEmpty()
        val fallback = File(fallbackPath)
        if (PlaybackFallbackPolicy.shouldAttemptFallback(
                fallbackAlreadyAttempted = fallbackAttempted,
                primaryPath = request?.audioPath.orEmpty(),
                fallbackPath = fallback.absolutePath,
                fallbackIsUsable = fallback.isFile && fallback.length() > 0L,
            )
        ) {
            fallbackAttempted = true
            updateStatus("自选音频不可用，正在播放内置提示音")
            playFile(fallback)
        } else {
            finishPlayback("$reason，本次已取消")
        }
    }

    private fun findPreferredDevice(playbackRequest: PlaybackRequest): AudioDeviceInfo? {
        val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        return candidates.firstOrNull {
            it.id == playbackRequest.preferredAudioDeviceId &&
                (DeviceIdentity.matches(playbackRequest.targetAddress, it.address) || it.address.isBlank())
        } ?: candidates.firstOrNull {
            DeviceIdentity.matches(playbackRequest.targetAddress, it.address)
        } ?: candidates.singleOrNull()?.takeIf { it.address.isBlank() }
    }

    private fun requestAudioFocus(): Boolean {
        val platformAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val newRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(platformAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change -> onAudioFocusChanged(change) }
            .build()
        focusRequest = newRequest
        return audioManager.requestAudioFocus(newRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun onAudioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                ducking = false
                applyPlayerVolume()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducking = true
                applyPlayerVolume()
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> finishPlayback("音频焦点被其他应用占用，播放已停止")
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && !warmupActive) {
            completePlayback()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        playFallbackOrStop("音频解码失败")
    }

    private fun completePlayback() {
        val playbackRequest = request ?: run {
            finishPlayback("播放成功")
            return
        }
        val shouldResumeMusic = AppleMusicResumePolicy.shouldResume(
            enabled = playbackRequest.playMusic,
            naturalCompletion = true,
        )
        if (!shouldResumeMusic) {
            finishPlayback("播放成功")
            return
        }
        if (finishing) return

        finishing = true
        cleanupOwnPlayback()
        releaseOwnPlayer()
        startRecoveryForeground("正在启动${playbackRequest.musicProvider.displayName}")
        ensureRecoveryWakeLock()
        postPlaybackJob = serviceScope.launch {
            delay(MUSIC_HANDOFF_DELAY_MILLIS)
            awaitOwnAudioTrackExit()
            runMusicRecovery(playbackRequest)
        }
    }

    private fun startMusicOnlyRecovery(playbackRequest: PlaybackRequest) {
        if (finishing) return
        finishing = true
        releaseOwnPlayer()
        ensureRecoveryWakeLock()
        postPlaybackJob = serviceScope.launch {
            runMusicRecovery(playbackRequest)
        }
    }

    private suspend fun runMusicRecovery(playbackRequest: PlaybackRequest) {
        val provider = playbackRequest.musicProvider
        val providerName = provider.displayName
        val controller = MusicProviderRegistry.create(this, provider)
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = AppleMusicRetryPolicy.deadline(
            startedAtMillis = startedAt,
            retryUntilDisconnected = playbackRequest.musicRetryUntilDisconnected,
        )
        var attempt = 0

        while (currentCoroutineContext().isActive) {
            if (findPreferredDevice(playbackRequest) == null) {
                finishPostPlayback(successPrefix(playbackRequest) + "设备媒体通道已断开，已停止播放$providerName")
                return
            }

            val remainingMillis = AppleMusicRetryPolicy.remainingMillis(
                deadlineMillis = deadline,
                nowMillis = SystemClock.elapsedRealtime(),
            )
            if (remainingMillis <= 0L) {
                finishPostPlayback(successPrefix(playbackRequest) + "已尝试 60 秒，$providerName 仍未确认出声")
                return
            }

            attempt += 1
            ensureRecoveryWakeLock()
            val elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
            startRecoveryForeground(
                "正在启动$providerName（第 ${attempt} 次，${elapsedSeconds} 秒）",
            )

            val result = if (deadline == Long.MAX_VALUE) {
                controller.resume()
            } else {
                withTimeoutOrNull(remainingMillis) { controller.resume() }
            }

            if (result == null) {
                finishPostPlayback(successPrefix(playbackRequest) + "已尝试 60 秒，$providerName 仍未确认出声")
                return
            }
            if (result.confirmed) {
                if (findPreferredDevice(playbackRequest) == null) {
                    finishPostPlayback(successPrefix(playbackRequest) + "设备媒体通道已断开，已停止播放$providerName")
                    return
                }
                finishPostPlayback(successPrefix(playbackRequest) + result.statusMessage)
                return
            }
            if (result.terminal) {
                finishPostPlayback(successPrefix(playbackRequest) + result.statusMessage)
                return
            }

            updateStatus(
                successPrefix(playbackRequest) +
                    "${result.statusMessage}，正在继续强制重试（第 ${attempt} 次）",
            )
            val retryDelay = if (deadline == Long.MAX_VALUE) {
                MUSIC_RETRY_INTERVAL_MILLIS
            } else {
                minOf(
                    MUSIC_RETRY_INTERVAL_MILLIS,
                    deadline - SystemClock.elapsedRealtime(),
                )
            }
            if (retryDelay > 0L) delay(retryDelay)
        }
    }

    private fun finishPlayback(status: String) {
        if (finishing) {
            postPlaybackJob?.cancel()
            postPlaybackJob = null
            releaseRecoveryWakeLock()
            updateStatus(status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        finishing = true
        cleanupOwnPlayback()
        releaseRecoveryWakeLock()
        updateStatus(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupOwnPlayback() {
        cancelWarmup()
        player.stop()
        player.setPreferredAudioDevice(null)
        player.clearMediaItems()
        volumeGuard?.restoreIfUnchanged()
        volumeGuard = null
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun finishPostPlayback(status: String) {
        postPlaybackJob = null
        releaseRecoveryWakeLock()
        updateStatus(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseOwnPlayer() {
        if (playerReleased) return
        playerReleased = true
        player.removeListener(this)
        player.release()
    }

    private suspend fun awaitOwnAudioTrackExit() {
        repeat(OWN_AUDIO_RELEASE_POLLS) {
            if (!audioManager.isMusicActive) return
            delay(OWN_AUDIO_RELEASE_POLL_MILLIS)
        }
    }

    private fun startAsForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun startRecoveryForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun ensureRecoveryWakeLock() {
        val lock = recoveryWakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:music-recovery")
            .apply { setReferenceCounted(false) }
            .also { recoveryWakeLock = it }
        if (!lock.isHeld) lock.acquire(RECOVERY_WAKE_LOCK_TIMEOUT_MILLIS)
    }

    private fun releaseRecoveryWakeLock() {
        recoveryWakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        recoveryWakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_STOP_REASON, "播放已手动停止"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setSound(null, null)
            },
        )
    }

    private fun updateStatus(message: String) {
        val application = application as CarAudioApplication
        val historyId = request?.historyId
        application.applicationScope.launch {
            application.settingsRepository.setStatus(message, historyId = historyId)
        }
    }

    private fun successPrefix(playbackRequest: PlaybackRequest): String =
        if (playbackRequest.playAnnouncement) "播报成功；" else ""

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        cancelWarmup()
        volumeGuard?.restoreIfUnchanged()
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        releaseRecoveryWakeLock()
        releaseOwnPlayer()
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun Intent.toPlaybackRequest() = PlaybackRequest(
        targetSlot = getIntExtra(EXTRA_TARGET_SLOT, 1),
        historyId = getLongExtra(EXTRA_HISTORY_ID, NO_HISTORY_ID)
            .takeUnless { it == NO_HISTORY_ID },
        playAnnouncement = getBooleanExtra(EXTRA_PLAY_ANNOUNCEMENT, true),
        audioPath = getStringExtra(EXTRA_AUDIO_PATH).orEmpty(),
        fallbackAudioPath = getStringExtra(EXTRA_FALLBACK_AUDIO_PATH).orEmpty(),
        targetAddress = getStringExtra(EXTRA_TARGET_ADDRESS).orEmpty(),
        preferredAudioDeviceId = getIntExtra(EXTRA_AUDIO_DEVICE_ID, -1),
        playerVolumePercent = getIntExtra(EXTRA_PLAYER_VOLUME, 80),
        systemVolumeEnabled = getBooleanExtra(EXTRA_SYSTEM_VOLUME_ENABLED, false),
        systemVolumePercent = getIntExtra(EXTRA_SYSTEM_VOLUME, 50),
        testPlayback = getBooleanExtra(EXTRA_TEST_PLAYBACK, false),
        playMusic = getBooleanExtra(EXTRA_PLAY_MUSIC, false),
        musicProvider = runCatching {
            MusicProvider.valueOf(
                getStringExtra(EXTRA_MUSIC_PROVIDER) ?: MusicProvider.APPLE_MUSIC.name,
            )
        }.getOrDefault(MusicProvider.APPLE_MUSIC),
        musicRetryUntilDisconnected =
            getBooleanExtra(EXTRA_MUSIC_RETRY_UNTIL_DISCONNECTED, true),
    )

    companion object {
        private const val ACTION_PLAY = "com.polaris.caraudiotrigger.action.PLAY"
        private const val ACTION_STOP = "com.polaris.caraudiotrigger.action.STOP"
        private const val EXTRA_TARGET_SLOT = "target_slot"
        private const val EXTRA_HISTORY_ID = "history_id"
        private const val EXTRA_PLAY_ANNOUNCEMENT = "play_announcement"
        private const val EXTRA_AUDIO_PATH = "audio_path"
        private const val EXTRA_FALLBACK_AUDIO_PATH = "fallback_audio_path"
        private const val EXTRA_TARGET_ADDRESS = "target_address"
        private const val EXTRA_AUDIO_DEVICE_ID = "audio_device_id"
        private const val EXTRA_PLAYER_VOLUME = "player_volume"
        private const val EXTRA_SYSTEM_VOLUME_ENABLED = "system_volume_enabled"
        private const val EXTRA_SYSTEM_VOLUME = "system_volume"
        private const val EXTRA_TEST_PLAYBACK = "test_playback"
        private const val EXTRA_PLAY_MUSIC = "play_music"
        private const val EXTRA_MUSIC_PROVIDER = "music_provider"
        private const val EXTRA_MUSIC_RETRY_UNTIL_DISCONNECTED =
            "music_retry_until_disconnected"
        private const val EXTRA_STOP_REASON = "stop_reason"
        private const val NO_HISTORY_ID = -1L
        private const val NOTIFICATION_CHANNEL_ID = "car_audio_playback"
        private const val NOTIFICATION_ID = 4107
        private const val AUDIO_PATH_WARMUP_MILLIS = 1_000L
        private const val MUSIC_HANDOFF_DELAY_MILLIS = 300L
        private const val OWN_AUDIO_RELEASE_POLL_MILLIS = 100L
        private const val OWN_AUDIO_RELEASE_POLLS = 5
        private const val MUSIC_RETRY_INTERVAL_MILLIS = 1_500L
        private const val RECOVERY_WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L

        @Volatile
        private var isRunning = false

        fun start(context: Context, request: PlaybackRequest) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_TARGET_SLOT, request.targetSlot)
                .putExtra(EXTRA_HISTORY_ID, request.historyId ?: NO_HISTORY_ID)
                .putExtra(EXTRA_PLAY_ANNOUNCEMENT, request.playAnnouncement)
                .putExtra(EXTRA_AUDIO_PATH, request.audioPath)
                .putExtra(EXTRA_FALLBACK_AUDIO_PATH, request.fallbackAudioPath)
                .putExtra(EXTRA_TARGET_ADDRESS, request.targetAddress)
                .putExtra(EXTRA_AUDIO_DEVICE_ID, request.preferredAudioDeviceId)
                .putExtra(EXTRA_PLAYER_VOLUME, request.playerVolumePercent)
                .putExtra(EXTRA_SYSTEM_VOLUME_ENABLED, request.systemVolumeEnabled)
                .putExtra(EXTRA_SYSTEM_VOLUME, request.systemVolumePercent)
                .putExtra(EXTRA_TEST_PLAYBACK, request.testPlayback)
                .putExtra(EXTRA_PLAY_MUSIC, request.playMusic)
                .putExtra(EXTRA_MUSIC_PROVIDER, request.musicProvider.name)
                .putExtra(
                    EXTRA_MUSIC_RETRY_UNTIL_DISCONNECTED,
                    request.musicRetryUntilDisconnected,
                )
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestStop(context: Context, reason: String) {
            if (!isRunning) return
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_STOP_REASON, reason)
            runCatching { context.startService(intent) }
                .onFailure { context.stopService(Intent(context, PlaybackService::class.java)) }
        }

        fun isActive(): Boolean = isRunning
    }
}

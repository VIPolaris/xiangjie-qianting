package com.polaris.caraudiotrigger.playback

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class AppleMusicResumeResult(
    val statusMessage: String,
    val confirmed: Boolean = false,
    val terminal: Boolean = false,
) {
    STARTED("Apple Music 已开始播放", confirmed = true),
    MEDIA_SERVICE_STARTED("已通过 Apple Music 媒体服务恢复播放", confirmed = true),
    SYSTEM_PLAY_STARTED("已通过系统播放命令恢复 Apple Music", confirmed = true),
    ALREADY_PLAYING("Apple Music 已在播放", confirmed = true),
    NOT_INSTALLED("手机未安装 Apple Music", terminal = true),
    NOTIFICATION_ACCESS_REQUIRED("未授予媒体会话控制权，仍在强制尝试"),
    NO_SESSION("尚未建立 Apple Music 播放会话"),
    AUDIO_NOT_ACTIVE("Apple Music 显示播放但仍未输出声音"),
    FAILED("Apple Music 播放请求暂未生效"),
}

internal data class AppleMusicPlaybackSnapshot(
    val state: Int?,
    val speed: Float,
)

internal interface AppleMusicSessionHandle : AutoCloseable {
    val packageName: String
    fun snapshot(): AppleMusicPlaybackSnapshot
    fun requestPlay(): Boolean
    fun requestPause(): Boolean
    override fun close() = Unit
}

internal data class AppleMusicServiceConnection(
    val session: AppleMusicSessionHandle? = null,
    val attempted: Boolean = false,
)

internal interface AppleMusicPlatform {
    fun isInstalled(): Boolean
    fun hasNotificationAccess(): Boolean
    fun findActiveSession(): AppleMusicSessionHandle?
    fun findMediaKeySession(): AppleMusicSessionHandle?
    fun findOtherActiveSessions(): List<AppleMusicSessionHandle>
    suspend fun connectMediaService(): AppleMusicServiceConnection
    fun launchApp(): Boolean
    fun dispatchSystemPlay(): Boolean
    fun isMusicActive(): Boolean
}

internal object AppleMusicResumePolicy {
    fun shouldResume(
        enabled: Boolean,
        naturalCompletion: Boolean,
    ): Boolean = enabled && naturalCompletion

    fun isConfirmedPlayback(
        snapshot: AppleMusicPlaybackSnapshot,
        musicActive: Boolean,
    ): Boolean = snapshot.state == PlaybackState.STATE_PLAYING &&
        snapshot.speed > 0f &&
        musicActive

    fun matchingSessionIndex(packageNames: List<String>): Int? =
        packageNames.indexOfFirst { it == AppleMusicController.PACKAGE_NAME }
            .takeIf { it >= 0 }

    fun isAppleMusicPackage(packageName: String?): Boolean =
        packageName == AppleMusicController.PACKAGE_NAME

    fun isProviderPackage(packageName: String?, targetPackageName: String): Boolean =
        packageName == targetPackageName
}

class AppleMusicController internal constructor(
    private val platform: AppleMusicPlatform,
    private val descriptor: MusicProviderDescriptor =
        MusicProviderRegistry.descriptor(com.polaris.caraudiotrigger.data.MusicProvider.APPLE_MUSIC),
) {
    constructor(context: Context) : this(
        context,
        MusicProviderRegistry.descriptor(
            com.polaris.caraudiotrigger.data.MusicProvider.APPLE_MUSIC,
        ),
    )

    constructor(
        context: Context,
        descriptor: MusicProviderDescriptor,
    ) : this(AndroidAppleMusicPlatform(context, descriptor), descriptor)

    fun isInstalled(): Boolean = platform.isInstalled()

    fun hasNotificationAccess(): Boolean = platform.hasNotificationAccess()

    suspend fun resume(): AppleMusicResumeResult {
        if (!platform.isInstalled()) return AppleMusicResumeResult.NOT_INSTALLED

        return try {
            resumeAttempt(hasNotificationAccess = platform.hasNotificationAccess())
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            AppleMusicResumeResult.NOTIFICATION_ACCESS_REQUIRED
        } catch (_: RuntimeException) {
            AppleMusicResumeResult.FAILED
        }
    }

    private suspend fun resumeAttempt(hasNotificationAccess: Boolean): AppleMusicResumeResult {
        pauseOtherPlayers()
        var appleSessionSeen = false
        findAppleSession()?.let { existingSession ->
            appleSessionSeen = true
            if (awaitConfirmedPlayback(existingSession, QUICK_CONFIRM_POLLS)) {
                return AppleMusicResumeResult.ALREADY_PLAYING
            }
            if (forceSessionPlayback(existingSession)) return AppleMusicResumeResult.STARTED
        }

        val firstServiceConnection = platform.connectMediaService()
        firstServiceConnection.session?.use { serviceSession ->
            appleSessionSeen = true
            if (forceSessionPlayback(serviceSession)) {
                return AppleMusicResumeResult.MEDIA_SERVICE_STARTED
            }
        }

        val launched = platform.launchApp()
        val launchedSession = if (launched) awaitSession(LAUNCH_DISCOVERY_POLLS) else null
        if (launchedSession != null) {
            appleSessionSeen = true
            if (forceSessionPlayback(launchedSession)) {
                return AppleMusicResumeResult.STARTED
            }
        }

        // Some versions expose their media service only after the application process has been
        // started. Resolve it again instead of reusing a rejected or expired service token.
        if (launched) {
            val secondServiceConnection = platform.connectMediaService()
            secondServiceConnection.session?.use { serviceSession ->
                appleSessionSeen = true
                if (forceSessionPlayback(serviceSession)) {
                    return AppleMusicResumeResult.MEDIA_SERVICE_STARTED
                }
            }
        }

        // Apple Music has absolute priority for this app. A system PLAY command is therefore sent
        // even when another player currently owns the media-button route. Any non-Apple session
        // woken by it is paused, then the foreground recovery loop will try Apple Music again.
        if (platform.dispatchSystemPlay()) {
            val systemResult = awaitSystemPlayWake(hasNotificationAccess)
            if (systemResult.confirmed) return systemResult
            if (systemResult == AppleMusicResumeResult.AUDIO_NOT_ACTIVE) {
                appleSessionSeen = true
            }
        }

        return when {
            !hasNotificationAccess -> AppleMusicResumeResult.NOTIFICATION_ACCESS_REQUIRED
            appleSessionSeen -> AppleMusicResumeResult.AUDIO_NOT_ACTIVE
            else -> AppleMusicResumeResult.NO_SESSION
        }
    }

    private suspend fun awaitSystemPlayWake(
        hasNotificationAccess: Boolean,
    ): AppleMusicResumeResult {
        repeat(SYSTEM_WAKE_POLLS) {
            findAppleSession()?.let { session ->
                if (forceSessionPlayback(session)) {
                    return AppleMusicResumeResult.SYSTEM_PLAY_STARTED
                }
                return AppleMusicResumeResult.AUDIO_NOT_ACTIVE
            }
            pauseOtherPlayers()
            delay(POLL_INTERVAL_MILLIS)
        }
        return if (hasNotificationAccess) {
            AppleMusicResumeResult.NO_SESSION
        } else {
            AppleMusicResumeResult.NOTIFICATION_ACCESS_REQUIRED
        }
    }

    private fun findAppleSession(): AppleMusicSessionHandle? =
        platform.findActiveSession() ?: platform.findMediaKeySession()

    private suspend fun forceSessionPlayback(session: AppleMusicSessionHandle): Boolean {
        if (!AppleMusicResumePolicy.isProviderPackage(
                session.packageName,
                descriptor.packageName,
            )
        ) return false
        if (session.requestPlay() && awaitConfirmedPlayback(session, PLAY_CONFIRM_POLLS)) {
            return true
        }

        // A stale Apple Music session can report PLAYING while owning no live AudioTrack. Force a
        // state transition before playing again so Apple Music rebuilds its decoder and route.
        session.requestPause()
        awaitPauseApplied(session)
        delay(PAUSE_SETTLE_MILLIS)
        if (!session.requestPlay()) return false
        return awaitConfirmedPlayback(session, RESET_CONFIRM_POLLS)
    }

    private fun pauseOtherPlayers() {
        platform.findOtherActiveSessions().forEach { session ->
            runCatching { session.requestPause() }
        }
    }

    private suspend fun awaitSession(polls: Int): AppleMusicSessionHandle? {
        repeat(polls) {
            findAppleSession()?.let { return it }
            delay(POLL_INTERVAL_MILLIS)
        }
        return findAppleSession()
    }

    private suspend fun awaitPauseApplied(session: AppleMusicSessionHandle) {
        repeat(PAUSE_CONFIRM_POLLS) {
            if (session.snapshot().state != PlaybackState.STATE_PLAYING) return
            delay(PAUSE_POLL_INTERVAL_MILLIS)
        }
    }

    private suspend fun awaitConfirmedPlayback(
        session: AppleMusicSessionHandle,
        polls: Int,
    ): Boolean {
        var consecutiveActiveSamples = 0
        repeat(polls) {
            delay(POLL_INTERVAL_MILLIS)
            val confirmed = AppleMusicResumePolicy.isConfirmedPlayback(
                snapshot = session.snapshot(),
                musicActive = platform.isMusicActive(),
            )
            consecutiveActiveSamples = if (confirmed) consecutiveActiveSamples + 1 else 0
            if (consecutiveActiveSamples >= REQUIRED_ACTIVE_SAMPLES) return true
        }
        return false
    }

    companion object {
        const val PACKAGE_NAME = "com.apple.android.music"
        private const val POLL_INTERVAL_MILLIS = 250L
        private const val PAUSE_POLL_INTERVAL_MILLIS = 100L
        private const val PAUSE_SETTLE_MILLIS = 200L
        private const val QUICK_CONFIRM_POLLS = 2
        private const val PLAY_CONFIRM_POLLS = 10
        private const val RESET_CONFIRM_POLLS = 12
        private const val PAUSE_CONFIRM_POLLS = 6
        private const val LAUNCH_DISCOVERY_POLLS = 8
        private const val SYSTEM_WAKE_POLLS = 20
        private const val REQUIRED_ACTIVE_SAMPLES = 2
    }
}

private class AndroidAppleMusicPlatform(
    private val context: Context,
    private val descriptor: MusicProviderDescriptor,
) : AppleMusicPlatform {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, AppleMusicNotificationListener::class.java)

    override fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(
            descriptor.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun hasNotificationAccess(): Boolean =
        notificationManager.isNotificationListenerAccessGranted(listenerComponent)

    @SuppressLint("MissingPermission")
    override fun findActiveSession(): AppleMusicSessionHandle? = activeControllers()
        .firstOrNull {
            AppleMusicResumePolicy.isProviderPackage(it.packageName, descriptor.packageName)
        }
        ?.let(::AndroidAppleMusicSession)

    @SuppressLint("MissingPermission")
    override fun findMediaKeySession(): AppleMusicSessionHandle? {
        if (!hasNotificationAccess()) return null
        return try {
            mediaSessionManager.getMediaKeyEventSession()
                ?.let { MediaController(context, it) }
                ?.takeIf {
                    AppleMusicResumePolicy.isProviderPackage(it.packageName, descriptor.packageName)
                }
                ?.let(::AndroidAppleMusicSession)
        } catch (_: SecurityException) {
            null
        }
    }

    override fun findOtherActiveSessions(): List<AppleMusicSessionHandle> = activeControllers()
        .filter {
            it.packageName != context.packageName &&
                !AppleMusicResumePolicy.isProviderPackage(it.packageName, descriptor.packageName)
        }
        .map(::AndroidAppleMusicSession)
        .filter {
            val snapshot = it.snapshot()
            snapshot.state == PlaybackState.STATE_PLAYING && snapshot.speed > 0f
        }

    override suspend fun connectMediaService(): AppleMusicServiceConnection {
        val services = discoverAppleMediaServices()
        if (services.isEmpty()) return AppleMusicServiceConnection()

        services.filter { it.kind == MediaServiceKind.PLATFORM }.forEach { candidate ->
            connectPlatformBrowser(candidate.component)?.let {
                return AppleMusicServiceConnection(it, attempted = true)
            }
        }
        services.filter { it.kind == MediaServiceKind.MEDIA3 }.forEach { candidate ->
            connectMedia3Service(candidate.component)?.let {
                return AppleMusicServiceConnection(it, attempted = true)
            }
        }
        return AppleMusicServiceConnection(attempted = true)
    }

    override fun launchApp(): Boolean {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(descriptor.packageName)
            ?: return false
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        return runCatching { context.startActivity(launchIntent) }.isSuccess
    }

    override fun dispatchSystemPlay(): Boolean = runCatching {
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0),
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0),
        )
        true
    }.getOrDefault(false)

    override fun isMusicActive(): Boolean = audioManager.isMusicActive

    @SuppressLint("MissingPermission")
    private fun activeControllers(): List<MediaController> {
        if (!hasNotificationAccess()) return emptyList()
        return try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun discoverAppleMediaServices(): List<MediaServiceCandidate> {
        val packageManager = context.packageManager
        val candidates = mutableListOf<MediaServiceCandidate>()
        SERVICE_ACTIONS.forEach { (action, kind) ->
            val resolveInfos = packageManager.queryIntentServices(
                Intent(action).setPackage(descriptor.packageName),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
            resolveInfos.forEach { resolveInfo ->
                val serviceInfo = resolveInfo.serviceInfo ?: return@forEach
                if (serviceInfo.packageName != descriptor.packageName ||
                    !serviceInfo.exported
                ) return@forEach
                candidates += MediaServiceCandidate(
                    component = ComponentName(serviceInfo.packageName, serviceInfo.name),
                    kind = kind,
                )
            }
        }
        return candidates.distinctBy { it.component to it.kind }
    }

    private suspend fun connectPlatformBrowser(
        component: ComponentName,
    ): AppleMusicSessionHandle? {
        val result = CompletableDeferred<AppleMusicSessionHandle?>()
        lateinit var browser: MediaBrowser
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val session = runCatching {
                    AndroidAppleMusicSession(
                        controller = MediaController(context, browser.sessionToken),
                        onClose = { browser.disconnect() },
                    )
                }.getOrNull()
                result.complete(session)
            }

            override fun onConnectionFailed() {
                result.complete(null)
            }

            override fun onConnectionSuspended() {
                result.complete(null)
            }
        }
        browser = MediaBrowser(context, component, callback, null)
        return try {
            browser.connect()
            withTimeoutOrNull(SERVICE_CONNECT_TIMEOUT_MILLIS) { result.await() }
                ?: run {
                    browser.disconnect()
                    null
                }
        } catch (error: CancellationException) {
            runCatching { browser.disconnect() }
            throw error
        } catch (_: RuntimeException) {
            runCatching { browser.disconnect() }
            null
        }
    }

    private suspend fun connectMedia3Service(
        component: ComponentName,
    ): AppleMusicSessionHandle? {
        val future = runCatching {
            androidx.media3.session.MediaController.Builder(
                context,
                SessionToken(context, component),
            ).buildAsync()
        }.getOrNull() ?: return null
        val result = CompletableDeferred<AppleMusicSessionHandle?>()
        future.addListener(
            {
                val session = runCatching {
                    Media3AppleMusicSession(future.get(), descriptor.packageName)
                }.getOrNull()
                result.complete(session)
            },
            ContextCompat.getMainExecutor(context),
        )
        return try {
            withTimeoutOrNull(SERVICE_CONNECT_TIMEOUT_MILLIS) { result.await() }
                ?: run {
                    future.cancel(true)
                    null
                }
        } catch (error: CancellationException) {
            future.cancel(true)
            throw error
        }
    }

    private data class MediaServiceCandidate(
        val component: ComponentName,
        val kind: MediaServiceKind,
    )

    private enum class MediaServiceKind { PLATFORM, MEDIA3 }

    companion object {
        private const val SERVICE_CONNECT_TIMEOUT_MILLIS = 2_000L
        private val SERVICE_ACTIONS = listOf(
            "android.media.browse.MediaBrowserService" to MediaServiceKind.PLATFORM,
            "androidx.media3.session.MediaLibraryService" to MediaServiceKind.MEDIA3,
            "androidx.media3.session.MediaSessionService" to MediaServiceKind.MEDIA3,
        )
    }
}

private class AndroidAppleMusicSession(
    private val controller: MediaController,
    private val onClose: () -> Unit = {},
) : AppleMusicSessionHandle {
    override val packageName: String = controller.packageName

    override fun snapshot(): AppleMusicPlaybackSnapshot {
        val state = controller.playbackState
        return AppleMusicPlaybackSnapshot(
            state = state?.state,
            speed = state?.playbackSpeed ?: 0f,
        )
    }

    override fun requestPlay(): Boolean = runCatching {
        controller.transportControls.play()
        true
    }.getOrDefault(false)

    override fun requestPause(): Boolean = runCatching {
        controller.transportControls.pause()
        true
    }.getOrDefault(false)

    override fun close() = onClose()
}

private class Media3AppleMusicSession(
    private val controller: androidx.media3.session.MediaController,
    override val packageName: String,
) : AppleMusicSessionHandle {
    override fun snapshot(): AppleMusicPlaybackSnapshot {
        val isPlaying = controller.playbackState == Player.STATE_READY && controller.playWhenReady
        return AppleMusicPlaybackSnapshot(
            state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            speed = if (isPlaying) controller.playbackParameters.speed else 0f,
        )
    }

    override fun requestPlay(): Boolean = runCatching {
        controller.play()
        true
    }.getOrDefault(false)

    override fun requestPause(): Boolean = runCatching {
        controller.pause()
        true
    }.getOrDefault(false)

    override fun close() = controller.release()
}

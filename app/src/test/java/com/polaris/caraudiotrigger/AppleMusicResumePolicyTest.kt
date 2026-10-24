package com.polaris.caraudiotrigger

import android.media.session.PlaybackState
import com.polaris.caraudiotrigger.data.MusicProvider
import com.polaris.caraudiotrigger.playback.AppleMusicController
import com.polaris.caraudiotrigger.playback.AppleMusicPlatform
import com.polaris.caraudiotrigger.playback.AppleMusicPlaybackSnapshot
import com.polaris.caraudiotrigger.playback.AppleMusicResumePolicy
import com.polaris.caraudiotrigger.playback.AppleMusicResumeResult
import com.polaris.caraudiotrigger.playback.AppleMusicServiceConnection
import com.polaris.caraudiotrigger.playback.AppleMusicSessionHandle
import com.polaris.caraudiotrigger.playback.MusicProviderRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicResumePolicyTest {
    @Test
    fun `all naturally completed playback may resume Apple Music`() {
        assertTrue(AppleMusicResumePolicy.shouldResume(enabled = true, naturalCompletion = true))
        assertFalse(AppleMusicResumePolicy.shouldResume(enabled = false, naturalCompletion = true))
        assertFalse(AppleMusicResumePolicy.shouldResume(enabled = true, naturalCompletion = false))
    }

    @Test
    fun `session selection matches only official Apple Music package`() {
        assertEquals(
            1,
            AppleMusicResumePolicy.matchingSessionIndex(
                listOf("com.spotify.music", AppleMusicController.PACKAGE_NAME),
            ),
        )
        assertNull(
            AppleMusicResumePolicy.matchingSessionIndex(
                listOf("com.spotify.music", "com.apple.android.music.classical"),
            ),
        )
        assertTrue(AppleMusicResumePolicy.isAppleMusicPackage(AppleMusicController.PACKAGE_NAME))
        assertFalse(AppleMusicResumePolicy.isAppleMusicPackage("com.spotify.music"))
    }

    @Test
    fun `reported playing requires Apple state speed and real audio`() {
        val complete = AppleMusicPlaybackSnapshot(PlaybackState.STATE_PLAYING, 1f)
        assertTrue(AppleMusicResumePolicy.isConfirmedPlayback(complete, musicActive = true))
        assertFalse(AppleMusicResumePolicy.isConfirmedPlayback(complete, musicActive = false))
        assertFalse(
            AppleMusicResumePolicy.isConfirmedPlayback(
                complete.copy(speed = 0f),
                musicActive = true,
            ),
        )
    }

    @Test
    fun `active paused Apple session receives direct play`() = runTest {
        val session = FakeSession()
        val platform = FakePlatform(activeSession = session).apply {
            musicActiveProvider = { session.isPlaying }
        }

        val result = AppleMusicController(platform).resume()

        assertEquals(AppleMusicResumeResult.STARTED, result)
        assertEquals(listOf("play"), session.commands)
        assertEquals(0, platform.systemPlayDispatches)
    }

    @Test
    fun `saved Apple media key session stays targeted`() = runTest {
        val session = FakeSession()
        val platform = FakePlatform(mediaKeySession = session).apply {
            musicActiveProvider = { session.isPlaying }
        }

        val result = AppleMusicController(platform).resume()

        assertEquals(AppleMusicResumeResult.STARTED, result)
        assertEquals(listOf("play"), session.commands)
        assertEquals(0, platform.systemPlayDispatches)
    }

    @Test
    fun `Apple media service restores playback before launch`() = runTest {
        val serviceSession = FakeSession()
        val platform = FakePlatform(serviceSession = serviceSession).apply {
            musicActiveProvider = { serviceSession.isPlaying }
        }

        val result = AppleMusicController(platform).resume()

        assertEquals(AppleMusicResumeResult.MEDIA_SERVICE_STARTED, result)
        assertEquals(1, platform.serviceConnections)
        assertFalse(platform.launched)
        assertEquals(1, serviceSession.closeCalls)
        assertEquals(0, platform.systemPlayDispatches)
    }

    @Test
    fun `silent Apple session is reset and still receives global play`() = runTest {
        val session = FakeSession()
        val platform = FakePlatform(activeSession = session).apply {
            musicActiveProvider = { false }
        }

        val result = AppleMusicController(platform).resume()

        assertEquals(AppleMusicResumeResult.AUDIO_NOT_ACTIVE, result)
        assertTrue(session.commands.contains("pause"))
        assertTrue(session.commands.count { it == "play" } >= 2)
        assertEquals(1, platform.systemPlayDispatches)
    }

    @Test
    fun `other active player never blocks system play and is paused`() = runTest {
        val other = FakeSession(packageName = "com.spotify.music").apply { acceptPlay() }
        val apple = FakeSession()
        val platform = FakePlatform(otherSessions = mutableListOf(other))
        platform.musicActiveProvider = { apple.isPlaying || other.isPlaying }
        platform.onSystemPlay = {
            apple.acceptPlay()
            platform.activeSession = apple
        }

        val result = AppleMusicController(platform).resume()

        assertEquals(AppleMusicResumeResult.SYSTEM_PLAY_STARTED, result)
        assertEquals(1, platform.systemPlayDispatches)
        assertTrue(other.commands.contains("pause"))
    }

    @Test
    fun `wrong player woken by global play is paused without becoming success`() = runTest {
        val other = FakeSession(packageName = "com.spotify.music")
        val platform = FakePlatform(otherSessions = mutableListOf(other)).apply {
            musicActiveProvider = { other.isPlaying }
            onSystemPlay = { other.acceptPlay() }
        }

        val result = AppleMusicController(platform).resume()

        assertEquals(AppleMusicResumeResult.NO_SESSION, result)
        assertEquals(1, platform.systemPlayDispatches)
        assertTrue(other.commands.contains("pause"))
    }

    @Test
    fun `missing notification access still launches and sends global play`() = runTest {
        val platform = FakePlatform(notificationAccess = false).apply {
            musicActiveProvider = { false }
        }

        val result = AppleMusicController(platform).resume()

        assertEquals(AppleMusicResumeResult.NOTIFICATION_ACCESS_REQUIRED, result)
        assertTrue(platform.launched)
        assertEquals(1, platform.systemPlayDispatches)
        assertFalse(result.terminal)
    }

    @Test
    fun `missing Apple Music is the only terminal result`() = runTest {
        val platform = FakePlatform(installed = false)

        val result = AppleMusicController(platform).resume()

        assertEquals(AppleMusicResumeResult.NOT_INSTALLED, result)
        assertTrue(result.terminal)
        assertEquals(0, platform.systemPlayDispatches)
    }

    @Test
    fun `selected non Apple provider uses only its own package session`() = runTest {
        val spotifyDescriptor = MusicProviderRegistry.descriptor(MusicProvider.SPOTIFY)
        val spotify = FakeSession(packageName = spotifyDescriptor.packageName)
        val platform = FakePlatform(activeSession = spotify).apply {
            musicActiveProvider = { spotify.isPlaying }
        }

        val result = AppleMusicController(platform, spotifyDescriptor).resume()

        assertEquals(AppleMusicResumeResult.STARTED, result)
        assertEquals(listOf("play"), spotify.commands)
        assertEquals(0, platform.systemPlayDispatches)
    }

    private class FakePlatform(
        var activeSession: FakeSession? = null,
        var mediaKeySession: FakeSession? = null,
        var serviceSession: FakeSession? = null,
        var otherSessions: MutableList<FakeSession> = mutableListOf(),
        private val notificationAccess: Boolean = true,
        private val installed: Boolean = true,
    ) : AppleMusicPlatform {
        var launched = false
        var serviceConnections = 0
        var systemPlayDispatches = 0
        var onSystemPlay: () -> Unit = {}
        var musicActiveProvider: () -> Boolean = { false }

        override fun isInstalled() = installed
        override fun hasNotificationAccess() = notificationAccess
        override fun findActiveSession(): AppleMusicSessionHandle? =
            activeSession.takeIf { notificationAccess }

        override fun findMediaKeySession(): AppleMusicSessionHandle? =
            mediaKeySession.takeIf { notificationAccess }

        override fun findOtherActiveSessions(): List<AppleMusicSessionHandle> =
            if (notificationAccess) otherSessions.filter { it.isPlaying } else emptyList()

        override suspend fun connectMediaService(): AppleMusicServiceConnection {
            serviceConnections += 1
            return AppleMusicServiceConnection(serviceSession, attempted = serviceSession != null)
        }

        override fun launchApp(): Boolean {
            launched = true
            return true
        }

        override fun dispatchSystemPlay(): Boolean {
            systemPlayDispatches += 1
            onSystemPlay()
            return true
        }

        override fun isMusicActive(): Boolean = musicActiveProvider()
    }

    private class FakeSession(
        override val packageName: String = AppleMusicController.PACKAGE_NAME,
    ) : AppleMusicSessionHandle {
        val commands = mutableListOf<String>()
        var closeCalls = 0
        var isPlaying = false
            private set

        override fun snapshot() = AppleMusicPlaybackSnapshot(
            state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            speed = if (isPlaying) 1f else 0f,
        )

        override fun requestPlay(): Boolean {
            commands += "play"
            acceptPlay()
            return true
        }

        override fun requestPause(): Boolean {
            commands += "pause"
            isPlaying = false
            return true
        }

        override fun close() {
            closeCalls += 1
        }

        fun acceptPlay() {
            isPlaying = true
        }
    }
}

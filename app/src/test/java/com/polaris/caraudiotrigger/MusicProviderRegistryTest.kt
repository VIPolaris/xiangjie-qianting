package com.polaris.caraudiotrigger

import com.polaris.caraudiotrigger.data.MusicProvider
import com.polaris.caraudiotrigger.playback.MusicProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicProviderRegistryTest {
    @Test
    fun `all selectable providers have a unique package and install page`() {
        val expectedPackages = mapOf(
            MusicProvider.APPLE_MUSIC to "com.apple.android.music",
            MusicProvider.SPOTIFY to "com.spotify.music",
            MusicProvider.QQ_MUSIC to "com.tencent.qqmusic",
            MusicProvider.NETEASE_CLOUD_MUSIC to "com.netease.cloudmusic",
            MusicProvider.KUGOU_MUSIC to "com.kugou.android",
        )

        assertEquals(MusicProvider.entries, MusicProviderRegistry.providers)
        assertEquals(
            expectedPackages,
            MusicProvider.entries.associateWith {
                MusicProviderRegistry.descriptor(it).packageName
            },
        )
        assertEquals(
            expectedPackages.size,
            expectedPackages.values.toSet().size,
        )
        MusicProvider.entries.forEach { provider ->
            assertTrue(MusicProviderRegistry.descriptor(provider).installUrl.startsWith("https://"))
        }
    }
}

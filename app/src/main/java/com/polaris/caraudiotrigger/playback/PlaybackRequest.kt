package com.polaris.caraudiotrigger.playback

import com.polaris.caraudiotrigger.data.MusicProvider

data class PlaybackRequest(
    val targetSlot: Int,
    val historyId: Long?,
    val playAnnouncement: Boolean,
    val audioPath: String,
    val fallbackAudioPath: String,
    val targetAddress: String,
    val preferredAudioDeviceId: Int,
    val playerVolumePercent: Int,
    val systemVolumeEnabled: Boolean,
    val systemVolumePercent: Int,
    val testPlayback: Boolean,
    val playMusic: Boolean,
    val musicProvider: MusicProvider,
    val musicRetryUntilDisconnected: Boolean,
)

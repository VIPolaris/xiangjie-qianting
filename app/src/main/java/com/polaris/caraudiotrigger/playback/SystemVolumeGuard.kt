package com.polaris.caraudiotrigger.playback

import android.media.AudioManager

class SystemVolumeGuard private constructor(
    private val audioManager: AudioManager,
    private val originalIndex: Int,
    private val appliedIndex: Int,
) {
    private var restored = false

    fun restoreIfUnchanged() {
        if (restored) return
        restored = true
        runCatching {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (VolumeMath.shouldRestoreStreamVolume(current, appliedIndex)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalIndex, 0)
            }
        }
    }

    companion object {
        fun applyIfEnabled(
            audioManager: AudioManager,
            enabled: Boolean,
            targetPercent: Int,
        ): SystemVolumeGuard? {
            if (!enabled) return null
            val original = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = VolumeMath.percentToStreamIndex(targetPercent, maximum)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            return SystemVolumeGuard(audioManager, original, target)
        }
    }
}

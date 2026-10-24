package com.polaris.caraudiotrigger.playback

import kotlin.math.roundToInt

object VolumeMath {
    fun percentToStreamIndex(percent: Int, maximum: Int): Int {
        if (maximum <= 0) return 0
        return (percent.coerceIn(0, 100) / 100f * maximum).roundToInt().coerceIn(0, maximum)
    }

    fun percentToPlayerGain(percent: Int): Float = percent.coerceIn(0, 100) / 100f

    fun shouldRestoreStreamVolume(currentIndex: Int, appliedIndex: Int): Boolean =
        currentIndex == appliedIndex
}

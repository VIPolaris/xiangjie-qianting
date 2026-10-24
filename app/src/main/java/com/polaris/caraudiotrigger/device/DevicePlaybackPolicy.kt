package com.polaris.caraudiotrigger.device

enum class DevicePlaybackMode(
    val shouldRun: Boolean,
    val playAnnouncement: Boolean,
    val playMusic: Boolean,
) {
    DISABLED(false, false, false),
    ANNOUNCEMENT_ONLY(true, true, false),
    MUSIC_ONLY(true, false, true),
    ANNOUNCEMENT_THEN_MUSIC(true, true, true),
}

object DevicePlaybackPolicy {
    fun resolve(announcementEnabled: Boolean, musicEnabled: Boolean): DevicePlaybackMode =
        when {
            announcementEnabled && musicEnabled ->
                DevicePlaybackMode.ANNOUNCEMENT_THEN_MUSIC
            announcementEnabled -> DevicePlaybackMode.ANNOUNCEMENT_ONLY
            musicEnabled -> DevicePlaybackMode.MUSIC_ONLY
            else -> DevicePlaybackMode.DISABLED
        }
}

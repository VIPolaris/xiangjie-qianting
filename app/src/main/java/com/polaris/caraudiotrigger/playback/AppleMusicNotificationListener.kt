package com.polaris.caraudiotrigger.playback

import android.service.notification.NotificationListenerService

/**
 * Grants media-session discovery after the user enables notification access.
 * Notification contents are intentionally never read, processed, or stored.
 */
class AppleMusicNotificationListener : NotificationListenerService()

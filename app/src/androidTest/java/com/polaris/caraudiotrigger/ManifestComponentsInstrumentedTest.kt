package com.polaris.caraudiotrigger

import android.app.Service
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polaris.caraudiotrigger.device.CarCompanionService
import com.polaris.caraudiotrigger.playback.AppleMusicNotificationListener
import com.polaris.caraudiotrigger.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestComponentsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun playbackServiceDeclaresPlaybackAndConnectedDeviceForegroundTypes() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, PlaybackService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertTrue(
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0,
        )
        assertTrue(
            info.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0,
        )
        assertEquals(Service::class.java, PlaybackService::class.java.superclass)
    }

    @Test
    fun companionServiceIsProtectedBySystemBindingPermission() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, CarCompanionService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertEquals("android.permission.BIND_COMPANION_DEVICE_SERVICE", info.permission)
    }

    @Test
    fun appleMusicListenerIsProtectedBySystemBindingPermission() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AppleMusicNotificationListener::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", info.permission)
    }
}

package com.polaris.caraudiotrigger.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.polaris.caraudiotrigger.CarAudioApplication
import com.polaris.caraudiotrigger.MainActivity
import com.polaris.caraudiotrigger.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process and CPU alive between a locked-screen Bluetooth event and playback startup.
 * The service exists only for the configured delay and A2DP route lookup; PlaybackService takes
 * over foreground ownership as soon as audio is ready.
 */
class ConnectionTriggerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinatorJobs = mutableSetOf<Job>()
    private var activeRequests = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:connection-trigger")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MILLIS)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val application = application as CarAudioApplication
        val associationId = intent?.getIntExtra(EXTRA_ASSOCIATION_ID, NO_ASSOCIATION)
            ?.takeUnless { it == NO_ASSOCIATION }
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        val job = when {
            associationId != null -> application.connectionCoordinator.onCompanionConnected(associationId)
            !address.isNullOrBlank() -> application.connectionCoordinator.onAddressConnected(address)
            else -> null
        }
        if (job == null) {
            finishIfIdle()
            return START_NOT_STICKY
        }

        activeRequests += 1
        coordinatorJobs += job
        serviceScope.launch {
            try {
                job.join()
            } finally {
                coordinatorJobs -= job
                activeRequests -= 1
                finishIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.trigger_notification_title))
            .setContentText(getString(R.string.trigger_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.trigger_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) },
        )
    }

    private fun finishIfIdle() {
        if (activeRequests != 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        coordinatorJobs.toList().forEach { it.cancel() }
        coordinatorJobs.clear()
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_ASSOCIATION_ID = "association_id"
        private const val EXTRA_ADDRESS = "address"
        private const val NO_ASSOCIATION = -1
        private const val NOTIFICATION_CHANNEL_ID = "car_connection_trigger"
        private const val NOTIFICATION_ID = 4106
        private const val MAX_WAKE_LOCK_MILLIS = 70_000L

        fun start(context: Context, associationId: Int? = null, address: String? = null) {
            val intent = Intent(context, ConnectionTriggerService::class.java)
                .putExtra(EXTRA_ASSOCIATION_ID, associationId ?: NO_ASSOCIATION)
                .putExtra(EXTRA_ADDRESS, address)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { error ->
                    val application = context.applicationContext as? CarAudioApplication
                    application?.applicationScope?.launch {
                        application.settingsRepository.setStatus(
                            "系统阻止后台连接任务：${error.localizedMessage ?: "请检查后台权限"}",
                        )
                    }
                }
        }

    }
}

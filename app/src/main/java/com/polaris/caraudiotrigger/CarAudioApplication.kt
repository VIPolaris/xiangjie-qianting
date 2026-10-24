package com.polaris.caraudiotrigger

import android.app.Application
import com.polaris.caraudiotrigger.data.AudioFileStore
import com.polaris.caraudiotrigger.data.SettingsRepository
import com.polaris.caraudiotrigger.device.A2dpRouteResolver
import com.polaris.caraudiotrigger.device.CompanionDeviceController
import com.polaris.caraudiotrigger.device.ConnectionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CarAudioApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var audioFileStore: AudioFileStore
        private set
    lateinit var companionDeviceController: CompanionDeviceController
        private set
    lateinit var connectionCoordinator: ConnectionCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        audioFileStore = AudioFileStore(this)
        companionDeviceController = CompanionDeviceController(this, settingsRepository)
        connectionCoordinator = ConnectionCoordinator(
            context = this,
            scope = applicationScope,
            settingsRepository = settingsRepository,
            audioFileStore = audioFileStore,
            routeResolver = A2dpRouteResolver(this),
        )
        applicationScope.launch(Dispatchers.IO) {
            runCatching { audioFileStore.ensureBuiltInChime() }
        }
    }
}


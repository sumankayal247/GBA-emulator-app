package io.github.sumankayal247.gbaemulator

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.sumankayal247.gbaemulator.data.db.GbaLibraryDatabase
import io.github.sumankayal247.gbaemulator.data.firmware.BiosRepository
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.rom.DeviceStorageScanner
import io.github.sumankayal247.gbaemulator.data.rom.LibraryRepository
import io.github.sumankayal247.gbaemulator.data.rom.RomStagingRepository
import io.github.sumankayal247.gbaemulator.data.saves.SaveStateRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.emulator.ControllerManager
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(application: Application) {
    val database = GbaLibraryDatabase.create(application)
    val preferencesRepository = PreferencesRepository(application)
    val sramSaveRepository = SramSaveRepository(File(application.filesDir, "saves"))
    val saveStateRepository = SaveStateRepository(File(application.filesDir, "saves"))
    val biosRepository = BiosRepository(File(application.filesDir, "system"))
    val romStagingRepository = RomStagingRepository(application)
    val libraryRepository = LibraryRepository(
        context = application,
        dao = database.libraryDao(),
        stagingRepository = romStagingRepository,
        sramRepository = sramSaveRepository,
        saveStateRepository = saveStateRepository,
        preferencesRepository = preferencesRepository,
    )
    val deviceStorageScanner = DeviceStorageScanner(application, libraryRepository)
    val emulatorSession = EmulatorSession(
        context = application,
        sramSaveRepository = sramSaveRepository,
        saveStateRepository = saveStateRepository,
        libraryRepository = libraryRepository,
        preferencesRepository = preferencesRepository,
    )
    val controllerManager = ControllerManager(
        context = application,
        preferencesRepository = preferencesRepository,
        onGamepadBitMaskChanged = { mask -> emulatorSession.setGamepadInputMask(mask) },
    )
}

class GbaEmulatorApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // One-time migration of any legacy DataStore recents into the Room library DB.
        appScope.launch {
            runCatching { container.libraryRepository.migrateLegacyRecentGamesIfNeeded() }
        }

        // Ensure backgrounding the process pauses emulation, releases inputs, and flushes SRAM.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    container.controllerManager.releaseAllInputs()
                    container.emulatorSession.onAppBackgrounded()
                }
            },
        )
    }
}

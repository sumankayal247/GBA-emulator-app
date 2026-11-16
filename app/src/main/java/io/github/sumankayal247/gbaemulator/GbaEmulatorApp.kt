package io.github.sumankayal247.gbaemulator

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.rom.RomStagingRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSession
import java.io.File

class AppContainer(application: Application) {
    val preferencesRepository = PreferencesRepository(application)
    val sramSaveRepository = SramSaveRepository(File(application.filesDir, "saves"))
    val romStagingRepository = RomStagingRepository(application)
    val emulatorSession = EmulatorSession(
        context = application,
        sramSaveRepository = sramSaveRepository,
        preferencesRepository = preferencesRepository,
    )
}

class GbaEmulatorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Ensure backgrounding the process pauses emulation, releases inputs, and flushes SRAM.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    container.emulatorSession.onAppBackgrounded()
                }
            },
        )
    }
}

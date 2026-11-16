package io.github.sumankayal247.gbaemulator.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

private val Context.emulatorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gba_emulator_prefs",
)

/**
 * Pure Kotlin, versioned serialization codec for recent ROM metadata stored in DataStore
 * (unit-testable on JVM without Android JSON stubs).
 * Replaced by versioned Room database in milestone P3.
 */
object RecentGamesCodec {
    private const val HEADER_PREFIX = "v1\n"
    private const val MAX_RECENTS = 30

    fun encode(records: List<RomRecord>): String {
        val limited = records
            .sortedByDescending { it.lastPlayedEpochMs }
            .distinctBy { it.romId }
            .take(MAX_RECENTS)
        return buildString {
            append(HEADER_PREFIX)
            for (r in limited) {
                append(enc(r.romId)).append('\t')
                append(enc(r.sha256)).append('\t')
                append(enc(r.system.id)).append('\t')
                append(enc(r.displayTitle)).append('\t')
                append(enc(r.sourceUri)).append('\t')
                append(r.fileSizeBytes).append('\t')
                append(r.lastPlayedEpochMs).append('\t')
                append(if (r.hasBatterySave) "1" else "0").append('\n')
            }
        }
    }

    fun decode(raw: String?): List<RomRecord> {
        if (raw.isNullOrBlank() || !raw.startsWith(HEADER_PREFIX)) {
            return emptyList()
        }
        val out = mutableListOf<RomRecord>()
        val lines = raw.removePrefix(HEADER_PREFIX).split('\n')
        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 8) continue
            val system = GameSystem.fromId(dec(parts[2])) ?: continue
            val fileSize = parts[5].toLongOrNull() ?: continue
            val lastPlayed = parts[6].toLongOrNull() ?: continue
            val romId = dec(parts[0])
            val sha256 = dec(parts[1])
            if (romId.isBlank() || sha256.length != 64) continue
            out.add(
                RomRecord(
                    romId = romId,
                    sha256 = sha256,
                    system = system,
                    displayTitle = dec(parts[3]).ifBlank { "Untitled Game" },
                    sourceUri = dec(parts[4]),
                    fileSizeBytes = fileSize,
                    lastPlayedEpochMs = lastPlayed,
                    hasBatterySave = parts[7] == "1",
                    sourceAvailable = true,
                ),
            )
        }
        return out.sortedByDescending { it.lastPlayedEpochMs }.distinctBy { it.romId }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun dec(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SCALING_MODE = intPreferencesKey("scaling_mode")
        val MUTED = booleanPreferencesKey("muted")
        val AUDIO_VOLUME = floatPreferencesKey("audio_volume")
        val ORIENTATION_LOCK = stringPreferencesKey("orientation_lock")
        val CONTROLS_OPACITY = floatPreferencesKey("controls_opacity")
        val CONTROLS_SCALE = floatPreferencesKey("controls_scale")
        val RECENT_GAMES = stringPreferencesKey("recent_games_v1")
    }

    val preferencesFlow: Flow<EmulatorPreferences> = context.emulatorDataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .map { prefs ->
            EmulatorPreferences(
                scalingMode = ScalingMode.fromNative(prefs[Keys.SCALING_MODE] ?: ScalingMode.ASPECT_FIT.nativeValue),
                muted = prefs[Keys.MUTED] ?: false,
                audioVolume = (prefs[Keys.AUDIO_VOLUME] ?: 1.0f).coerceIn(0f, 1f),
                orientationLock = OrientationLock.fromId(prefs[Keys.ORIENTATION_LOCK] ?: OrientationLock.AUTO.id),
                controlsOpacity = (prefs[Keys.CONTROLS_OPACITY] ?: 0.92f).coerceIn(0.35f, 1.0f),
                controlsScale = (prefs[Keys.CONTROLS_SCALE] ?: 1.0f).coerceIn(0.8f, 1.25f),
            )
        }

    val recentGamesFlow: Flow<List<RomRecord>> = context.emulatorDataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .map { prefs ->
            RecentGamesCodec.decode(prefs[Keys.RECENT_GAMES])
        }

    suspend fun updateScalingMode(mode: ScalingMode) {
        context.emulatorDataStore.edit { it[Keys.SCALING_MODE] = mode.nativeValue }
    }

    suspend fun updateMuted(muted: Boolean) {
        context.emulatorDataStore.edit { it[Keys.MUTED] = muted }
    }

    suspend fun updateAudioVolume(volume: Float) {
        context.emulatorDataStore.edit { it[Keys.AUDIO_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun updateOrientationLock(lock: OrientationLock) {
        context.emulatorDataStore.edit { it[Keys.ORIENTATION_LOCK] = lock.id }
    }

    suspend fun updateControlsOpacity(opacity: Float) {
        context.emulatorDataStore.edit { it[Keys.CONTROLS_OPACITY] = opacity.coerceIn(0.35f, 1.0f) }
    }

    suspend fun updateControlsScale(scale: Float) {
        context.emulatorDataStore.edit { it[Keys.CONTROLS_SCALE] = scale.coerceIn(0.8f, 1.25f) }
    }

    suspend fun upsertRecentGame(record: RomRecord) {
        context.emulatorDataStore.edit { prefs ->
            val current = RecentGamesCodec.decode(prefs[Keys.RECENT_GAMES]).toMutableList()
            current.removeAll { it.romId == record.romId }
            current.add(0, record)
            prefs[Keys.RECENT_GAMES] = RecentGamesCodec.encode(current)
        }
    }

    suspend fun markBatterySavePresent(romId: String, hasSave: Boolean) {
        context.emulatorDataStore.edit { prefs ->
            val updated = RecentGamesCodec.decode(prefs[Keys.RECENT_GAMES]).map {
                if (it.romId == romId) it.copy(hasBatterySave = hasSave) else it
            }
            prefs[Keys.RECENT_GAMES] = RecentGamesCodec.encode(updated)
        }
    }

    suspend fun removeRecentGame(romId: String) {
        context.emulatorDataStore.edit { prefs ->
            val updated = RecentGamesCodec.decode(prefs[Keys.RECENT_GAMES]).filterNot { it.romId == romId }
            prefs[Keys.RECENT_GAMES] = RecentGamesCodec.encode(updated)
        }
    }
}

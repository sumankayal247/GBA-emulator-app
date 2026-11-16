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
import io.github.sumankayal247.gbaemulator.domain.model.ControllerProfile
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import io.github.sumankayal247.gbaemulator.domain.model.TouchLayoutProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

private val Context.emulatorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gba_emulator_prefs",
)

/**
 * Pure Kotlin, versioned serialization codec for recent ROM metadata (kept for backwards
 * compatibility and migration into the Room library database).
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

/**
 * Pure Kotlin codec for persisted per-controller button mapping profiles.
 */
object ControllerProfilesCodec {
    private const val HEADER_PREFIX = "ctrl_v1\n"

    fun encode(profiles: Map<String, ControllerProfile>): String = buildString {
        append(HEADER_PREFIX)
        profiles.values.sortedBy { it.displayName.lowercase() }.forEach { profile ->
            append(profile.serialize()).append('\n')
        }
    }

    fun decode(raw: String?): Map<String, ControllerProfile> {
        if (raw.isNullOrBlank() || !raw.startsWith(HEADER_PREFIX)) return emptyMap()
        val out = LinkedHashMap<String, ControllerProfile>()
        raw.removePrefix(HEADER_PREFIX).split('\n').forEach { line ->
            if (line.isNotBlank()) {
                val parsed = ControllerProfile.deserialize(line)
                if (parsed != null) {
                    out[parsed.descriptor] = parsed
                }
            }
        }
        return out
    }
}

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SCALING_MODE = intPreferencesKey("scaling_mode")
        val MUTED = booleanPreferencesKey("muted")
        val AUDIO_VOLUME = floatPreferencesKey("audio_volume")
        val ORIENTATION_LOCK = stringPreferencesKey("orientation_lock")
        val CONTROLS_OPACITY = floatPreferencesKey("controls_opacity")
        val CONTROLS_SCALE = floatPreferencesKey("controls_scale")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val AUTO_HIDE_TOUCH_CONTROLLER = booleanPreferencesKey("auto_hide_touch_controller")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val PORTRAIT_LAYOUT = stringPreferencesKey("portrait_layout_v1")
        val LANDSCAPE_LAYOUT = stringPreferencesKey("landscape_layout_v1")
        val CONTROLLER_PROFILES = stringPreferencesKey("controller_profiles_v1")
        val RECENT_GAMES = stringPreferencesKey("recent_games_v1")
    }

    val preferencesFlow: Flow<EmulatorPreferences> = context.emulatorDataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .map { prefs ->
            val legacyOpacity = (prefs[Keys.CONTROLS_OPACITY] ?: 0.92f).coerceIn(0.35f, 1.0f)
            val legacyScale = (prefs[Keys.CONTROLS_SCALE] ?: 1.0f).coerceIn(0.75f, 1.30f)
            val portrait = TouchLayoutProfile.deserialize(prefs[Keys.PORTRAIT_LAYOUT], landscape = false)
            val landscape = TouchLayoutProfile.deserialize(prefs[Keys.LANDSCAPE_LAYOUT], landscape = true)
            EmulatorPreferences(
                scalingMode = ScalingMode.fromNative(prefs[Keys.SCALING_MODE] ?: ScalingMode.ASPECT_FIT.nativeValue),
                muted = prefs[Keys.MUTED] ?: false,
                audioVolume = (prefs[Keys.AUDIO_VOLUME] ?: 1.0f).coerceIn(0f, 1f),
                orientationLock = OrientationLock.fromId(prefs[Keys.ORIENTATION_LOCK] ?: OrientationLock.AUTO.id),
                controlsOpacity = legacyOpacity,
                controlsScale = legacyScale,
                hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
                autoHideTouchWithController = prefs[Keys.AUTO_HIDE_TOUCH_CONTROLLER] ?: true,
                reducedMotion = prefs[Keys.REDUCED_MOTION] ?: false,
                portraitLayout = portrait,
                landscapeLayout = landscape,
            )
        }

    val recentGamesFlow: Flow<List<RomRecord>> = context.emulatorDataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .map { prefs ->
            RecentGamesCodec.decode(prefs[Keys.RECENT_GAMES])
        }

    val controllerProfilesFlow: Flow<Map<String, ControllerProfile>> = context.emulatorDataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .map { prefs ->
            ControllerProfilesCodec.decode(prefs[Keys.CONTROLLER_PROFILES])
        }

    suspend fun getLegacyRecentGamesOnce(): List<RomRecord> = recentGamesFlow.first()

    suspend fun clearLegacyRecentGames() {
        context.emulatorDataStore.edit { it.remove(Keys.RECENT_GAMES) }
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
        val clamped = opacity.coerceIn(0.35f, 1.0f)
        context.emulatorDataStore.edit { prefs ->
            prefs[Keys.CONTROLS_OPACITY] = clamped
            val p = TouchLayoutProfile.deserialize(prefs[Keys.PORTRAIT_LAYOUT], false)
                .copy(opacity = clamped).clamped()
            val l = TouchLayoutProfile.deserialize(prefs[Keys.LANDSCAPE_LAYOUT], true)
                .copy(opacity = clamped).clamped()
            prefs[Keys.PORTRAIT_LAYOUT] = p.serialize()
            prefs[Keys.LANDSCAPE_LAYOUT] = l.serialize()
        }
    }

    suspend fun updateControlsScale(scale: Float) {
        val clamped = scale.coerceIn(0.75f, 1.30f)
        context.emulatorDataStore.edit { prefs ->
            prefs[Keys.CONTROLS_SCALE] = clamped
            val p = TouchLayoutProfile.deserialize(prefs[Keys.PORTRAIT_LAYOUT], false)
                .copy(scale = clamped).clamped()
            val l = TouchLayoutProfile.deserialize(prefs[Keys.LANDSCAPE_LAYOUT], true)
                .copy(scale = clamped).clamped()
            prefs[Keys.PORTRAIT_LAYOUT] = p.serialize()
            prefs[Keys.LANDSCAPE_LAYOUT] = l.serialize()
        }
    }

    suspend fun updateHapticsEnabled(enabled: Boolean) {
        context.emulatorDataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun updateAutoHideTouchWithController(enabled: Boolean) {
        context.emulatorDataStore.edit { it[Keys.AUTO_HIDE_TOUCH_CONTROLLER] = enabled }
    }

    suspend fun updateReducedMotion(enabled: Boolean) {
        context.emulatorDataStore.edit { it[Keys.REDUCED_MOTION] = enabled }
    }

    suspend fun updatePortraitLayout(profile: TouchLayoutProfile) {
        val clamped = profile.clamped()
        context.emulatorDataStore.edit {
            it[Keys.PORTRAIT_LAYOUT] = clamped.serialize()
            it[Keys.CONTROLS_OPACITY] = clamped.opacity
            it[Keys.CONTROLS_SCALE] = clamped.scale
        }
    }

    suspend fun updateLandscapeLayout(profile: TouchLayoutProfile) {
        val clamped = profile.clamped()
        context.emulatorDataStore.edit {
            it[Keys.LANDSCAPE_LAYOUT] = clamped.serialize()
        }
    }

    suspend fun upsertControllerProfile(profile: ControllerProfile) {
        context.emulatorDataStore.edit { prefs ->
            val current = ControllerProfilesCodec.decode(prefs[Keys.CONTROLLER_PROFILES]).toMutableMap()
            current[profile.descriptor] = profile
            prefs[Keys.CONTROLLER_PROFILES] = ControllerProfilesCodec.encode(current)
        }
    }

    suspend fun removeControllerProfile(descriptor: String) {
        context.emulatorDataStore.edit { prefs ->
            val current = ControllerProfilesCodec.decode(prefs[Keys.CONTROLLER_PROFILES]).toMutableMap()
            current.remove(descriptor)
            prefs[Keys.CONTROLLER_PROFILES] = ControllerProfilesCodec.encode(current)
        }
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

package com.happy.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "happy_prefs")

/**
 * Small, boring settings store. Phase 0 only really needs [serviceEnabled];
 * the audio knobs are declared now so later phases add no new plumbing.
 */
@Singleton
class Prefs @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
        val WAKE_THRESHOLD = floatPreferencesKey("wake_threshold")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
    }

    /** Whether the user wants Happy running. Consulted on boot. */
    val serviceEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SERVICE_ENABLED] ?: false }

    /** openWakeWord score threshold. Spec section 4: start at 0.5, tune 0.3-0.7. */
    val wakeThreshold: Flow<Float> =
        context.dataStore.data.map { it[Keys.WAKE_THRESHOLD] ?: DEFAULT_WAKE_THRESHOLD }

    val ttsRate: Flow<Float> = context.dataStore.data.map { it[Keys.TTS_RATE] ?: DEFAULT_TTS_RATE }

    val ttsPitch: Flow<Float> = context.dataStore.data.map { it[Keys.TTS_PITCH] ?: DEFAULT_TTS_PITCH }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SERVICE_ENABLED] = enabled }
    }

    suspend fun setWakeThreshold(value: Float) {
        context.dataStore.edit { it[Keys.WAKE_THRESHOLD] = value.coerceIn(0.05f, 0.95f) }
    }

    /**
     * Blocking read for [com.happy.assistant.service.BootReceiver], which has no
     * coroutine scope of its own and gets roughly ten seconds to do its job.
     */
    fun serviceEnabledBlocking(): Boolean = runBlocking { serviceEnabled.first() }

    companion object {
        const val DEFAULT_WAKE_THRESHOLD = 0.5f
        const val DEFAULT_TTS_RATE = 1.05f
        const val DEFAULT_TTS_PITCH = 1.0f
    }
}

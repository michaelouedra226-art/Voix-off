package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.LanguageOption
import com.example.domain.model.SessionUsage
import com.example.domain.model.StudioSettings
import com.example.domain.model.TtsModelOption
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voxaDataStore: DataStore<Preferences> by preferencesDataStore(name = "voxa_studio_prefs")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val DEFAULT_MODEL = stringPreferencesKey("default_model_id")
        val DEFAULT_VOICE = stringPreferencesKey("default_voice")
        val DEFAULT_TONE = stringPreferencesKey("default_tone")
        val DEFAULT_LANGUAGE = stringPreferencesKey("default_language_code")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val COMPLETION_SOUND = booleanPreferencesKey("completion_sound_enabled")
        val PRONUNCIATION_NORM = booleanPreferencesKey("pronunciation_normalization_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACTIVE_PROJECT_ID = stringPreferencesKey("active_project_id")
        val RECENT_TONES = stringPreferencesKey("recent_tones")
        val TOTAL_API_CALLS = intPreferencesKey("total_api_calls")
        val TOTAL_CHARS = intPreferencesKey("total_chars_generated")
    }

    val settingsFlow: Flow<StudioSettings> = context.voxaDataStore.data.map { prefs ->
        StudioSettings(
            defaultModelId = prefs[Keys.DEFAULT_MODEL] ?: TtsModelOption.GEMINI_3_1_FLASH_TTS.modelId,
            defaultVoice = prefs[Keys.DEFAULT_VOICE] ?: "Kore",
            defaultTone = prefs[Keys.DEFAULT_TONE] ?: "naturel",
            defaultLanguageCode = prefs[Keys.DEFAULT_LANGUAGE] ?: LanguageOption.AUTO.code,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
            completionSoundEnabled = prefs[Keys.COMPLETION_SOUND] ?: true,
            pronunciationNormalizationEnabled = prefs[Keys.PRONUNCIATION_NORM] ?: true,
            themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            } ?: ThemeMode.DARK
        )
    }

    val activeProjectIdFlow: Flow<String?> = context.voxaDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PROJECT_ID]
    }

    val recentTonesFlow: Flow<List<String>> = context.voxaDataStore.data.map { prefs ->
        prefs[Keys.RECENT_TONES]
            ?.split("||")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    val cumulativeUsageFlow: Flow<SessionUsage> = context.voxaDataStore.data.map { prefs ->
        SessionUsage(
            apiCallsCount = prefs[Keys.TOTAL_API_CALLS] ?: 0,
            generatedCharactersCount = prefs[Keys.TOTAL_CHARS] ?: 0,
            cachedHitsCount = 0
        )
    }

    suspend fun updateSettings(transform: (StudioSettings) -> StudioSettings) {
        context.voxaDataStore.edit { prefs ->
            val current = StudioSettings(
                defaultModelId = prefs[Keys.DEFAULT_MODEL] ?: TtsModelOption.GEMINI_3_1_FLASH_TTS.modelId,
                defaultVoice = prefs[Keys.DEFAULT_VOICE] ?: "Kore",
                defaultTone = prefs[Keys.DEFAULT_TONE] ?: "naturel",
                defaultLanguageCode = prefs[Keys.DEFAULT_LANGUAGE] ?: LanguageOption.AUTO.code,
                hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
                completionSoundEnabled = prefs[Keys.COMPLETION_SOUND] ?: true,
                pronunciationNormalizationEnabled = prefs[Keys.PRONUNCIATION_NORM] ?: true,
                themeMode = prefs[Keys.THEME_MODE]?.let {
                    runCatching { ThemeMode.valueOf(it) }.getOrNull()
                } ?: ThemeMode.DARK
            )
            val updated = transform(current)
            prefs[Keys.DEFAULT_MODEL] = updated.defaultModelId
            prefs[Keys.DEFAULT_VOICE] = updated.defaultVoice
            prefs[Keys.DEFAULT_TONE] = updated.defaultTone
            prefs[Keys.DEFAULT_LANGUAGE] = updated.defaultLanguageCode
            prefs[Keys.HAPTICS_ENABLED] = updated.hapticsEnabled
            prefs[Keys.COMPLETION_SOUND] = updated.completionSoundEnabled
            prefs[Keys.PRONUNCIATION_NORM] = updated.pronunciationNormalizationEnabled
            prefs[Keys.THEME_MODE] = updated.themeMode.name
        }
    }

    suspend fun setActiveProjectId(projectId: String?) {
        context.voxaDataStore.edit { prefs ->
            if (projectId == null) prefs.remove(Keys.ACTIVE_PROJECT_ID)
            else prefs[Keys.ACTIVE_PROJECT_ID] = projectId
        }
    }

    suspend fun recordRecentTone(tone: String) {
        val cleaned = tone.trim()
        if (cleaned.isEmpty()) return
        context.voxaDataStore.edit { prefs ->
            val existing = prefs[Keys.RECENT_TONES]
                ?.split("||")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.equals(cleaned, ignoreCase = true) }
                ?: emptyList()
            val updated = (listOf(cleaned) + existing).take(8)
            prefs[Keys.RECENT_TONES] = updated.joinToString("||")
        }
    }

    suspend fun incrementUsage(charsGenerated: Int) {
        context.voxaDataStore.edit { prefs ->
            prefs[Keys.TOTAL_API_CALLS] = (prefs[Keys.TOTAL_API_CALLS] ?: 0) + 1
            prefs[Keys.TOTAL_CHARS] = (prefs[Keys.TOTAL_CHARS] ?: 0) + charsGenerated.coerceAtLeast(0)
        }
    }
}

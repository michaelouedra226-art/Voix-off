package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiPoolStrategy
import com.example.domain.model.LanguageOption
import com.example.domain.model.SessionUsage
import com.example.domain.model.StudioSettings
import com.example.domain.model.TtsModelOption
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

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
        val PERSONAL_GEMINI_API_KEY = stringPreferencesKey("personal_gemini_api_key")
        val API_KEY_POOL_JSON = stringPreferencesKey("api_key_pool_json")
        val API_POOL_STRATEGY = stringPreferencesKey("api_pool_strategy")
        val MODEL_FALLBACK_ON_QUOTA = booleanPreferencesKey("model_fallback_on_quota")
        val AUTO_EXPORT_ENABLED = booleanPreferencesKey("auto_export_enabled")
        val AUTO_EXPORT_TREE_URI = stringPreferencesKey("auto_export_tree_uri")
        val AUTO_EXPORT_FOLDER_LABEL = stringPreferencesKey("auto_export_folder_label")
        val AUTO_EXPORT_FORMAT = stringPreferencesKey("auto_export_format")
        val AUTO_EXPORT_VOICE_ONLY = booleanPreferencesKey("auto_export_voice_only")
    }

    val settingsFlow: Flow<StudioSettings> = context.voxaDataStore.data.map { prefs ->
        readSettingsFromPreferences(prefs)
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
            val current = readSettingsFromPreferences(prefs)
            val updated = transform(current)
            prefs[Keys.DEFAULT_MODEL] = updated.defaultModelId
            prefs[Keys.DEFAULT_VOICE] = updated.defaultVoice
            prefs[Keys.DEFAULT_TONE] = updated.defaultTone
            prefs[Keys.DEFAULT_LANGUAGE] = updated.defaultLanguageCode
            prefs[Keys.HAPTICS_ENABLED] = updated.hapticsEnabled
            prefs[Keys.COMPLETION_SOUND] = updated.completionSoundEnabled
            prefs[Keys.PRONUNCIATION_NORM] = updated.pronunciationNormalizationEnabled
            prefs[Keys.THEME_MODE] = updated.themeMode.name
            val cleanedKey = updated.personalGeminiApiKey.trim()
            if (cleanedKey.isEmpty()) {
                prefs.remove(Keys.PERSONAL_GEMINI_API_KEY)
            } else {
                prefs[Keys.PERSONAL_GEMINI_API_KEY] = cleanedKey
            }
            if (updated.apiKeyPool.isEmpty()) {
                prefs.remove(Keys.API_KEY_POOL_JSON)
            } else {
                prefs[Keys.API_KEY_POOL_JSON] = encodeApiKeyPool(updated.apiKeyPool)
            }
            prefs[Keys.API_POOL_STRATEGY] = updated.apiPoolStrategy.name
            prefs[Keys.MODEL_FALLBACK_ON_QUOTA] = updated.modelFallbackOnQuota
            prefs[Keys.AUTO_EXPORT_ENABLED] = updated.autoExportEnabled
            prefs[Keys.AUTO_EXPORT_TREE_URI] = updated.autoExportTreeUri
            prefs[Keys.AUTO_EXPORT_FOLDER_LABEL] = updated.autoExportFolderLabel
            prefs[Keys.AUTO_EXPORT_FORMAT] = updated.autoExportFormat
            prefs[Keys.AUTO_EXPORT_VOICE_ONLY] = updated.autoExportVoiceOnly
        }
    }

    private fun readSettingsFromPreferences(prefs: Preferences): StudioSettings {
        val personalKey = (prefs[Keys.PERSONAL_GEMINI_API_KEY] ?: "").trim()
        val parsedPool = decodeApiKeyPool(prefs[Keys.API_KEY_POOL_JSON])
        val normalizedPool = if (parsedPool.isEmpty() && personalKey.isNotEmpty()) {
            listOf(
                ApiKeyEntry(
                    id = "primary_legacy",
                    label = "Clé #1",
                    apiKey = personalKey,
                    enabled = true
                )
            )
        } else {
            parsedPool
        }
        return StudioSettings(
            defaultModelId = prefs[Keys.DEFAULT_MODEL] ?: TtsModelOption.GEMINI_3_1_FLASH_TTS.modelId,
            defaultVoice = prefs[Keys.DEFAULT_VOICE] ?: "Kore",
            defaultTone = prefs[Keys.DEFAULT_TONE] ?: "naturel",
            defaultLanguageCode = prefs[Keys.DEFAULT_LANGUAGE] ?: LanguageOption.AUTO.code,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
            completionSoundEnabled = prefs[Keys.COMPLETION_SOUND] ?: true,
            pronunciationNormalizationEnabled = prefs[Keys.PRONUNCIATION_NORM] ?: true,
            themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            } ?: ThemeMode.DARK,
            personalGeminiApiKey = personalKey,
            apiKeyPool = normalizedPool,
            apiPoolStrategy = ApiPoolStrategy.fromName(prefs[Keys.API_POOL_STRATEGY]),
            modelFallbackOnQuota = prefs[Keys.MODEL_FALLBACK_ON_QUOTA] ?: true,
            autoExportEnabled = prefs[Keys.AUTO_EXPORT_ENABLED] ?: false,
            autoExportTreeUri = prefs[Keys.AUTO_EXPORT_TREE_URI] ?: "",
            autoExportFolderLabel = prefs[Keys.AUTO_EXPORT_FOLDER_LABEL] ?: "",
            autoExportFormat = prefs[Keys.AUTO_EXPORT_FORMAT] ?: "M4A",
            autoExportVoiceOnly = prefs[Keys.AUTO_EXPORT_VOICE_ONLY] ?: false
        )
    }

    private fun encodeApiKeyPool(pool: List<ApiKeyEntry>): String {
        val arr = JSONArray()
        for (entry in pool) {
            val cleaned = entry.apiKey.trim()
            if (cleaned.isEmpty()) continue
            arr.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("label", entry.label.trim())
                    put("apiKey", cleaned)
                    put("enabled", entry.enabled)
                    if (entry.lastStatusOk != null) put("lastStatusOk", entry.lastStatusOk)
                    if (!entry.lastStatusMessage.isNullOrBlank()) put("lastStatusMessage", entry.lastStatusMessage)
                }
            )
        }
        return arr.toString()
    }

    private fun decodeApiKeyPool(raw: String?): List<ApiKeyEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val key = obj.optString("apiKey", "").trim()
                    if (key.isEmpty()) continue
                    val id = obj.optString("id", "key_$i").ifBlank { "key_$i" }
                    val label = obj.optString("label", "Clé #${i + 1}").ifBlank { "Clé #${i + 1}" }
                    val enabled = obj.optBoolean("enabled", true)
                    val lastStatusOk = if (obj.has("lastStatusOk") && !obj.isNull("lastStatusOk")) {
                        obj.optBoolean("lastStatusOk")
                    } else null
                    val lastStatusMessage = obj.optString("lastStatusMessage", "").takeIf { it.isNotBlank() }
                    add(
                        ApiKeyEntry(
                            id = id,
                            label = label,
                            apiKey = key,
                            enabled = enabled,
                            lastStatusOk = lastStatusOk,
                            lastStatusMessage = lastStatusMessage
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
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

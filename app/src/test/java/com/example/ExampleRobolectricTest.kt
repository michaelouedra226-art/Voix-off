package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.VoxaDatabase
import com.example.data.VoxaRepository
import com.example.domain.model.AudioMetrics
import com.example.domain.model.StudioMode
import com.example.viewmodel.StudioUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.ai.GeminiDirectRestClient
import com.example.ai.GeminiRestHttpException
import com.example.ai.VoxaAiService
import com.example.ai.configureAppCheck
import com.example.ai.ensureFirebaseAppInitialized
import com.example.ai.isFirebaseAppReady
import com.example.data.SettingsDataStore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `firebase options and default FirebaseApp initialize cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val options = FirebaseOptions.fromResource(context)
        assertTrue(
            "google_app_id must be present in merged resources (check app/google-services.json)",
            options != null && options.applicationId.isNotBlank()
        )
        assertTrue(ensureFirebaseAppInitialized(context))
        configureAppCheck(context, null)
        assertTrue(isFirebaseAppReady(context))
        assertTrue(FirebaseApp.getApps(context).isNotEmpty())
    }

    @Test
    fun `app name matches Voxa Studio`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Voxa Studio", appName)
    }

    @Test
    fun `initial studio state is strictly empty without demo data`() {
        val initial = StudioUiState()
        assertEquals(StudioMode.BRIEF, initial.mode)
        assertTrue(initial.briefDescription.isEmpty())
        assertTrue(initial.briefRawText.isEmpty())
        assertTrue(initial.expertScript.isEmpty())
        assertTrue(initial.projects.isEmpty())
        assertTrue(initial.scriptVersions.isEmpty())
        assertTrue(initial.generations.isEmpty())
        assertNull(initial.activeGenerationId)
        assertNull(initial.briefPlan)
        assertNull(initial.audioMetrics)
        assertFalse(initial.hasFinalAudio)
    }

    @Test
    fun `generation records persist and delete cleanly in repository`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, VoxaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = VoxaRepository(context, db.voxaDao())
            val projectId = "proj-test-1"
            val sampleBuf = FloatArray(2400) { 0.1f }
            val metrics = AudioMetrics(
                durationSec = 0.1f,
                peakDb = -20f,
                integratedLufs = -16f,
                isClippingRisk = false,
                isTooQuiet = false,
                waveformPeaks = listOf(0.2f, 0.4f, 0.3f),
                segmentTimings = emptyList()
            )

            val saved = repo.saveGenerationRecord(
                projectId = projectId,
                title = "Spot radio",
                sourceMode = StudioMode.BRIEF,
                annotatedScript = "[voix: Kore] Bonjour",
                directorExplanation = "Ton chaleureux",
                selectedVoice = "Kore",
                modelId = "gemini-3.1-flash-tts-preview",
                mixedPcm = sampleBuf,
                voiceOnlyPcm = sampleBuf,
                metrics = metrics,
                ambiencesSummary = "pluie douce (25%)",
                segmentCount = 1
            )

            assertTrue(File(saved.mixedPcmPath).exists())
            assertTrue(File(saved.voiceOnlyPcmPath).exists())

            val observed = repo.observeGenerations(projectId).first()
            assertEquals(1, observed.size)
            assertEquals("Spot radio", observed.first().title)
            assertEquals(3, observed.first().waveformPeaks.size)

            repo.deleteGeneration(saved)
            assertFalse(File(saved.mixedPcmPath).exists())
            assertTrue(repo.observeGenerations(projectId).first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `direct rest client builds valid tts and structured payloads and parses responses`() {
        val ttsBody = GeminiDirectRestClient.buildTtsRequestBody(
            actorPrompt = "Bonjour studio",
            voiceName = "Kore",
            languageCode = "fr-FR"
        )
        val genCfg = ttsBody.getJSONObject("generationConfig")
        assertEquals("AUDIO", genCfg.getJSONArray("responseModalities").getString(0))
        val speechCfg = genCfg.getJSONObject("speechConfig")
        assertEquals("fr-FR", speechCfg.getString("languageCode"))
        assertEquals(
            "Kore",
            speechCfg.getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName")
        )

        val autoLangBody = GeminiDirectRestClient.buildTtsRequestBody(
            actorPrompt = "Hello",
            voiceName = "Puck",
            languageCode = "auto"
        )
        assertFalse(
            autoLangBody.getJSONObject("generationConfig").getJSONObject("speechConfig").has("languageCode")
        )

        val fakeAudioJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "inlineData": { "mimeType": "audio/L16;rate=24000", "data": "AQIDBA==" } }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        assertEquals("AQIDBA==", GeminiDirectRestClient.extractAudioBase64FromResponse(fakeAudioJson))

        val fakeTextJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [ { "text": "{\"annotatedScript\":\"[voix: Kore] Bonjour\"}" } ]
                  }
                }
              ]
            }
        """.trimIndent()
        assertTrue(GeminiDirectRestClient.extractTextFromResponse(fakeTextJson).contains("annotatedScript"))

        val wavBytes = ByteArray(50)
        wavBytes[0] = 'R'.code.toByte(); wavBytes[1] = 'I'.code.toByte()
        wavBytes[2] = 'F'.code.toByte(); wavBytes[3] = 'F'.code.toByte()
        wavBytes[8] = 'W'.code.toByte(); wavBytes[9] = 'A'.code.toByte()
        wavBytes[10] = 'V'.code.toByte(); wavBytes[11] = 'E'.code.toByte()
        assertEquals(6, GeminiDirectRestClient.stripWavHeaderIfPresent(wavBytes).size)
    }

    @Test
    fun `personal gemini api key persists trimmed and rest errors translate without app check confusion`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        store.updateSettings { it.copy(personalGeminiApiKey = "  AIzaTestKey123  ") }
        assertEquals("AIzaTestKey123", store.settingsFlow.first().personalGeminiApiKey)

        store.updateSettings { it.copy(personalGeminiApiKey = "") }
        assertEquals("", store.settingsFlow.first().personalGeminiApiKey)

        val service = VoxaAiService(context)
        val parsedErr = GeminiDirectRestClient.parseErrorResponse(
            400,
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        )
        val readable = service.translateAiError(parsedErr, usingPersonalKey = true)
        assertTrue(readable.contains("Clé API Gemini", ignoreCase = true))
        assertFalse(readable.contains("App Check", ignoreCase = true))
    }
}

package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ApiPoolExhaustedException
import com.example.ai.GeminiDirectRestClient
import com.example.ai.GeminiRestHttpException
import com.example.ai.VoxaAiService
import com.example.ai.configureAppCheck
import com.example.ai.ensureFirebaseAppInitialized
import com.example.ai.isFirebaseAppReady
import com.example.audio.AudioExportFormat
import com.example.data.SettingsDataStore
import com.example.data.VoxaDatabase
import com.example.data.VoxaRepository
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiPoolStrategy
import com.example.domain.model.AudioMetrics
import com.example.domain.model.StudioMode
import com.example.viewmodel.StudioUiState
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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
import java.io.File
import java.util.zip.ZipInputStream

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
        assertTrue(initial.allGenerations.isEmpty())
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
    fun `personal gemini api key and auto export settings persist cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        store.updateSettings {
            it.copy(
                personalGeminiApiKey = "  AIzaTestKey123  ",
                autoExportEnabled = true,
                autoExportTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AVoxaExports",
                autoExportFolderLabel = "VoxaExports",
                autoExportFormat = "WAV",
                autoExportVoiceOnly = true
            )
        }
        val persisted = store.settingsFlow.first()
        assertEquals("AIzaTestKey123", persisted.personalGeminiApiKey)
        assertTrue(persisted.autoExportEnabled)
        assertEquals("VoxaExports", persisted.autoExportFolderLabel)
        assertEquals("WAV", persisted.autoExportFormat)
        assertTrue(persisted.autoExportVoiceOnly)

        store.updateSettings { it.copy(personalGeminiApiKey = "", autoExportEnabled = false) }
        assertEquals("", store.settingsFlow.first().personalGeminiApiKey)
        assertFalse(store.settingsFlow.first().autoExportEnabled)

        val service = VoxaAiService(context)
        val parsedErr = GeminiDirectRestClient.parseErrorResponse(
            400,
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        )
        val readable = service.translateAiError(parsedErr, usingPersonalKey = true)
        assertTrue(readable.contains("Clé API Gemini", ignoreCase = true))
        assertFalse(readable.contains("App Check", ignoreCase = true))
    }

    @Test
    fun `global allGenerationsFlow aggregates across projects newest first and reads export buffers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, VoxaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = VoxaRepository(context, db.voxaDao())
            val metrics = AudioMetrics(
                durationSec = 0.2f,
                peakDb = -12f,
                integratedLufs = -16f,
                isClippingRisk = false,
                isTooQuiet = false,
                waveformPeaks = listOf(0.3f, 0.5f),
                segmentTimings = emptyList()
            )
            val mixedA = FloatArray(1200) { 0.25f }
            val voiceA = FloatArray(1200) { 0.12f }
            val genA = repo.saveGenerationRecord(
                projectId = "proj-alpha",
                title = "Intro Podcast",
                sourceMode = StudioMode.BRIEF,
                annotatedScript = "[voix: Kore] Bienvenue",
                directorExplanation = "Ton posé",
                selectedVoice = "Kore",
                modelId = "gemini-3.1-flash-tts-preview",
                mixedPcm = mixedA,
                voiceOnlyPcm = voiceA,
                metrics = metrics,
                ambiencesSummary = "studio calme (15%)",
                segmentCount = 1
            )

            // Ensure strictly newer timestamp for second generation in a different project
            Thread.sleep(5)
            val mixedB = FloatArray(1200) { 0.45f }
            val voiceB = FloatArray(1200) { 0.20f }
            val genB = repo.saveGenerationRecord(
                projectId = "proj-beta",
                title = "Spot Promo",
                sourceMode = StudioMode.EXPERT,
                annotatedScript = "[voix: Puck] Offre spéciale",
                directorExplanation = "",
                selectedVoice = "Puck",
                modelId = "gemini-3.1-flash-tts-preview",
                mixedPcm = mixedB,
                voiceOnlyPcm = voiceB,
                metrics = metrics,
                ambiencesSummary = "",
                segmentCount = 1
            )

            val allGens = repo.allGenerationsFlow.first()
            assertEquals(2, allGens.size)
            assertEquals(genB.id, allGens[0].id)
            assertEquals(genA.id, allGens[1].id)

            val loadedMixedA = repo.readGenerationAudioBuffer(genA, voiceOnly = false)
            val loadedVoiceA = repo.readGenerationAudioBuffer(genA, voiceOnly = true)
            assertEquals(1200, loadedMixedA.size)
            assertEquals(1200, loadedVoiceA.size)
            assertTrue(loadedMixedA[0] > 0.2f)
            assertTrue(loadedVoiceA[0] < 0.15f)

            // Test favorites, renaming, tags and batch ZIP export
            repo.setGenerationFavorite(genA.id, true)
            repo.renameGeneration(genA.id, "Intro Podcast V2")
            repo.updateGenerationTags(genA.id, listOf("Podcast", "Pub"))

            val updatedA = repo.allGenerationsFlow.first().first { it.id == genA.id }
            assertTrue(updatedA.isFavorite)
            assertEquals("Intro Podcast V2", updatedA.title)
            assertEquals(listOf("Podcast", "Pub"), updatedA.tags)

            val zipFile = File.createTempFile("voxa_batch_test", ".zip")
            try {
                val exportedCount = repo.exportBatchZipToUri(
                    records = listOf(updatedA, genB),
                    format = AudioExportFormat.WAV,
                    voiceOnly = false,
                    destinationUri = Uri.fromFile(zipFile)
                )
                assertEquals(2, exportedCount)

                val zipEntries = mutableListOf<String>()
                ZipInputStream(zipFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        zipEntries.add(entry.name)
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                assertTrue(zipEntries.any { it.endsWith(".wav") })
                assertTrue(zipEntries.any { it.endsWith("_script.txt") })
                assertEquals(4, zipEntries.size)
            } finally {
                zipFile.delete()
            }

            // Clean up generated test PCM files
            repo.deleteGeneration(genA)
            repo.deleteGeneration(genB)
            assertTrue(repo.allGenerationsFlow.first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `room migration 2 to 3 preserves existing data and applies defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("migration_test_2_3.db")
        if (dbFile.exists()) dbFile.delete()

        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `projects` (
                        `id` TEXT NOT NULL, `title` TEXT NOT NULL, `mode` TEXT NOT NULL,
                        `briefDescription` TEXT NOT NULL, `briefRawText` TEXT NOT NULL,
                        `expertScript` TEXT NOT NULL, `defaultVoice` TEXT NOT NULL,
                        `ttsModelId` TEXT NOT NULL, `languageCode` TEXT NOT NULL,
                        `voiceVolume` REAL NOT NULL, `ambienceVolume` REAL NOT NULL,
                        `duckingAmountDb` REAL NOT NULL, `fadeDurationSec` REAL NOT NULL,
                        `crossfadeMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `generations` (
                        `id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `title` TEXT NOT NULL,
                        `sourceMode` TEXT NOT NULL, `annotatedScript` TEXT NOT NULL,
                        `directorExplanation` TEXT NOT NULL, `selectedVoice` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL, `mixedPcmPath` TEXT NOT NULL,
                        `voiceOnlyPcmPath` TEXT NOT NULL, `durationSec` REAL NOT NULL,
                        `peakDb` REAL NOT NULL, `integratedLufs` REAL NOT NULL,
                        `waveformPeaksCsv` TEXT NOT NULL, `ambiencesSummary` TEXT NOT NULL,
                        `segmentCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO projects VALUES ('p1','Projet v2','BRIEF','','','','Kore','m1','fr-FR',1.0,1.0,10.0,1.5,80,1000)"
                )
                db.execSQL(
                    "INSERT INTO generations VALUES ('g1','p1','Gen v2','BRIEF','Bonjour','','Kore','m1','/tmp/m.pcm','/tmp/v.pcm',1.0,-3.0,-16.0,'0.2','',1,2000)"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(callback)
                .build()
        )
        try {
            val writableDb = helper.writableDatabase
            VoxaDatabase.MIGRATION_2_3.migrate(writableDb)

            writableDb.query("SELECT isFavorite, tagsCsv, title FROM generations WHERE id = 'g1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertEquals("", c.getString(1))
                assertEquals("Gen v2", c.getString(2))
            }
            writableDb.query("SELECT targetLufs, reverbPreset, playbackSpeed FROM projects WHERE id = 'p1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(-16.0f, c.getFloat(0), 0.01f)
                assertEquals("NONE", c.getString(1))
                assertEquals(1.0f, c.getFloat(2), 0.01f)
            }
        } finally {
            helper.close()
            dbFile.delete()
        }
    }

    @Test
    fun `multi-api pool settings persist and unwrap pool errors cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        val entries = listOf(
            ApiKeyEntry(id = "k1", label = "Compte Principal", apiKey = "AIzaPoolKeyAlpha1111", enabled = true, lastStatusOk = true, lastStatusMessage = "Valide"),
            ApiKeyEntry(id = "k2", label = "Compte Secours", apiKey = "AIzaPoolKeyBeta2222", enabled = true, lastStatusOk = false, lastStatusMessage = "429"),
            ApiKeyEntry(id = "k3", label = "Compte Pause", apiKey = "AIzaPoolKeyGamma3333", enabled = false)
        )
        store.updateSettings {
            it.copy(
                personalGeminiApiKey = "AIzaPoolKeyAlpha1111",
                apiKeyPool = entries,
                apiPoolStrategy = ApiPoolStrategy.ROUND_ROBIN,
                modelFallbackOnQuota = false
            )
        }

        val loaded = store.settingsFlow.first()
        assertEquals(3, loaded.apiKeyPool.size)
        assertEquals("Compte Principal", loaded.apiKeyPool[0].label)
        assertEquals(true, loaded.apiKeyPool[0].lastStatusOk)
        assertEquals(ApiPoolStrategy.ROUND_ROBIN, loaded.apiPoolStrategy)
        assertFalse(loaded.modelFallbackOnQuota)
        assertEquals(listOf("AIzaPoolKeyAlpha1111", "AIzaPoolKeyBeta2222"), loaded.activeApiKeys())

        // Clean up pool settings
        store.updateSettings {
            it.copy(
                personalGeminiApiKey = "",
                apiKeyPool = emptyList(),
                apiPoolStrategy = ApiPoolStrategy.PARALLEL_BOOST,
                modelFallbackOnQuota = true
            )
        }

        val service = VoxaAiService(context)
        val exhaustedErr = ApiPoolExhaustedException(
            attemptedKeysCount = 3,
            lastError = GeminiRestHttpException(429, "RESOURCE_EXHAUSTED", "Quota exceeded")
        )
        val readable = service.translateAiError(exhaustedErr, usingPersonalKey = true)
        assertTrue(readable.contains("3 clés"))
        assertTrue(readable.contains("429"))
    }
}

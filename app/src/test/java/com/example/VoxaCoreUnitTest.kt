package com.example

import com.example.ai.ApiPoolCoordinator
import com.example.ai.ApiPoolExhaustedException
import com.example.ai.GeminiRestHttpException
import com.example.audio.AmbienceSynthesizer
import com.example.audio.AudioExporter
import com.example.audio.AudioMixerEngine
import com.example.audio.PcmUtils
import com.example.domain.model.AmbienceCatalog
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiPoolStrategy
import com.example.domain.model.DefectType
import com.example.domain.model.DialogueCompiler
import com.example.domain.model.DialogueLine
import com.example.domain.model.MixConfig
import com.example.domain.model.PronunciationEntry
import com.example.domain.model.ReverbPreset
import com.example.domain.model.SegmentTimingRange
import com.example.domain.model.SpeechSpeed
import com.example.domain.model.StudioPresetCatalog
import com.example.domain.model.StudioSettings
import com.example.domain.model.TimelineEvent
import com.example.domain.model.VoiceCatalog
import com.example.domain.script.BracketParser
import com.example.domain.script.LanguageDetector
import com.example.domain.script.PronunciationNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

class VoxaCoreUnitTest {

    @Test
    fun `bracket parser handles persistent directives, shortcuts, pauses and mid-sentence splits`() {
        val script = "[voix: Charon] [ton: posé] [ambiance: pluie douce, 25%] Bonjour [pause: 1.5s] [en chuchotant] monde. [ambiance: stop] Fin."
        val res = BracketParser.parse(script)

        assertTrue("Aucune erreur attendue", res.diagnostics.isEmpty())
        assertEquals(3, res.segments.size)

        val s1 = res.segments[0]
        assertEquals("Bonjour", s1.text)
        assertEquals("Charon", s1.voice)
        assertEquals("posé", s1.tone)
        assertEquals("pluie douce", s1.activeAmbience)

        val s2 = res.segments[1]
        assertEquals("monde.", s2.text)
        assertEquals("Charon", s2.voice)
        assertEquals("en chuchotant", s2.tone)
        assertEquals("pluie douce", s2.activeAmbience)

        val s3 = res.segments[2]
        assertEquals("Fin.", s3.text)
        assertNull(s3.activeAmbience)

        val pauses = res.events.filterIsInstance<TimelineEvent.PauseEvent>()
        assertEquals(1, pauses.size)
        assertEquals(1.5f, pauses[0].durationSec, 0.01f)
    }

    @Test
    fun `bracket parser splits segments longer than 900 chars at sentence boundaries`() {
        val sentence = "Voici une phrase de test suffisamment longue pour remplir un segment. "
        val longText = sentence.repeat(20) // ~1400 chars
        val res = BracketParser.parse(longText)

        assertTrue(res.segments.size >= 2)
        assertTrue(res.segments.all { it.text.length <= BracketParser.MAX_SEGMENT_CHARS })
    }

    @Test
    fun `bracket parser safely diagnoses malformed brackets without crashing`() {
        val malformed = "[voix: Inconnue] [pause: abc] [ton: unclosed"
        val res = BracketParser.parse(malformed)

        assertEquals(3, res.diagnostics.count { it.isError })
        assertEquals(0, res.segments.size)
    }

    @Test
    fun `bracket parser detects autocomplete context after open bracket`() {
        val text = "Bonjour [voix: Ko"
        val ctx = BracketParser.detectAutocomplete(
            script = text,
            cursorPosition = text.length,
            recentTones = emptyList(),
            customAmbienceNames = emptyList()
        )
        assertNotNull(ctx)
        assertEquals("voix", ctx?.commandKey)
        assertTrue(ctx!!.suggestions.any { it.displayLabel == "Kore" })
    }

    @Test
    fun `pronunciation normalizer expands numbers, currencies, dates, times and dictionary`() {
        val dict = listOf(PronunciationEntry("1", "Voxa", "Voksa"))
        val input = "Voxa coûte 15 € à 14h30 le 01/05/2026 avec 25% de remise."
        val out = PronunciationNormalizer.applyAll(
            text = input,
            languageCode = "fr-FR",
            normalizeStandardTokens = true,
            customDictionary = dict
        )
        assertTrue(out.contains("Voksa"))
        assertTrue(out.contains("quinze euros"))
        assertTrue(out.contains("quatorze heures trente"))
        assertTrue(out.contains("premier mai deux mille vingt-six"))
        assertTrue(out.contains("vingt-cinq pour cent"))
    }

    @Test
    fun `language detector identifies primary language and mixed languages`() {
        val mixed = "Bonjour le monde dans la maison pour un test avec nous. However the quick brown fox and this test are not from here."
        val res = LanguageDetector.detect(mixed)
        assertTrue(res.hasMixedLanguages)
        assertTrue(res.detectedLanguagesFr.size >= 2)
    }

    @Test
    fun `all 14 ambience presets synthesize valid finite loop buffers`() {
        for (preset in AmbienceCatalog.presets) {
            val buf = AmbienceSynthesizer.getOrGenerateLoop(preset.name)
            assertEquals(PcmUtils.SAMPLE_RATE * 10, buf.size)
            assertFalse("NaN détecté pour ${preset.name}", buf.any { it.isNaN() || it.isInfinite() })
        }
    }

    @Test
    fun `audio mixer normalizes output and respects -1 dBFS ceiling`() {
        val sr = PcmUtils.SAMPLE_RATE
        val voiceSamples = FloatArray(sr) { i ->
            0.35f * sin(2.0 * Math.PI * 220.0 * i / sr).toFloat()
        }
        val tempFile = File.createTempFile("voxa_test_seg", ".pcm")
        try {
            PcmUtils.writeFloatPcmToFile(tempFile, voiceSamples)
            val script = "[ambiance: pluie douce, 30%] Bonjour [pause: 0.5s] Test."
            val parsed = BracketParser.parse(script)
            val pcmMap = parsed.segments.associate { it.id to tempFile.absolutePath }

            val mixed = AudioMixerEngine.mixTimeline(
                events = parsed.events,
                segmentPcmPaths = pcmMap,
                mixConfig = MixConfig(),
                customAmbiencePaths = emptyMap()
            )
            assertTrue(mixed.mixedPcm.isNotEmpty())
            assertTrue(mixed.voiceOnlyPcm.isNotEmpty())
            assertTrue("Crête doit rester <= -0.9 dB", mixed.metrics.peakDb <= -0.8f)
            assertEquals(2, mixed.metrics.segmentTimings.size)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `audio mixer post-processing applies trim, reverb, speed and target loudness cleanly`() {
        val sr = PcmUtils.SAMPLE_RATE
        val voiceSamples = FloatArray(sr * 2) { i ->
            0.3f * sin(2.0 * Math.PI * 260.0 * i / sr).toFloat()
        }
        val tempFile = File.createTempFile("voxa_test_dsp", ".pcm")
        try {
            PcmUtils.writeFloatPcmToFile(tempFile, voiceSamples)
            val parsed = BracketParser.parse("Premier segment de test complet.")
            val pcmMap = parsed.segments.associate { it.id to tempFile.absolutePath }

            val base = AudioMixerEngine.mixTimeline(
                events = parsed.events,
                segmentPcmPaths = pcmMap,
                mixConfig = MixConfig(targetLufs = -16.0f),
                customAmbiencePaths = emptyMap()
            )
            val processed = AudioMixerEngine.mixTimeline(
                events = parsed.events,
                segmentPcmPaths = pcmMap,
                mixConfig = MixConfig(
                    trimStartSec = 0.25f,
                    trimEndSec = 0.25f,
                    reverbPreset = ReverbPreset.STUDIO,
                    playbackSpeed = 1.15f,
                    targetLufs = -14.0f
                ),
                customAmbiencePaths = emptyMap()
            )

            assertTrue(processed.mixedPcm.isNotEmpty())
            assertFalse(processed.mixedPcm.any { it.isNaN() || it.isInfinite() })
            assertTrue(processed.metrics.durationSec < base.metrics.durationSec)
            assertTrue(processed.metrics.peakDb <= -0.8f)
            assertTrue(processed.metrics.segmentTimings.isNotEmpty())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `dialogue compiler round-trips cleanly through bracket parser`() {
        val lines = listOf(
            DialogueLine(
                id = "l1",
                voiceName = "Kore",
                tone = "chaleureux",
                speed = SpeechSpeed.NORMAL,
                pauseBeforeSec = 0.0f,
                text = "Bienvenue dans notre studio."
            ),
            DialogueLine(
                id = "l2",
                voiceName = "Charon",
                tone = "posé",
                speed = SpeechSpeed.LENT,
                pauseBeforeSec = 0.8f,
                text = "Merci pour cette invitation."
            )
        )
        val compiled = DialogueCompiler.compileScript(
            lines = lines,
            ambienceName = "studio calme",
            ambienceVolumePercent = 25
        )
        val parsed = BracketParser.parse(compiled)

        assertTrue("Aucune erreur de syntaxe attendue", parsed.diagnostics.isEmpty())
        assertEquals(2, parsed.segments.size)
        assertEquals("Kore", parsed.segments[0].voice)
        assertEquals("chaleureux", parsed.segments[0].tone)
        assertEquals("studio calme", parsed.segments[0].activeAmbience)
        assertEquals("Charon", parsed.segments[1].voice)
        assertEquals("posé", parsed.segments[1].tone)
        assertEquals(SpeechSpeed.LENT, parsed.segments[1].speed)
        assertEquals(1, parsed.events.filterIsInstance<TimelineEvent.PauseEvent>().size)
    }

    @Test
    fun `studio preset catalog provides valid presets with known voices`() {
        val presets = StudioPresetCatalog.presets
        assertTrue(presets.size >= 5)
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        for (preset in presets) {
            assertTrue(preset.titleFr.isNotBlank())
            assertTrue(preset.briefDescription.isNotBlank())
            assertNotNull(VoiceCatalog.findByName(preset.suggestedVoice))
        }
    }

    @Test
    fun `audio exporter builds valid srt subtitle blocks`() {
        val parsed = BracketParser.parse("Première réplique. [pause: 0.5s] Deuxième réplique.")
        val timings = listOf(
            SegmentTimingRange(parsed.segments[0].id, startSec = 0.0f, endSec = 1.45f),
            SegmentTimingRange(parsed.segments[1].id, startSec = 1.95f, endSec = 3.5f)
        )
        val srt = AudioExporter.buildSrtContent(parsed.segments, timings, totalDurationSec = 3.5f)

        assertTrue(srt.contains("1\n00:00:00,000 --> 00:00:01,450"))
        assertTrue(srt.contains("Première réplique."))
        assertTrue(srt.contains("2\n00:00:01,950 --> 00:00:03,500"))
        assertTrue(srt.contains("Deuxième réplique."))
    }

    @Test
    fun `defect detector flags empty and short audio`() {
        val silent = FloatArray(PcmUtils.SAMPLE_RATE) { 0f }
        assertEquals(DefectType.EMPTY_AUDIO, PcmUtils.detectDefect(silent))

        val tooShort = FloatArray(2000) { 0.4f }
        assertEquals(DefectType.TOO_SHORT, PcmUtils.detectDefect(tooShort))
    }

    @Test
    fun `bracket parser handles scripts exceeding 20000 chars without truncation`() {
        val sentence = "Voici une phrase de test suffisamment longue pour dépasser vingt mille caractères. "
        val longScript = sentence.repeat(300) // ~24 900 chars
        val res = BracketParser.parse(longScript)

        assertEquals(longScript.length, res.characterCount)
        assertTrue(res.characterCount > 20_000)
        assertTrue(res.segments.isNotEmpty())
        assertTrue(res.segments.all { it.text.length <= BracketParser.MAX_SEGMENT_CHARS })
    }

    @Test
    fun `studio settings multi-api pool resolves active keys and effective concurrency cleanly`() {
        val legacyOnly = StudioSettings(personalGeminiApiKey = " AIzaLegacy ")
        assertEquals(listOf("AIzaLegacy"), legacyOnly.activeApiKeys())
        assertEquals(1, legacyOnly.effectiveConcurrency())

        val poolSettings = StudioSettings(
            personalGeminiApiKey = "AIzaKey1",
            apiKeyPool = listOf(
                ApiKeyEntry("1", "Compte 1", "AIzaKey1", enabled = false),
                ApiKeyEntry("2", "Compte 2", "AIzaKey2", enabled = true),
                ApiKeyEntry("3", "Compte 3", "AIzaKey3", enabled = true),
                ApiKeyEntry("4", "Compte 4", "AIzaKey4", enabled = true),
                ApiKeyEntry("5", "Compte 5", "AIzaKey5", enabled = true),
                ApiKeyEntry("6", "Compte 6", "AIzaKey6", enabled = true)
            ),
            apiPoolStrategy = ApiPoolStrategy.PARALLEL_BOOST
        )
        // Clé #1 désactivée dans le pool ne doit pas être réactivée par personalGeminiApiKey
        assertEquals(
            listOf("AIzaKey2", "AIzaKey3", "AIzaKey4", "AIzaKey5", "AIzaKey6"),
            poolSettings.activeApiKeys()
        )
        assertEquals(4, poolSettings.effectiveConcurrency())

        val roundRobinSettings = poolSettings.copy(apiPoolStrategy = ApiPoolStrategy.ROUND_ROBIN)
        assertEquals(1, roundRobinSettings.effectiveConcurrency())
    }

    @Test
    fun `api pool coordinator rotates keys, cools down 429 keys and fails over automatically`() = runBlocking {
        ApiPoolCoordinator.clearAll()
        val keys = listOf("AIza_A", "AIza_B", "AIza_C")

        val firstOrder = ApiPoolCoordinator.selectOrderedCandidates(keys, ApiPoolStrategy.ROUND_ROBIN, nowMs = 1_000L)
        val secondOrder = ApiPoolCoordinator.selectOrderedCandidates(keys, ApiPoolStrategy.ROUND_ROBIN, nowMs = 1_000L)
        assertEquals(listOf("AIza_A", "AIza_B", "AIza_C"), firstOrder)
        assertEquals(listOf("AIza_B", "AIza_C", "AIza_A"), secondOrder)

        ApiPoolCoordinator.markRateLimited("AIza_A", durationMs = 30_000L, nowMs = 1_000L)
        assertTrue(ApiPoolCoordinator.isInCooldown("AIza_A", nowMs = 10_000L))
        assertFalse(ApiPoolCoordinator.isInCooldown("AIza_A", nowMs = 35_000L))

        val prioritizedOrder = ApiPoolCoordinator.selectOrderedCandidates(keys, ApiPoolStrategy.FAILOVER, nowMs = 10_000L)
        assertEquals("AIza_B", prioritizedOrder.first())
        assertEquals("AIza_A", prioritizedOrder.last())

        // Execute across pool: AIza_B raises 429, AIza_C succeeds
        ApiPoolCoordinator.clearAll()
        val tried = mutableListOf<String>()
        val result = ApiPoolCoordinator.executeAcrossPool(listOf("AIza_1", "AIza_2"), ApiPoolStrategy.FAILOVER) { key ->
            tried.add(key)
            if (key == "AIza_1") {
                throw GeminiRestHttpException(429, "RESOURCE_EXHAUSTED", "Quota exceeded")
            }
            "OK:$key"
        }
        assertEquals("OK:AIza_2", result)
        assertEquals(listOf("AIza_1", "AIza_2"), tried)
        assertTrue(ApiPoolCoordinator.isInCooldown("AIza_1"))

        // If every key fails, throws ApiPoolExhaustedException
        ApiPoolCoordinator.clearAll()
        var exhausted: ApiPoolExhaustedException? = null
        try {
            ApiPoolCoordinator.executeAcrossPool(listOf("AIza_X", "AIza_Y"), ApiPoolStrategy.ROUND_ROBIN) {
                throw GeminiRestHttpException(429, "RESOURCE_EXHAUSTED", "All exhausted")
            }
        } catch (e: ApiPoolExhaustedException) {
            exhausted = e
        }
        assertNotNull(exhausted)
        assertEquals(2, exhausted?.attemptedKeysCount)
    }
}

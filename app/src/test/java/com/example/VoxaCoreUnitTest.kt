package com.example

import com.example.audio.AmbienceSynthesizer
import com.example.audio.AudioMixerEngine
import com.example.audio.PcmUtils
import com.example.domain.model.AmbienceCatalog
import com.example.domain.model.DefectType
import com.example.domain.model.MixConfig
import com.example.domain.model.PronunciationEntry
import com.example.domain.model.SpeechSpeed
import com.example.domain.model.TimelineEvent
import com.example.domain.script.BracketParser
import com.example.domain.script.LanguageDetector
import com.example.domain.script.PronunciationNormalizer
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
}

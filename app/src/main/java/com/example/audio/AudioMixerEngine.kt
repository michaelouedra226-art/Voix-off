package com.example.audio

import com.example.domain.model.AudioMetrics
import com.example.domain.model.MixConfig
import com.example.domain.model.SegmentTimingRange
import com.example.domain.model.TimelineEvent
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class MixResult(
    val mixedPcm: FloatArray,
    val voiceOnlyPcm: FloatArray,
    val metrics: AudioMetrics
)

/**
 * Moteur de mixage multi-piste hors-ligne :
 * - Retrait des silences parasites en début/fin de segment
 * - Fondu enchaîné de 60 à 100 ms entre segments
 * - Filtre passe-haut à 80 Hz et compresseur doux sur la piste voix
 * - Piste d'ambiance séparée avec fondus entrée/sortie et ducking automatique
 *   (baisse ~10 dB quand la voix parle, attaque 80 ms, relâchement 600 ms)
 * - Normalisation finale vers -16 LUFS avec limiteur de crête à -1 dBFS
 */
object AudioMixerEngine {

    private const val SR = PcmUtils.SAMPLE_RATE

    fun mixTimeline(
        events: List<TimelineEvent>,
        segmentPcmPaths: Map<String, String>,
        mixConfig: MixConfig,
        customAmbiencePaths: Map<String, String>
    ): MixResult {
        val crossfadeSamples = (SR * (mixConfig.crossfadeMs.coerceIn(60, 100) / 1000f)).toInt()

        // 1. Préparer les morceaux de la piste voix + tracer la chronologie exacte et les changements d'ambiance
        val voiceTimelineChunks = mutableListOf<FloatArray>()
        val segmentTimings = mutableListOf<SegmentTimingRange>()
        val ambienceChanges = mutableListOf<Triple<Int, String?, Float>>() // samplePos, nameOrNull, volume

        var currentSampleCursor = 0
        var lastWasVoiceSegment = false

        for (event in events) {
            when (event) {
                is TimelineEvent.AmbienceEvent -> {
                    ambienceChanges.add(Triple(currentSampleCursor, event.ambienceName, event.volume))
                }

                is TimelineEvent.PauseEvent -> {
                    val pauseSamples = (SR * event.durationSec).toInt().coerceAtLeast(1)
                    voiceTimelineChunks.add(FloatArray(pauseSamples))
                    currentSampleCursor += pauseSamples
                    lastWasVoiceSegment = false
                }

                is TimelineEvent.VoiceSegment -> {
                    val path = segmentPcmPaths[event.id] ?: continue
                    val raw = PcmUtils.readFloatPcmFromFile(File(path))
                    if (raw.isEmpty()) continue
                    val trimmed = PcmUtils.trimSilence(raw, SR)
                    if (trimmed.isEmpty()) continue

                    val overlap = if (lastWasVoiceSegment && trimmed.size > crossfadeSamples * 2) {
                        crossfadeSamples
                    } else 0

                    val startSample = (currentSampleCursor - overlap).coerceAtLeast(0)
                    val endSample = startSample + trimmed.size
                    segmentTimings.add(
                        SegmentTimingRange(
                            segmentId = event.id,
                            startSec = startSample.toFloat() / SR,
                            endSec = endSample.toFloat() / SR
                        )
                    )

                    voiceTimelineChunks.add(
                        if (overlap > 0) applyCrossfadePlaceholder(trimmed, overlap) else trimmed
                    )
                    // Si overlap > 0, on marquera l'assemblage avec chevauchement ci-dessous
                    currentSampleCursor = endSample
                    lastWasVoiceSegment = true
                }
            }
        }

        val totalSamples = max(currentSampleCursor, 1)
        val voiceTrack = FloatArray(totalSamples)

        // Assemblage précis avec overlap-add sur les frontières segment->segment
        var writePos = 0
        var prevWasVoice = false
        for (event in events) {
            when (event) {
                is TimelineEvent.AmbienceEvent -> Unit
                is TimelineEvent.PauseEvent -> {
                    val pauseSamples = (SR * event.durationSec).toInt().coerceAtLeast(1)
                    writePos += pauseSamples
                    prevWasVoice = false
                }
                is TimelineEvent.VoiceSegment -> {
                    val path = segmentPcmPaths[event.id] ?: continue
                    val raw = PcmUtils.readFloatPcmFromFile(File(path))
                    if (raw.isEmpty()) continue
                    val trimmed = PcmUtils.trimSilence(raw, SR)
                    if (trimmed.isEmpty()) continue

                    val overlap = if (prevWasVoice && trimmed.size > crossfadeSamples * 2 && writePos >= crossfadeSamples) {
                        crossfadeSamples
                    } else 0

                    val start = writePos - overlap
                    for (i in trimmed.indices) {
                        val dest = start + i
                        if (dest >= voiceTrack.size) break
                        if (i < overlap) {
                            val t = i.toFloat() / overlap
                            val fadeOutPrev = cos(t * PI * 0.5).toFloat()
                            val fadeInCurr = sin(t * PI * 0.5).toFloat()
                            voiceTrack[dest] = voiceTrack[dest] * fadeOutPrev + trimmed[i] * fadeInCurr
                        } else {
                            voiceTrack[dest] += trimmed[i]
                        }
                    }
                    writePos = start + trimmed.size
                    prevWasVoice = true
                }
            }
        }

        // 2. Traitement de la piste voix : Passe-haut 80 Hz + Compresseur doux + Volume voix
        applyHighPass80Hz(voiceTrack)
        applyGentleVoiceCompressor(voiceTrack)
        if (mixConfig.voiceVolume != 1.0f) {
            for (i in voiceTrack.indices) {
                voiceTrack[i] *= mixConfig.voiceVolume
            }
        }

        // 3. Construction de la piste d'ambiance séparée avec fondus d'entrée/sortie (1,5 s par défaut)
        val ambienceTrack = FloatArray(totalSamples)
        if (ambienceChanges.isNotEmpty()) {
            val fadeSamples = (SR * mixConfig.fadeDurationSec.coerceIn(0.2f, 3.0f)).toInt()
            val customCache = HashMap<String, FloatArray>()

            for (idx in ambienceChanges.indices) {
                val (startSample, ambName, vol) = ambienceChanges[idx]
                if (ambName == null) continue
                val endSample = if (idx + 1 < ambienceChanges.size) {
                    ambienceChanges[idx + 1].first.coerceAtMost(totalSamples)
                } else {
                    totalSamples
                }
                if (endSample <= startSample) continue

                val loopBuffer = loadAmbienceLoop(ambName, customAmbiencePaths, customCache)
                if (loopBuffer.isEmpty()) continue

                val segLen = endSample - startSample
                val actualFadeIn = min(fadeSamples, segLen / 2)
                val actualFadeOut = min(fadeSamples, segLen / 2)

                for (local in 0 until segLen) {
                    val dest = startSample + local
                    if (dest >= ambienceTrack.size) break
                    val loopSample = loopBuffer[local % loopBuffer.size]
                    val fadeInGain = if (actualFadeIn > 0 && local < actualFadeIn) {
                        local.toFloat() / actualFadeIn
                    } else 1f
                    val remaining = segLen - 1 - local
                    val fadeOutGain = if (actualFadeOut > 0 && remaining < actualFadeOut) {
                        remaining.toFloat() / actualFadeOut
                    } else 1f

                    ambienceTrack[dest] += loopSample * vol * mixConfig.ambienceVolume * fadeInGain * fadeOutGain
                }
            }

            // 4. Ducking automatique (attaque 80 ms, relâchement 600 ms, baisse ~10 dB quand la voix parle)
            applyDucking(
                voiceTrack = voiceTrack,
                ambienceTrack = ambienceTrack,
                duckingDb = mixConfig.duckingAmountDb
            )
        }

        // 5. Somme finale + Normalisation -16 LUFS + Limiteur à -1 dB
        val mixed = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            mixed[i] = voiceTrack[i] + ambienceTrack[i]
        }

        val voiceOnlyNormalized = voiceTrack.copyOf()
        normalizeToTargetLufsWithPeakLimiter(voiceOnlyNormalized, targetLufs = -16.0f, peakLimitDb = -1.0f)
        val (peakDb, lufs) = normalizeToTargetLufsWithPeakLimiter(mixed, targetLufs = -16.0f, peakLimitDb = -1.0f)

        val metrics = AudioMetrics(
            durationSec = totalSamples.toFloat() / SR,
            peakDb = peakDb,
            integratedLufs = lufs,
            isClippingRisk = peakDb > -1.05f && PcmUtils.computePeakDb(voiceTrack) > -0.5f,
            isTooQuiet = lufs < -28f && totalSamples > SR / 2,
            waveformPeaks = PcmUtils.computeWaveformPeaks(mixed),
            segmentTimings = segmentTimings
        )

        return MixResult(
            mixedPcm = mixed,
            voiceOnlyPcm = voiceOnlyNormalized,
            metrics = metrics
        )
    }

    private fun applyCrossfadePlaceholder(samples: FloatArray, overlap: Int): FloatArray = samples

    private fun loadAmbienceLoop(
        ambName: String,
        customAmbiencePaths: Map<String, String>,
        customCache: MutableMap<String, FloatArray>
    ): FloatArray {
        val customPath = customAmbiencePaths.entries
            .firstOrNull { it.key.equals(ambName, ignoreCase = true) }?.value
        if (customPath != null) {
            customCache[customPath]?.let { return it }
            val loaded = PcmUtils.readFloatPcmFromFile(File(customPath))
            if (loaded.isNotEmpty()) {
                customCache[customPath] = loaded
                return loaded
            }
        }
        return AmbienceSynthesizer.getOrGenerateLoop(ambName)
    }

    /**
     * Filtre passe-haut Butterworth du 2e ordre à 80 Hz (fs = 24000 Hz).
     */
    private fun applyHighPass80Hz(samples: FloatArray) {
        val fc = 80.0
        val w0 = 2.0 * PI * fc / SR
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * 0.70710678) // Q = 1/sqrt(2)

        val b0 = ((1.0 + cosW0) / 2.0).toFloat()
        val b1 = (-(1.0 + cosW0)).toFloat()
        val b2 = ((1.0 + cosW0) / 2.0).toFloat()
        val a0 = (1.0 + alpha).toFloat()
        val a1 = (-2.0 * cosW0).toFloat()
        val a2 = (1.0 - alpha).toFloat()

        val nb0 = b0 / a0
        val nb1 = b1 / a0
        val nb2 = b2 / a0
        val na1 = a1 / a0
        val na2 = a2 / a0

        var x1 = 0f; var x2 = 0f
        var y1 = 0f; var y2 = 0f
        for (i in samples.indices) {
            val x0 = samples[i]
            val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            samples[i] = y0
        }
    }

    /**
     * Compresseur doux pour homogénéiser la présence vocale (seuil -18 dB, ratio 2.5:1).
     */
    private fun applyGentleVoiceCompressor(samples: FloatArray) {
        val thresholdDb = -18.0f
        val ratio = 2.5f
        val attackCoeff = exp(-1.0 / (0.010 * SR)).toFloat()
        val releaseCoeff = exp(-1.0 / (0.120 * SR)).toFloat()
        val makeupGain = 1.25f

        var env = 0f
        for (i in samples.indices) {
            val level = abs(samples[i])
            env = if (level > env) {
                attackCoeff * env + (1f - attackCoeff) * level
            } else {
                releaseCoeff * env + (1f - releaseCoeff) * level
            }
            val envDb = if (env < 1e-5f) -96f else 20f * log10(env)
            val gainDb = if (envDb > thresholdDb) {
                val excess = envDb - thresholdDb
                -(excess - excess / ratio)
            } else 0f
            val gain = 10f.pow(gainDb / 20f) * makeupGain
            samples[i] *= gain
        }
    }

    /**
     * Ducking automatique sur la piste d'ambiance piloté par l'enveloppe vocale :
     * attaque 80 ms, relâchement 600 ms.
     */
    private fun applyDucking(
        voiceTrack: FloatArray,
        ambienceTrack: FloatArray,
        duckingDb: Float
    ) {
        if (duckingDb <= 0.05f) return
        val attackCoeff = exp(-1.0 / (0.080 * SR)).toFloat()
        val releaseCoeff = exp(-1.0 / (0.600 * SR)).toFloat()
        val minGain = 10f.pow(-duckingDb.coerceIn(0f, 20f) / 20f)

        var voiceEnv = 0f
        for (i in voiceTrack.indices) {
            val v = abs(voiceTrack[i])
            voiceEnv = if (v > voiceEnv) {
                attackCoeff * voiceEnv + (1f - attackCoeff) * v
            } else {
                releaseCoeff * voiceEnv + (1f - releaseCoeff) * v
            }
            // Seuil de parole autour de -38 dBFS (0.0125)
            val duckFactor = ((voiceEnv - 0.010f) / 0.045f).coerceIn(0f, 1f)
            val gain = 1f - duckFactor * (1f - minGain)
            ambienceTrack[i] *= gain
        }
    }

    /**
     * Normalise vers -16 LUFS avec limiteur doux à -1 dBFS (0.89125).
     */
    private fun normalizeToTargetLufsWithPeakLimiter(
        samples: FloatArray,
        targetLufs: Float,
        peakLimitDb: Float
    ): Pair<Float, Float> {
        if (samples.isEmpty()) return -96f to -96f

        // Calcul d'intensité RMS pondérée (hors silences sous -50 dB, proche LUFS intégré)
        var gatedSumSq = 0.0
        var gatedCount = 0
        val blockSize = (SR * 0.4f).toInt().coerceAtLeast(1)
        var idx = 0
        while (idx < samples.size) {
            val end = min(samples.size, idx + blockSize)
            var bSum = 0.0
            for (j in idx until end) {
                bSum += samples[j] * samples[j]
            }
            val bRms = sqrt(bSum / (end - idx))
            if (bRms > 0.003) { // > -50 dBFS
                gatedSumSq += bSum
                gatedCount += (end - idx)
            }
            idx = end
        }

        val currentRms = if (gatedCount > 0) sqrt(gatedSumSq / gatedCount).toFloat() else 0f
        if (currentRms > 1e-5f) {
            val currentLufs = 20f * log10(currentRms) - 0.691f
            val gainDb = (targetLufs - currentLufs).coerceIn(-18f, 24f)
            val linearGain = 10f.pow(gainDb / 20f)
            for (i in samples.indices) {
                samples[i] *= linearGain
            }
        }

        // Limiteur doux de crête à -1 dBFS (~0.891)
        val ceiling = 10f.pow(peakLimitDb / 20f)
        for (i in samples.indices) {
            val x = samples[i]
            if (abs(x) > ceiling * 0.9f) {
                samples[i] = ceiling * tanh(x / ceiling)
            }
        }

        val finalPeak = PcmUtils.computePeakDb(samples)
        val finalRms = PcmUtils.computeRmsDb(samples)
        val finalLufs = if (finalRms <= -90f) -96f else finalRms - 0.691f
        return finalPeak to finalLufs
    }
}

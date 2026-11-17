package com.example.audio

import com.example.domain.model.DefectType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object PcmUtils {
    const val SAMPLE_RATE = 24000

    fun pcm16ToFloatArray(bytes: ByteArray): FloatArray {
        if (bytes.size < 2) return FloatArray(0)
        val count = bytes.size / 2
        val out = FloatArray(count)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            out[i] = bb.short / 32768f
        }
        return out
    }

    fun floatArrayToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clamped = (s.coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
            bb.putShort(clamped)
        }
        return out
    }

    fun writeFloatPcmToFile(file: File, samples: FloatArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(floatArrayToPcm16(samples))
    }

    fun readFloatPcmFromFile(file: File): FloatArray {
        if (!file.exists()) return FloatArray(0)
        return pcm16ToFloatArray(file.readBytes())
    }

    /**
     * Supprime les silences parasites en début et fin de segment (< -48 dBFS)
     * en conservant une marge naturelle de 25 ms et un micro-fondu anti-clic de 8 ms.
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): FloatArray {
        if (samples.isEmpty()) return samples
        val threshold = 0.004f // ~ -48 dBFS
        val padSamples = (sampleRate * 0.025f).toInt()
        val fadeSamples = (sampleRate * 0.008f).toInt()

        var firstNonSilent = -1
        for (i in samples.indices) {
            if (abs(samples[i]) > threshold) {
                firstNonSilent = i
                break
            }
        }
        if (firstNonSilent == -1) return samples // entièrement silencieux

        var lastNonSilent = samples.lastIndex
        for (i in samples.lastIndex downTo firstNonSilent) {
            if (abs(samples[i]) > threshold) {
                lastNonSilent = i
                break
            }
        }

        val start = max(0, firstNonSilent - padSamples)
        val end = min(samples.size, lastNonSilent + padSamples + 1)
        val trimmed = samples.copyOfRange(start, end)

        val actualFade = min(fadeSamples, trimmed.size / 4)
        for (i in 0 until actualFade) {
            val g = i.toFloat() / actualFade.coerceAtLeast(1)
            trimmed[i] *= g
            trimmed[trimmed.lastIndex - i] *= g
        }
        return trimmed
    }

    fun computePeakDb(samples: FloatArray): Float {
        if (samples.isEmpty()) return -96f
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        return if (peak < 1e-5f) -96f else (20f * log10(peak))
    }

    fun computeRmsDb(samples: FloatArray): Float {
        if (samples.isEmpty()) return -96f
        var sumSq = 0.0
        for (s in samples) {
            sumSq += s * s
        }
        val rms = sqrt(sumSq / samples.size).toFloat()
        return if (rms < 1e-5f) -96f else (20f * log10(rms))
    }

    /**
     * Détection automatique de défauts audio sur un segment généré.
     */
    fun detectDefect(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): DefectType? {
        if (samples.isEmpty()) return DefectType.EMPTY_AUDIO
        val rmsDb = computeRmsDb(samples)
        if (rmsDb < -54f) return DefectType.EMPTY_AUDIO

        val durationSec = samples.size.toFloat() / sampleRate
        if (durationSec < 0.25f) return DefectType.TOO_SHORT

        // Détecte un silence interne prolongé (> 1,8 s)
        val silenceThreshold = 0.003f
        val minSilenceSamples = (sampleRate * 1.8f).toInt()
        var consecutiveSilent = 0
        for (s in samples) {
            if (abs(s) < silenceThreshold) {
                consecutiveSilent++
                if (consecutiveSilent >= minSilenceSamples) {
                    return DefectType.LONG_SILENCE
                }
            } else {
                consecutiveSilent = 0
            }
        }
        return null
    }

    fun computeWaveformPeaks(samples: FloatArray, bucketCount: Int = 88): List<Float> {
        if (samples.isEmpty() || bucketCount <= 0) return emptyList()
        val step = max(1, samples.size / bucketCount)
        val peaks = ArrayList<Float>(bucketCount)
        var i = 0
        while (i < samples.size && peaks.size < bucketCount) {
            val end = min(samples.size, i + step)
            var maxVal = 0f
            for (j in i until end) {
                val a = abs(samples[j])
                if (a > maxVal) maxVal = a
            }
            peaks.add(maxVal.coerceIn(0.03f, 1f))
            i = end
        }
        return peaks
    }

    fun sha256Fingerprint(
        text: String,
        voice: String,
        tone: String,
        speed: String,
        modelId: String,
        languageCode: String
    ): String {
        val raw = "$modelId|$languageCode|$voice|$tone|$speed|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

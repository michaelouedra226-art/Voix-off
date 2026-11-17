package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.domain.model.AmbienceCatalog
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthétiseur DSP d'ambiances sonores bouclables sans coupure audible
 * et décodeur de fichiers audio personnels (mp3/wav/m4a) vers PCM 24 kHz mono.
 */
object AmbienceSynthesizer {

    private const val SR = PcmUtils.SAMPLE_RATE
    private const val LOOP_SECONDS = 10
    private const val LOOP_CROSSFADE_SEC = 0.6f

    // Cache en mémoire des boucles déjà synthétisées pour aperçus et mixages instantanés
    private val loopCache = HashMap<String, FloatArray>()

    fun getOrGenerateLoop(ambienceName: String): FloatArray {
        val canonical = AmbienceCatalog.findByName(ambienceName)?.id ?: ambienceName.lowercase()
        loopCache[canonical]?.let { return it }

        val totalSamples = SR * LOOP_SECONDS
        val fadeSamples = (SR * LOOP_CROSSFADE_SEC).toInt()
        // Génère un tampon plus long de fadeSamples pour boucler par fondu croisé sans clic
        val raw = FloatArray(totalSamples + fadeSamples)
        val rng = Random(canonical.hashCode())

        when (canonical) {
            "pluie_douce" -> synthRain(raw, rng, intensity = 0.45f)
            "orage_lointain" -> synthThunderstorm(raw, rng)
            "vent" -> synthWind(raw, rng)
            "vagues" -> synthWaves(raw, rng)
            "foret_oiseaux" -> synthForestBirds(raw, rng)
            "nuit_grillons" -> synthNightCrickets(raw, rng)
            "cafe_anime" -> synthCafe(raw, rng)
            "marche_anime" -> synthMarket(raw, rng)
            "feu_camp" -> synthCampfire(raw, rng)
            "studio_calme" -> synthStudioRoomTone(raw, rng)
            "drone_cinematique" -> synthCinematicDrone(raw)
            "nappe_douce" -> synthSoftPad(raw)
            "suspense" -> synthSuspense(raw)
            "joyeux_leger" -> synthLightHappy(raw)
            else -> synthStudioRoomTone(raw, rng)
        }

        // Bouclage sans couture (crossfade égal en puissance entre fin et début)
        val loop = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            loop[i] = raw[i]
        }
        for (i in 0 until fadeSamples) {
            val t = i.toFloat() / fadeSamples
            val fadeOut = cos(t * PI * 0.5).toFloat()
            val fadeIn = sin(t * PI * 0.5).toFloat()
            loop[i] = raw[i] * fadeIn + raw[totalSamples + i] * fadeOut
        }

        loopCache[canonical] = loop
        return loop
    }

    /**
     * Petit son cristallin optionnel joué à la fin d'une génération réussie.
     */
    fun generateCompletionChime(): FloatArray {
        val durationSec = 0.55f
        val count = (SR * durationSec).toInt()
        val out = FloatArray(count)
        val freqs = floatArrayOf(587.33f, 880.0f) // Ré5 -> La5
        for (i in 0 until count) {
            val t = i.toFloat() / SR
            val s1 = sin(2.0 * PI * freqs[0] * t).toFloat() * exp(-t * 6.5f) * 0.22f
            val s2 = if (t > 0.11f) {
                val t2 = t - 0.11f
                sin(2.0 * PI * freqs[1] * t2).toFloat() * exp(-t2 * 5.2f) * 0.22f
            } else 0f
            out[i] = (s1 + s2).coerceIn(-0.6f, 0.6f)
        }
        return out
    }

    // --- Synthèses procédurales spécialisées ---

    private fun pinkNoiseSample(b: FloatArray, rng: Random): Float {
        val white = rng.nextFloat() * 2f - 1f
        b[0] = 0.99886f * b[0] + white * 0.0555179f
        b[1] = 0.99332f * b[1] + white * 0.0750759f
        b[2] = 0.96900f * b[2] + white * 0.1538520f
        b[3] = 0.86650f * b[3] + white * 0.3104856f
        b[4] = 0.55000f * b[4] + white * 0.5329522f
        b[5] = -0.7616f * b[5] - white * 0.0168980f
        val pink = b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + white * 0.5362f
        b[6] = white * 0.115926f
        return pink * 0.11f
    }

    private fun synthRain(out: FloatArray, rng: Random, intensity: Float) {
        val pinkState = FloatArray(7)
        var lp = 0f
        var dropEnv = 0f
        var dropPhase = 0.0
        var dropFreq = 1800.0
        for (i in out.indices) {
            val p = pinkNoiseSample(pinkState, rng)
            lp = lp * 0.72f + p * 0.28f
            val bed = (p * 0.55f + lp * 0.45f) * intensity
            if (rng.nextFloat() < 0.0018f) {
                dropEnv = 0.15f + rng.nextFloat() * 0.15f
                dropFreq = 900.0 + rng.nextDouble() * 2400.0
            }
            val drop = sin(dropPhase).toFloat() * dropEnv
            dropPhase += 2.0 * PI * dropFreq / SR
            dropEnv *= 0.994f
            out[i] = (bed + drop).coerceIn(-0.85f, 0.85f)
        }
    }

    private fun synthThunderstorm(out: FloatArray, rng: Random) {
        synthRain(out, rng, intensity = 0.48f)
        val pinkState = FloatArray(7)
        var rumbleLp = 0f
        var rumbleLp2 = 0f
        var thunderEnv = 0f
        for (i in out.indices) {
            val t = i.toFloat() / SR
            if (i == (SR * 1.4f).toInt() || i == (SR * 6.2f).toInt()) {
                thunderEnv = 1.0f
            }
            val p = pinkNoiseSample(pinkState, rng)
            rumbleLp = rumbleLp * 0.985f + p * 0.015f
            rumbleLp2 = rumbleLp2 * 0.985f + rumbleLp * 0.015f
            val sub = sin(2.0 * PI * 44.0 * t).toFloat() * 0.18f
            out[i] = (out[i] + (rumbleLp2 * 5.2f + sub) * thunderEnv).coerceIn(-0.9f, 0.9f)
            thunderEnv *= 0.99993f
        }
    }

    private fun synthWind(out: FloatArray, rng: Random) {
        val pinkState = FloatArray(7)
        var y1 = 0f
        var y2 = 0f
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val lfo = 0.5f + 0.45f * sin(2.0 * PI * 0.18 * t).toFloat()
            val p = pinkNoiseSample(pinkState, rng)
            val cutoff = 0.03f + lfo * 0.11f
            y1 += cutoff * (p - y1)
            y2 += cutoff * (y1 - y2)
            out[i] = (y2 * (1.2f + lfo * 1.6f)).coerceIn(-0.8f, 0.8f)
        }
    }

    private fun synthWaves(out: FloatArray, rng: Random) {
        val pinkState = FloatArray(7)
        var lp = 0f
        for (i in out.indices) {
            val t = i.toDouble() / SR
            // Cycle de vague d'environ 5 s
            val swell = (0.5f - 0.5f * cos(2.0 * PI * 0.2 * t)).toFloat()
            val shaped = swell * swell
            val p = pinkNoiseSample(pinkState, rng)
            val alpha = 0.04f + shaped * 0.35f
            lp = lp * (1f - alpha) + p * alpha
            out[i] = (lp * (0.4f + shaped * 1.4f)).coerceIn(-0.85f, 0.85f)
        }
    }

    private fun synthForestBirds(out: FloatArray, rng: Random) {
        val pinkState = FloatArray(7)
        var breeze = 0f
        var chirpEnv = 0f
        var chirpPhase = 0.0
        var chirpBaseFreq = 3200.0
        var chirpTimer = 0
        for (i in out.indices) {
            val p = pinkNoiseSample(pinkState, rng)
            breeze = breeze * 0.94f + p * 0.06f
            if (chirpTimer <= 0 && rng.nextFloat() < 0.00055f) {
                chirpEnv = 0.25f
                chirpBaseFreq = 2600.0 + rng.nextDouble() * 1900.0
                chirpTimer = (SR * 0.14f).toInt()
            }
            var bird = 0f
            if (chirpTimer > 0) {
                val mod = sin(2.0 * PI * 28.0 * (chirpTimer.toDouble() / SR)) * 380.0
                chirpPhase += 2.0 * PI * (chirpBaseFreq + mod) / SR
                bird = sin(chirpPhase).toFloat() * chirpEnv
                chirpEnv *= 0.9992f
                chirpTimer--
            }
            out[i] = (breeze * 0.45f + bird).coerceIn(-0.8f, 0.8f)
        }
    }

    private fun synthNightCrickets(out: FloatArray, rng: Random) {
        val pinkState = FloatArray(7)
        var air = 0f
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val p = pinkNoiseSample(pinkState, rng)
            air = air * 0.96f + p * 0.04f
            // Pulsations typiques de grillons (~4.15 kHz modulé en trains d'impulsions)
            val burstGate = if (sin(2.0 * PI * 1.1 * t) > 0.15) 1f else 0f
            val pulse = maxOf(0f, sin(2.0 * PI * 22.0 * t).toFloat())
            val cricket = sin(2.0 * PI * 4150.0 * t).toFloat() * pulse * burstGate * 0.11f
            val cricket2 = sin(2.0 * PI * 4380.0 * t).toFloat() *
                maxOf(0f, sin(2.0 * PI * 18.0 * t).toFloat()) *
                (if (sin(2.0 * PI * 0.8 * t + 1.4) > 0.25) 1f else 0f) * 0.07f
            out[i] = (air * 0.35f + cricket + cricket2).coerceIn(-0.8f, 0.8f)
        }
    }

    private fun synthCafe(out: FloatArray, rng: Random) {
        val pinkState = FloatArray(7)
        var murmurLp = 0f
        var clinkEnv = 0f
        var clinkPhase = 0.0
        var clinkFreq = 2400.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val p = pinkNoiseSample(pinkState, rng)
            val voiceBandMod = 0.7f + 0.3f * sin(2.0 * PI * 3.1 * t).toFloat() * sin(2.0 * PI * 0.7 * t).toFloat()
            murmurLp = murmurLp * 0.82f + p * 0.18f
            if (rng.nextFloat() < 0.00028f) {
                clinkEnv = 0.14f
                clinkFreq = 2100.0 + rng.nextDouble() * 1600.0
            }
            val clink = sin(clinkPhase).toFloat() * clinkEnv
            clinkPhase += 2.0 * PI * clinkFreq / SR
            clinkEnv *= 0.9965f
            out[i] = (murmurLp * 0.85f * voiceBandMod + p * 0.18f + clink).coerceIn(-0.8f, 0.8f)
        }
    }

    private fun synthMarket(out: FloatArray, rng: Random) {
        val pinkState = FloatArray(7)
        var crowdLp = 0f
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val p = pinkNoiseSample(pinkState, rng)
            val swell = 0.75f + 0.25f * sin(2.0 * PI * 1.4 * t).toFloat()
            crowdLp = crowdLp * 0.76f + p * 0.24f
            out[i] = (crowdLp * 0.95f * swell + p * 0.28f).coerceIn(-0.85f, 0.85f)
        }
    }

    private fun synthCampfire(out: FloatArray, rng: Random) {
        val pinkState = FloatArray(7)
        var low = 0f
        var crackle = 0f
        for (i in out.indices) {
            val p = pinkNoiseSample(pinkState, rng)
            low = low * 0.94f + p * 0.06f
            if (rng.nextFloat() < 0.0014f) {
                crackle = (rng.nextFloat() * 2f - 1f) * 0.65f
            } else {
                crackle *= 0.92f
            }
            out[i] = (low * 0.9f + crackle).coerceIn(-0.85f, 0.85f)
        }
    }

    private fun synthStudioRoomTone(out: FloatArray, rng: Random) {
        val pinkState = FloatArray(7)
        var lp = 0f
        for (i in out.indices) {
            val p = pinkNoiseSample(pinkState, rng)
            lp = lp * 0.9f + p * 0.1f
            out[i] = (lp * 0.09f).coerceIn(-0.2f, 0.2f)
        }
    }

    private fun synthCinematicDrone(out: FloatArray) {
        // Accord profond en quinte (Do2 = 65.4 Hz, Sol2 = 98 Hz, Do3 = 130.8 Hz) avec lente respiration
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val breath = 0.75f + 0.25f * sin(2.0 * PI * 0.1 * t).toFloat()
            val s1 = sin(2.0 * PI * 65.41 * t).toFloat() * 0.28f
            val s2 = sin(2.0 * PI * 98.00 * t).toFloat() * 0.18f
            val s3 = sin(2.0 * PI * 130.81 * t + 0.3 * sin(2.0 * PI * 0.2 * t)).toFloat() * 0.12f
            out[i] = ((s1 + s2 + s3) * breath).coerceIn(-0.75f, 0.75f)
        }
    }

    private fun synthSoftPad(out: FloatArray) {
        // Nappe chaude Maj9 (Fa3, La3, Do4, Mi4, Sol4)
        val freqs = doubleArrayOf(174.61, 220.0, 261.63, 329.63, 392.0)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val lfo = 0.8f + 0.2f * sin(2.0 * PI * 0.1 * t).toFloat()
            var sum = 0f
            for ((idx, f) in freqs.withIndex()) {
                val amp = 0.14f / (1f + idx * 0.25f)
                sum += sin(2.0 * PI * f * t + idx * 0.4).toFloat() * amp
            }
            out[i] = (sum * lfo).coerceIn(-0.7f, 0.7f)
        }
    }

    private fun synthSuspense(out: FloatArray) {
        // Drone sombre avec seconde mineure et battement lent
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val pulse = 0.65f + 0.35f * sin(2.0 * PI * 0.5 * t).toFloat()
            val s1 = sin(2.0 * PI * 73.42 * t).toFloat() * 0.25f
            val s2 = sin(2.0 * PI * 77.78 * t).toFloat() * 0.16f // seconde mineure
            val s3 = sin(2.0 * PI * 103.83 * t).toFloat() * 0.10f // triton
            out[i] = ((s1 + s2 + s3) * pulse).coerceIn(-0.75f, 0.75f)
        }
    }

    private fun synthLightHappy(out: FloatArray) {
        // Arpège doux et lumineux (Do4, Mi4, Sol4, Si4)
        val notes = doubleArrayOf(261.63, 329.63, 392.0, 493.88, 392.0, 329.63)
        val noteLen = SR * 10 / 20 // 0.5s par note
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val step = (i / noteLen) % notes.size
            val localT = (i % noteLen).toFloat() / SR
            val env = sin(PI * (i % noteLen).toDouble() / noteLen).toFloat()
            val bell = sin(2.0 * PI * notes[step] * t).toFloat() * env * 0.18f
            val pad = sin(2.0 * PI * 130.81 * t).toFloat() * 0.08f +
                sin(2.0 * PI * 196.0 * t).toFloat() * 0.06f
            out[i] = (bell + pad * (1f - localT * 0.1f)).coerceIn(-0.65f, 0.65f)
        }
    }

    /**
     * Décode un fichier audio importé par l'utilisateur (MP3, WAV, M4A, OGG)
     * vers du PCM mono 24 kHz flottant utilisable dans le mixeur.
     */
    fun decodeExternalAudioToPcm24k(context: Context, uri: Uri, maxDurationSec: Int = 90): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex == -1 || format == null) {
            extractor.release()
            throw IllegalArgumentException("Aucune piste audio trouvée dans ce fichier.")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val rawSamples = ArrayList<Float>(SR * 10)
        var sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val maxSourceSamples = sourceSampleRate * maxDurationSec

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone && rawSamples.size < maxSourceSamples) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk, 0, info.size)
                            val sb = java.nio.ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val shorts = ShortArray(sb.remaining())
                            sb.get(shorts)
                            var idx = 0
                            while (idx + channelCount <= shorts.size && rawSamples.size < maxSourceSamples) {
                                var sum = 0f
                                for (c in 0 until channelCount) {
                                    sum += shorts[idx + c] / 32768f
                                }
                                rawSamples.add(sum / channelCount)
                                idx += channelCount
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        if (rawSamples.isEmpty()) {
            throw IllegalStateException("Impossible de décoder le contenu audio du fichier.")
        }

        // Rééchantillonnage linéaire vers 24 kHz
        if (sourceSampleRate == SR) {
            return rawSamples.toFloatArray()
        }
        val ratio = sourceSampleRate.toDouble() / SR
        val targetLen = (rawSamples.size / ratio).toInt().coerceAtLeast(1)
        val resampled = FloatArray(targetLen)
        for (i in 0 until targetLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, rawSamples.lastIndex)
            val i1 = (i0 + 1).coerceIn(0, rawSamples.lastIndex)
            val frac = (srcPos - i0).toFloat()
            resampled[i] = rawSamples[i0] * (1f - frac) + rawSamples[i1] * frac
        }
        return resampled
    }
}

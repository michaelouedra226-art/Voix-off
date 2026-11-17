package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

data class PlaybackState(
    val activeTag: String? = null, // "final", "voice_only", "segment:<id>", "ambience:<name>", "ab:<id>"
    val isPlaying: Boolean = false,
    val currentSec: Float = 0f,
    val totalSec: Float = 0f,
    val liveVuNormalized: Float = 0f,
    val liveVuDb: Float = -60f
)

/**
 * Lecteur PCM temps réel basé sur AudioTrack avec suivi précis du curseur,
 * recherche par glissement ou double-tap (±5s) et vumètre de niveau en direct.
 */
class AudioPlayerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null
    private var activeTrack: AudioTrack? = null

    private var currentPcm: FloatArray = FloatArray(0)
    private var currentSamplePos: Int = 0

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun loadBuffer(tag: String, samples: FloatArray, autoPlay: Boolean = false) {
        stopInternal(resetPosition = true)
        currentPcm = samples
        currentSamplePos = 0
        val totalSec = samples.size.toFloat() / PcmUtils.SAMPLE_RATE
        _state.value = PlaybackState(
            activeTag = tag,
            isPlaying = false,
            currentSec = 0f,
            totalSec = totalSec,
            liveVuNormalized = 0f,
            liveVuDb = -60f
        )
        if (autoPlay && samples.isNotEmpty()) {
            play()
        }
    }

    fun playSamplesOnce(tag: String, samples: FloatArray) {
        loadBuffer(tag, samples, autoPlay = true)
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        if (currentPcm.isEmpty()) return
        if (_state.value.isPlaying) return
        if (currentSamplePos >= currentPcm.size) {
            currentSamplePos = 0
        }

        val sr = PcmUtils.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeTrack = track
        track.play()
        _state.value = _state.value.copy(isPlaying = true)

        playJob = scope.launch {
            val chunkSamples = 1024 // ~42 ms pour un vumètre fluide
            while (isActive && currentSamplePos < currentPcm.size) {
                val end = min(currentPcm.size, currentSamplePos + chunkSamples)
                val slice = currentPcm.copyOfRange(currentSamplePos, end)
                val pcmBytes = PcmUtils.floatArrayToPcm16(slice)

                // Calcul du niveau crête instantané pour le vumètre
                var peak = 0f
                for (s in slice) {
                    val a = abs(s)
                    if (a > peak) peak = a
                }
                val db = if (peak < 1e-4f) -60f else (20f * log10(peak)).coerceIn(-60f, 0f)
                val norm = ((db + 60f) / 60f).coerceIn(0f, 1f)

                val written = track.write(pcmBytes, 0, pcmBytes.size)
                if (written < 0) break

                currentSamplePos = end
                _state.value = _state.value.copy(
                    currentSec = currentSamplePos.toFloat() / sr,
                    liveVuNormalized = norm,
                    liveVuDb = db
                )
            }

            if (currentSamplePos >= currentPcm.size && isActive) {
                currentSamplePos = 0
                _state.value = _state.value.copy(
                    isPlaying = false,
                    currentSec = 0f,
                    liveVuNormalized = 0f,
                    liveVuDb = -60f
                )
            }
            releaseTrackSafely()
        }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        releaseTrackSafely()
        _state.value = _state.value.copy(isPlaying = false, liveVuNormalized = 0f, liveVuDb = -60f)
    }

    fun stop() {
        stopInternal(resetPosition = true)
    }

    fun seekTo(seconds: Float) {
        if (currentPcm.isEmpty()) return
        val wasPlaying = _state.value.isPlaying
        if (wasPlaying) pause()
        val targetSample = (seconds * PcmUtils.SAMPLE_RATE).toInt().coerceIn(0, currentPcm.lastIndex)
        currentSamplePos = targetSample
        _state.value = _state.value.copy(currentSec = targetSample.toFloat() / PcmUtils.SAMPLE_RATE)
        if (wasPlaying) play()
    }

    fun seekByDelta(deltaSeconds: Float) {
        val target = (_state.value.currentSec + deltaSeconds).coerceIn(0f, _state.value.totalSec)
        seekTo(target)
    }

    fun playAmbiencePreview(ambienceName: String, customPcmPath: String? = null) {
        val loop = if (customPcmPath != null) {
            val file = java.io.File(customPcmPath)
            if (file.exists()) PcmUtils.readFloatPcmFromFile(file) else AmbienceSynthesizer.getOrGenerateLoop(ambienceName)
        } else {
            AmbienceSynthesizer.getOrGenerateLoop(ambienceName)
        }
        val fiveSecCount = min(loop.size, PcmUtils.SAMPLE_RATE * 5)
        val preview = loop.copyOfRange(0, fiveSecCount)
        // Petit fondu entrée/sortie 150 ms
        val fade = min(preview.size / 4, (PcmUtils.SAMPLE_RATE * 0.15f).toInt())
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            preview[i] *= g
            preview[preview.lastIndex - i] *= g
        }
        playSamplesOnce("ambience:$ambienceName", preview)
    }

    private fun stopInternal(resetPosition: Boolean) {
        playJob?.cancel()
        playJob = null
        releaseTrackSafely()
        if (resetPosition) {
            currentSamplePos = 0
            _state.value = _state.value.copy(
                isPlaying = false,
                currentSec = 0f,
                liveVuNormalized = 0f,
                liveVuDb = -60f
            )
        }
    }

    private fun releaseTrackSafely() {
        try {
            activeTrack?.pause()
            activeTrack?.flush()
            activeTrack?.release()
        } catch (_: Exception) {
        } finally {
            activeTrack = null
        }
    }

    fun release() {
        stop()
        currentPcm = FloatArray(0)
    }
}

package com.example.audio

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AudioExportFormat(val extension: String, val mimeType: String, val labelFr: String) {
    WAV("wav", "audio/wav", "WAV Studio (sans perte)"),
    M4A("m4a", "audio/mp4", "M4A / AAC (compressé)")
}

/**
 * Exporteur audio réel vers WAV (PCM 16-bit 24 kHz) et M4A/AAC (via MediaCodec + MediaMuxer),
 * avec prise en charge de l'export "voix seule" et du partage natif Android.
 */
object AudioExporter {

    private const val SR = PcmUtils.SAMPLE_RATE

    fun writeWavToStream(samples: FloatArray, output: OutputStream) {
        val pcmBytes = PcmUtils.floatArrayToPcm16(samples)
        val header = buildWavHeader(pcmBytes.size, sampleRate = SR, channels = 1, bitsPerSample = 16)
        output.write(header)
        output.write(pcmBytes)
        output.flush()
    }

    fun writeWavToFile(samples: FloatArray, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            writeWavToStream(samples, fos)
        }
    }

    /**
     * Encode vers M4A (AAC-LC 128 kbps, 24 kHz mono) via l'encodeur matériel/logiciel Android.
     */
    fun writeM4aToFile(samples: FloatArray, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()

        val pcmBytes = PcmUtils.floatArrayToPcm16(samples)
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, SR, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(targetFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var offset = 0
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        if (offset >= pcmBytes.size) {
                            val ptsUs = (offset.toLong() / 2) * 1_000_000L / SR
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val chunkSize = minOf(inBuf.remaining(), pcmBytes.size - offset)
                            inBuf.put(pcmBytes, offset, chunkSize)
                            val ptsUs = (offset.toLong() / 2) * 1_000_000L / SR
                            offset += chunkSize
                            codec.queueInputBuffer(inIndex, 0, chunkSize, ptsUs, 0)
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }
    }

    fun writeToSafUri(
        context: Context,
        samples: FloatArray,
        format: AudioExportFormat,
        destinationUri: Uri
    ) {
        when (format) {
            AudioExportFormat.WAV -> {
                context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    writeWavToStream(samples, out)
                } ?: throw IllegalStateException("Impossible d'ouvrir le fichier de destination.")
            }
            AudioExportFormat.M4A -> {
                val temp = File(context.cacheDir, "export_temp_${System.currentTimeMillis()}.m4a")
                try {
                    writeM4aToFile(samples, temp)
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        temp.inputStream().use { inp -> inp.copyTo(out) }
                    } ?: throw IllegalStateException("Impossible d'écrire le fichier M4A.")
                } finally {
                    temp.delete()
                }
            }
        }
    }

    fun createShareIntent(
        context: Context,
        samples: FloatArray,
        format: AudioExportFormat,
        baseName: String
    ): Intent {
        val safeName = baseName.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "voxa_studio" }
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        val targetFile = File(exportDir, "${safeName}.${format.extension}")
        when (format) {
            AudioExportFormat.WAV -> writeWavToFile(samples, targetFile)
            AudioExportFormat.M4A -> writeM4aToFile(samples, targetFile)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildWavHeader(
        pcmDataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val totalDataLen = pcmDataSize + 36
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // PCM format
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmDataSize)
        }.array()
    }
}

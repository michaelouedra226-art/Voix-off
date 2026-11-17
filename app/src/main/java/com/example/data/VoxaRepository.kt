package com.example.data

import android.content.Context
import android.net.Uri
import com.example.audio.AmbienceSynthesizer
import com.example.audio.PcmUtils
import com.example.domain.model.AudioMetrics
import com.example.domain.model.CustomAmbience
import com.example.domain.model.DefectType
import com.example.domain.model.GenerationRecord
import com.example.domain.model.MixConfig
import com.example.domain.model.ProjectItem
import com.example.domain.model.PronunciationEntry
import com.example.domain.model.ScriptVersion
import com.example.domain.model.SegmentTake
import com.example.domain.model.StudioMode
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Référentiel principal gérant la persistance locale Room, le cache PCM sur disque
 * et l'import/export JSON des projets.
 */
class VoxaRepository(
    private val context: Context,
    private val dao: VoxaDao
) {
    private val segmentCacheDir = File(context.filesDir, "segment_cache").apply { mkdirs() }
    private val customAmbienceDir = File(context.filesDir, "custom_ambiences").apply { mkdirs() }
    private val generationsDir = File(context.filesDir, "generations").apply { mkdirs() }

    val projectsFlow: Flow<List<ProjectItem>> = dao.observeProjects().map { list ->
        list.map { it.toDomain() }
    }

    val dictionaryFlow: Flow<List<PronunciationEntry>> = dao.observeDictionary().map { list ->
        list.map { PronunciationEntry(it.id, it.originalWord, it.phoneticReplacement) }
    }

    val customAmbiencesFlow: Flow<List<CustomAmbience>> = dao.observeCustomAmbiences().map { list ->
        list.map { CustomAmbience(it.id, it.name, it.pcmFilePath, it.durationSec, it.createdAt) }
    }

    fun observeVersions(projectId: String): Flow<List<ScriptVersion>> =
        dao.observeVersionsForProject(projectId).map { list ->
            list.map { ScriptVersion(it.id, it.projectId, it.script, it.label, it.createdAt) }
        }

    fun observeGenerations(projectId: String): Flow<List<GenerationRecord>> =
        dao.observeGenerationsForProject(projectId).map { list ->
            list.map { it.toDomain() }
        }

    suspend fun saveGenerationRecord(
        projectId: String,
        title: String,
        sourceMode: StudioMode,
        annotatedScript: String,
        directorExplanation: String,
        selectedVoice: String,
        modelId: String,
        mixedPcm: FloatArray,
        voiceOnlyPcm: FloatArray,
        metrics: AudioMetrics,
        ambiencesSummary: String,
        segmentCount: Int
    ): GenerationRecord = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val mixedFile = File(generationsDir, "${id}_mixed.pcm")
        val voiceFile = File(generationsDir, "${id}_voice.pcm")
        PcmUtils.writeFloatPcmToFile(mixedFile, mixedPcm)
        PcmUtils.writeFloatPcmToFile(voiceFile, voiceOnlyPcm)

        val now = System.currentTimeMillis()
        val peaksCsv = metrics.waveformPeaks.joinToString(",") { "%.3f".format(Locale.US, it) }
        val entity = GenerationEntity(
            id = id,
            projectId = projectId,
            title = title,
            sourceMode = sourceMode.name,
            annotatedScript = annotatedScript,
            directorExplanation = directorExplanation,
            selectedVoice = selectedVoice,
            modelId = modelId,
            mixedPcmPath = mixedFile.absolutePath,
            voiceOnlyPcmPath = voiceFile.absolutePath,
            durationSec = metrics.durationSec,
            peakDb = metrics.peakDb,
            integratedLufs = metrics.integratedLufs,
            waveformPeaksCsv = peaksCsv,
            ambiencesSummary = ambiencesSummary,
            segmentCount = segmentCount,
            createdAt = now
        )
        dao.insertGeneration(entity)
        entity.toDomain()
    }

    suspend fun deleteGeneration(record: GenerationRecord) = withContext(Dispatchers.IO) {
        File(record.mixedPcmPath).delete()
        File(record.voiceOnlyPcmPath).delete()
        dao.deleteGenerationById(record.id)
    }

    suspend fun getProject(id: String): ProjectItem? =
        dao.getProjectById(id)?.toDomain()

    suspend fun saveProject(project: ProjectItem) {
        dao.upsertProject(project.toEntity())
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        val gens = dao.getGenerationsForProjectOnce(id)
        for (g in gens) {
            File(g.mixedPcmPath).delete()
            File(g.voiceOnlyPcmPath).delete()
        }
        dao.deleteGenerationsForProject(id)
        dao.deleteVersionsForProject(id)
        dao.deleteProjectById(id)
    }

    suspend fun saveScriptVersion(projectId: String, script: String, label: String) {
        if (script.isBlank()) return
        dao.insertVersion(
            ScriptVersionEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                script = script,
                label = label,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun upsertPronunciation(originalWord: String, phonetic: String) {
        dao.upsertPronunciation(
            PronunciationEntity(
                id = UUID.randomUUID().toString(),
                originalWord = originalWord.trim(),
                phoneticReplacement = phonetic.trim()
            )
        )
    }

    suspend fun deletePronunciation(id: String) {
        dao.deletePronunciation(id)
    }

    suspend fun getCachedTake(fingerprint: String): SegmentTake? = withContext(Dispatchers.IO) {
        val entity = dao.getCachedSegment(fingerprint) ?: return@withContext null
        val file = File(entity.pcmFilePath)
        if (!file.exists() || file.length() == 0L) {
            dao.deleteCachedSegment(fingerprint)
            return@withContext null
        }
        SegmentTake(
            takeId = "cache_${fingerprint.take(8)}",
            fingerprint = fingerprint,
            pcmFilePath = entity.pcmFilePath,
            durationSec = entity.durationSec,
            peakDb = entity.peakDb,
            createdAt = entity.createdAt,
            defect = entity.defectName?.let { runCatching { DefectType.valueOf(it) }.getOrNull() }
        )
    }

    suspend fun saveSegmentTakeToDisk(
        fingerprint: String,
        samples: FloatArray,
        saveAsPrimaryCache: Boolean
    ): SegmentTake = withContext(Dispatchers.IO) {
        val takeId = UUID.randomUUID().toString().take(8)
        val file = if (saveAsPrimaryCache) {
            File(segmentCacheDir, "$fingerprint.pcm")
        } else {
            File(segmentCacheDir, "${fingerprint}_take_$takeId.pcm")
        }
        PcmUtils.writeFloatPcmToFile(file, samples)

        val durationSec = samples.size.toFloat() / PcmUtils.SAMPLE_RATE
        val peakDb = PcmUtils.computePeakDb(samples)
        val defect = PcmUtils.detectDefect(samples)
        val now = System.currentTimeMillis()

        if (saveAsPrimaryCache) {
            dao.upsertCachedSegment(
                CachedSegmentEntity(
                    fingerprint = fingerprint,
                    pcmFilePath = file.absolutePath,
                    durationSec = durationSec,
                    peakDb = peakDb,
                    defectName = defect?.name,
                    createdAt = now
                )
            )
            enforceCacheLimit(maxEntries = 120)
        }

        SegmentTake(
            takeId = takeId,
            fingerprint = fingerprint,
            pcmFilePath = file.absolutePath,
            durationSec = durationSec,
            peakDb = peakDb,
            createdAt = now,
            defect = defect
        )
    }

    private suspend fun enforceCacheLimit(maxEntries: Int) {
        val all = dao.getAllCachedSegments()
        if (all.size > maxEntries) {
            val toRemove = all.take(all.size - maxEntries)
            for (item in toRemove) {
                File(item.pcmFilePath).delete()
                dao.deleteCachedSegment(item.fingerprint)
            }
        }
    }

    suspend fun importCustomAmbience(name: String, uri: Uri): CustomAmbience = withContext(Dispatchers.IO) {
        val samples = AmbienceSynthesizer.decodeExternalAudioToPcm24k(context, uri)
        val id = UUID.randomUUID().toString()
        val safeName = name.trim().ifBlank { "ambiance_${id.take(4)}" }
        val file = File(customAmbienceDir, "$id.pcm")
        PcmUtils.writeFloatPcmToFile(file, samples)
        val durationSec = samples.size.toFloat() / PcmUtils.SAMPLE_RATE
        val entity = CustomAmbienceEntity(
            id = id,
            name = safeName,
            pcmFilePath = file.absolutePath,
            durationSec = durationSec,
            createdAt = System.currentTimeMillis()
        )
        dao.upsertCustomAmbience(entity)
        CustomAmbience(id, safeName, file.absolutePath, durationSec, entity.createdAt)
    }

    suspend fun deleteCustomAmbience(ambience: CustomAmbience) = withContext(Dispatchers.IO) {
        File(ambience.pcmFilePath).delete()
        dao.deleteCustomAmbience(ambience.id)
    }

    fun exportProjectToJson(project: ProjectItem): String {
        return JSONObject().apply {
            put("voxaFormatVersion", 1)
            put("title", project.title)
            put("mode", project.mode.name)
            put("briefDescription", project.briefDescription)
            put("briefRawText", project.briefRawText)
            put("expertScript", project.expertScript)
            put("defaultVoice", project.defaultVoice)
            put("ttsModelId", project.ttsModelId)
            put("languageCode", project.languageCode)
            put(
                "mixConfig",
                JSONObject().apply {
                    put("voiceVolume", project.mixConfig.voiceVolume)
                    put("ambienceVolume", project.mixConfig.ambienceVolume)
                    put("duckingAmountDb", project.mixConfig.duckingAmountDb)
                    put("fadeDurationSec", project.mixConfig.fadeDurationSec)
                    put("crossfadeMs", project.mixConfig.crossfadeMs)
                }
            )
        }.toString(2)
    }

    fun parseImportedProjectJson(rawJson: String, fallbackVoice: String, fallbackModel: String): ProjectItem {
        val obj = JSONObject(rawJson)
        val mixObj = obj.optJSONObject("mixConfig")
        val mix = MixConfig(
            voiceVolume = mixObj?.optDouble("voiceVolume", 1.0)?.toFloat() ?: 1.0f,
            ambienceVolume = mixObj?.optDouble("ambienceVolume", 1.0)?.toFloat() ?: 1.0f,
            duckingAmountDb = mixObj?.optDouble("duckingAmountDb", 10.0)?.toFloat() ?: 10.0f,
            fadeDurationSec = mixObj?.optDouble("fadeDurationSec", 1.5)?.toFloat() ?: 1.5f,
            crossfadeMs = mixObj?.optInt("crossfadeMs", 80) ?: 80
        )
        val mode = runCatching { StudioMode.valueOf(obj.optString("mode", "EXPERT")) }
            .getOrDefault(StudioMode.EXPERT)
        return ProjectItem(
            id = UUID.randomUUID().toString(),
            title = obj.optString("title").ifBlank { "Projet importé" },
            mode = mode,
            briefDescription = obj.optString("briefDescription", ""),
            briefRawText = obj.optString("briefRawText", ""),
            expertScript = obj.optString("expertScript", ""),
            defaultVoice = obj.optString("defaultVoice", fallbackVoice),
            ttsModelId = obj.optString("ttsModelId", fallbackModel),
            languageCode = obj.optString("languageCode", "auto"),
            mixConfig = mix,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun ProjectEntity.toDomain(): ProjectItem = ProjectItem(
        id = id,
        title = title,
        mode = runCatching { StudioMode.valueOf(mode) }.getOrDefault(StudioMode.BRIEF),
        briefDescription = briefDescription,
        briefRawText = briefRawText,
        expertScript = expertScript,
        defaultVoice = defaultVoice,
        ttsModelId = ttsModelId,
        languageCode = languageCode,
        mixConfig = MixConfig(
            voiceVolume = voiceVolume,
            ambienceVolume = ambienceVolume,
            duckingAmountDb = duckingAmountDb,
            fadeDurationSec = fadeDurationSec,
            crossfadeMs = crossfadeMs
        ),
        updatedAt = updatedAt
    )

    private fun ProjectItem.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        title = title,
        mode = mode.name,
        briefDescription = briefDescription,
        briefRawText = briefRawText,
        expertScript = expertScript,
        defaultVoice = defaultVoice,
        ttsModelId = ttsModelId,
        languageCode = languageCode,
        voiceVolume = mixConfig.voiceVolume,
        ambienceVolume = mixConfig.ambienceVolume,
        duckingAmountDb = mixConfig.duckingAmountDb,
        fadeDurationSec = mixConfig.fadeDurationSec,
        crossfadeMs = mixConfig.crossfadeMs,
        updatedAt = updatedAt
    )

    private fun GenerationEntity.toDomain(): GenerationRecord {
        val peaks = if (waveformPeaksCsv.isBlank()) {
            emptyList()
        } else {
            waveformPeaksCsv.split(",").mapNotNull { it.toFloatOrNull() }
        }
        return GenerationRecord(
            id = id,
            projectId = projectId,
            title = title,
            sourceMode = runCatching { StudioMode.valueOf(sourceMode) }.getOrDefault(StudioMode.BRIEF),
            annotatedScript = annotatedScript,
            directorExplanation = directorExplanation,
            selectedVoice = selectedVoice,
            modelId = modelId,
            mixedPcmPath = mixedPcmPath,
            voiceOnlyPcmPath = voiceOnlyPcmPath,
            durationSec = durationSec,
            peakDb = peakDb,
            integratedLufs = integratedLufs,
            waveformPeaks = peaks,
            ambiencesSummary = ambiencesSummary,
            segmentCount = segmentCount,
            createdAt = createdAt
        )
    }
}

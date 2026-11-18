package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.ai.BriefPlanningException
import com.example.ai.VoxaAiService
import com.example.audio.AmbienceSynthesizer
import com.example.audio.AudioExportFormat
import com.example.audio.AudioExporter
import com.example.audio.AudioMixerEngine
import com.example.audio.AudioPlayerEngine
import com.example.audio.PcmUtils
import com.example.audio.PlaybackState
import com.example.data.SettingsDataStore
import com.example.data.VoxaDatabase
import com.example.data.VoxaRepository
import com.example.domain.model.AudioMetrics
import com.example.domain.model.BriefPlan
import com.example.domain.model.GenerationRecord
import com.example.domain.model.LanguageOption
import com.example.domain.model.MixConfig
import com.example.domain.model.ProjectItem
import com.example.domain.model.ScriptVersion
import com.example.domain.model.SegmentRuntimeState
import com.example.domain.model.SegmentStatus
import com.example.domain.model.StudioMode
import com.example.domain.model.StudioSettings
import com.example.domain.model.TargetDurationOption
import com.example.domain.model.TimelineEvent
import com.example.domain.model.VoiceGenderPreference
import com.example.domain.script.BracketParser
import com.example.domain.script.LanguageDetector
import com.example.domain.script.PronunciationNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface StudioToastEvent {
    data class Info(val message: String) : StudioToastEvent
    data class UndoDeleteProject(val project: ProjectItem) : StudioToastEvent
    data class UndoClearScript(val previousScript: String) : StudioToastEvent
}

class VoxaStudioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val dao = VoxaDatabase.getInstance(appContext).voxaDao()
    private val repository = VoxaRepository(appContext, dao)
    private val settingsStore = SettingsDataStore(appContext)
    private val aiService = VoxaAiService(appContext)
    val playerEngine = AudioPlayerEngine()

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()
    val playbackState: StateFlow<PlaybackState> = playerEngine.state

    private val _toastEvents = MutableSharedFlow<StudioToastEvent>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<StudioToastEvent> = _toastEvents.asSharedFlow()

    // Tampons audio finaux conservés en mémoire pour lecture et export immédiat
    private var mixedFinalPcm: FloatArray = FloatArray(0)
    private var voiceOnlyFinalPcm: FloatArray = FloatArray(0)

    // Piles Annuler / Rétablir pour l'éditeur
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    private var generationJob: Job? = null
    private var parseJob: Job? = null
    private var autoSaveJob: Job? = null
    private var versionsJob: Job? = null
    private var generationsJob: Job? = null
    private var isAppInBackground = false

    init {
        ensureNotificationChannel()
        observePersistentData()
        refreshNetworkStatus()
    }

    fun setAppInBackground(inBackground: Boolean) {
        isAppInBackground = inBackground
    }

    fun refreshNetworkStatus() {
        _uiState.update { it.copy(isOffline = !aiService.isNetworkAvailable()) }
    }

    private fun observePersistentData() {
        viewModelScope.launch {
            val initialSettings = settingsStore.settingsFlow.first()
            val activeId = settingsStore.activeProjectIdFlow.first()
            val loadedProject = activeId?.let { repository.getProject(it) }

            if (loadedProject != null) {
                loadProjectIntoEditor(loadedProject, initialSettings)
            } else {
                // Premier lancement : strictement vide avec valeurs de config neutres
                val freshId = UUID.randomUUID().toString()
                _uiState.update {
                    it.copy(
                        activeProjectId = freshId,
                        selectedVoice = initialSettings.defaultVoice,
                        selectedModelId = initialSettings.defaultModelId,
                        selectedLanguage = LanguageOption.fromCode(initialSettings.defaultLanguageCode),
                        settings = initialSettings
                    )
                }
                observeProjectVersions(freshId)
                observeProjectGenerations(freshId)
            }

            launch {
                settingsStore.settingsFlow.collect { s ->
                    _uiState.update { it.copy(settings = s) }
                    reparseScript()
                }
            }
            launch {
                settingsStore.recentTonesFlow.collect { tones ->
                    _uiState.update { it.copy(recentTones = tones) }
                }
            }
            launch {
                settingsStore.cumulativeUsageFlow.collect { usage ->
                    _uiState.update { it.copy(cumulativeUsage = usage) }
                }
            }
            launch {
                repository.projectsFlow.collect { projects ->
                    _uiState.update { it.copy(projects = projects) }
                }
            }
            launch {
                repository.dictionaryFlow.collect { dict ->
                    _uiState.update { it.copy(dictionary = dict) }
                }
            }
            launch {
                repository.customAmbiencesFlow.collect { ambs ->
                    _uiState.update { it.copy(customAmbiences = ambs) }
                    reparseScript()
                }
            }
        }
    }

    private fun observeProjectVersions(projectId: String) {
        versionsJob?.cancel()
        versionsJob = viewModelScope.launch {
            repository.observeVersions(projectId).collect { versions ->
                _uiState.update { it.copy(scriptVersions = versions) }
            }
        }
    }

    private fun observeProjectGenerations(projectId: String) {
        generationsJob?.cancel()
        generationsJob = viewModelScope.launch {
            repository.observeGenerations(projectId).collect { gens ->
                _uiState.update { it.copy(generations = gens) }
                // Si aucun audio n'est en mémoire et qu'une génération existe sur disque, restaurer la plus récente
                if (mixedFinalPcm.isEmpty() && !_uiState.value.hasFinalAudio && gens.isNotEmpty()) {
                    val latest = gens.first()
                    if (File(latest.mixedPcmPath).exists()) {
                        loadGenerationIntoPlayer(latest, autoPlay = false)
                    }
                }
            }
        }
    }

    // --- Navigation & Édition ---

    fun setMode(mode: StudioMode) {
        _uiState.update { it.copy(mode = mode, errorMessage = null) }
        scheduleAutoSave()
    }

    fun updateProjectTitle(title: String) {
        _uiState.update { it.copy(projectTitle = title) }
        scheduleAutoSave()
    }

    fun updateBriefDescription(desc: String) {
        _uiState.update { it.copy(briefDescription = desc, errorMessage = null) }
        scheduleAutoSave()
    }

    fun updateBriefRawText(text: String) {
        _uiState.update { it.copy(briefRawText = text, errorMessage = null) }
        scheduleAutoSave()
    }

    fun setTargetDuration(opt: TargetDurationOption) {
        _uiState.update { it.copy(targetDuration = opt) }
    }

    fun setSelectedLanguage(lang: LanguageOption) {
        _uiState.update { it.copy(selectedLanguage = lang) }
        scheduleAutoSave()
    }

    fun setGenderPreference(pref: VoiceGenderPreference) {
        _uiState.update { it.copy(genderPreference = pref) }
    }

    fun updateRefinePrompt(prompt: String) {
        _uiState.update { it.copy(refinePrompt = prompt) }
    }

    fun updateExpertScript(newScript: String, cursorPos: Int = newScript.length, pushUndo: Boolean = true) {
        val current = _uiState.value.expertScript
        if (pushUndo && newScript != current) {
            undoStack.addLast(current)
            if (undoStack.size > 150) undoStack.removeFirst()
            redoStack.clear()
        }
        _uiState.update {
            it.copy(
                expertScript = newScript,
                cursorPosition = cursorPos.coerceIn(0, newScript.length),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                errorMessage = null
            )
        }
        reparseScript()
        scheduleAutoSave()
    }

    fun updateCursorPosition(pos: Int) {
        _uiState.update { it.copy(cursorPosition = pos.coerceIn(0, it.expertScript.length)) }
    }

    fun undoScript() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeLast()
        redoStack.addLast(_uiState.value.expertScript)
        _uiState.update {
            it.copy(
                expertScript = prev,
                cursorPosition = prev.length,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        reparseScript()
        scheduleAutoSave()
    }

    fun redoScript() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(_uiState.value.expertScript)
        _uiState.update {
            it.copy(
                expertScript = next,
                cursorPosition = next.length,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        reparseScript()
        scheduleAutoSave()
    }

    fun insertTagAtCursor(tagText: String, replaceFromIndex: Int? = null) {
        val state = _uiState.value
        val script = state.expertScript
        val start = (replaceFromIndex ?: state.cursorPosition).coerceIn(0, script.length)
        val end = state.cursorPosition.coerceIn(start, script.length)
        val prefix = script.substring(0, start)
        val suffix = script.substring(end)
        val needsSpace = prefix.isNotEmpty() && !prefix.last().isWhitespace() && replaceFromIndex == null && !tagText.endsWith(": ")
        val inserted = (if (needsSpace) " " else "") + tagText
        val newText = prefix + inserted + suffix
        val newCursor = prefix.length + inserted.length
        updateExpertScript(newText, newCursor)

        // Mémorise le ton s'il s'agit d'une balise [ton: ...] complète
        val toneMatch = Regex("\\[ton:\\s*([^\\]]+)\\]", RegexOption.IGNORE_CASE).find(tagText)
        if (toneMatch != null) {
            val toneVal = toneMatch.groupValues[1].trim()
            viewModelScope.launch { settingsStore.recordRecentTone(toneVal) }
        }
    }

    fun acceptContextualToneSuggestion() {
        val suggestion = _uiState.value.contextualSuggestion ?: return
        val script = _uiState.value.expertScript
        val idx = suggestion.insertIndex.coerceIn(0, script.length)
        val tag = "[ton: ${suggestion.suggestedTone}] "
        val updated = script.substring(0, idx) + tag + script.substring(idx)
        updateExpertScript(updated, idx + tag.length)
        _uiState.update { it.copy(contextualSuggestion = null, dismissedSuggestionIndex = null) }
    }

    fun dismissContextualToneSuggestion() {
        val idx = _uiState.value.contextualSuggestion?.insertIndex
        _uiState.update { it.copy(contextualSuggestion = null, dismissedSuggestionIndex = idx) }
    }

    fun setSelectedVoice(voiceName: String) {
        _uiState.update { it.copy(selectedVoice = voiceName) }
        reparseScript()
        scheduleAutoSave()
    }

    fun setSelectedModel(modelId: String) {
        _uiState.update { it.copy(selectedModelId = modelId) }
        reparseScript()
        scheduleAutoSave()
    }

    private fun reparseScript() {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            val state = _uiState.value
            val customNames = state.customAmbiences.map { it.name }.toSet()
            val (parsed, langDet, suggestion) = withContext(Dispatchers.Default) {
                val p = BracketParser.parse(
                    script = state.expertScript,
                    defaultVoice = state.selectedVoice,
                    defaultTone = state.settings.defaultTone,
                    customAmbienceNames = customNames
                )
                val l = if (state.expertScript.isNotBlank()) LanguageDetector.detect(state.expertScript) else null
                val s = BracketParser.findContextualToneSuggestion(state.expertScript)
                Triple(p, l, s)
            }

            // Met à jour les empreintes et vérifie le cache sur disque pour l'indicateur de chronologie
            val newSegmentStates = HashMap<String, SegmentRuntimeState>()
            for (seg in parsed.segments) {
                val fp = computeSegmentFingerprint(seg)
                val existing = state.segmentStates[seg.id]
                if (existing != null && existing.fingerprint == fp) {
                    newSegmentStates[seg.id] = existing
                } else {
                    val cached = repository.getCachedTake(fp)
                    if (cached != null) {
                        newSegmentStates[seg.id] = SegmentRuntimeState(
                            segmentId = seg.id,
                            fingerprint = fp,
                            status = SegmentStatus.READY,
                            fromCache = true,
                            takes = listOf(cached),
                            activeTakeIndex = 0
                        )
                    } else {
                        newSegmentStates[seg.id] = SegmentRuntimeState(
                            segmentId = seg.id,
                            fingerprint = fp,
                            status = SegmentStatus.PENDING,
                            fromCache = false
                        )
                    }
                }
            }

            val filteredSuggestion = if (suggestion != null && suggestion.insertIndex == state.dismissedSuggestionIndex) {
                null
            } else suggestion

            _uiState.update {
                it.copy(
                    parseResult = parsed,
                    languageDetection = langDet,
                    contextualSuggestion = filteredSuggestion,
                    segmentStates = newSegmentStates
                )
            }
        }
    }

    private fun computeSegmentFingerprint(seg: TimelineEvent.VoiceSegment): String {
        val state = _uiState.value
        val effectiveLang = resolveEffectiveLanguageCode(seg.text)
        val normalizedText = PronunciationNormalizer.applyAll(
            text = seg.text,
            languageCode = effectiveLang,
            normalizeStandardTokens = state.settings.pronunciationNormalizationEnabled,
            customDictionary = state.dictionary
        )
        return PcmUtils.sha256Fingerprint(
            text = normalizedText,
            voice = seg.voice,
            tone = seg.tone,
            speed = seg.speed.labelFr,
            modelId = state.selectedModelId,
            languageCode = effectiveLang
        )
    }

    private fun resolveEffectiveLanguageCode(text: String): String {
        val chosen = _uiState.value.selectedLanguage
        if (chosen != LanguageOption.AUTO) return chosen.code
        return LanguageDetector.detect(text).primaryLanguageCode
    }

    // --- Mode Brief ---

    fun planOrGenerateBrief(previewPlanOnly: Boolean) {
        val state = _uiState.value
        if (state.briefDescription.isBlank() && state.briefRawText.isBlank()) {
            triggerHaptic(isError = true)
            _uiState.update {
                it.copy(errorMessage = "Décrivez ce que vous souhaitez ou collez votre texte avant de lancer la génération.")
            }
            return
        }
        refreshNetworkStatus()
        if (_uiState.value.isOffline) {
            triggerHaptic(isError = true)
            _uiState.update {
                it.copy(errorMessage = "Vous êtes hors ligne. Connectez-vous à Internet pour générer une nouvelle voix off ; vos projets déjà générés restent lisibles.")
            }
            return
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPlanningBrief = true,
                    generationPhase = GenerationPhase.ANALYZING,
                    errorMessage = null
                )
            }
            try {
                delay(180)
                _uiState.update { it.copy(generationPhase = GenerationPhase.SCRIPTING) }

                val langLabel = if (state.selectedLanguage == LanguageOption.AUTO) {
                    "détectée automatiquement (français si la demande est en français)"
                } else state.selectedLanguage.labelFr

                val plan = aiService.generateBriefPlan(
                    description = state.briefDescription,
                    rawText = state.briefRawText,
                    targetDuration = state.targetDuration,
                    languageLabel = langLabel,
                    genderPreference = state.genderPreference,
                    preferredModelId = state.selectedModelId
                )

                _uiState.update {
                    it.copy(
                        briefPlan = plan,
                        isPlanningBrief = false,
                        selectedVoice = plan.selectedVoice,
                        selectedModelId = plan.recommendedModel
                    )
                }

                if (previewPlanOnly) {
                    _uiState.update { it.copy(generationPhase = GenerationPhase.IDLE) }
                    saveProjectImmediately()
                    triggerHaptic(isError = false)
                } else {
                    applyPlanAndGenerate(plan)
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isPlanningBrief = false, generationPhase = GenerationPhase.IDLE) }
                throw e
            } catch (e: BriefPlanningException) {
                triggerHaptic(isError = true)
                _uiState.update {
                    it.copy(
                        isPlanningBrief = false,
                        generationPhase = GenerationPhase.IDLE,
                        mode = StudioMode.EXPERT,
                        expertScript = e.fallbackScript.ifBlank { it.expertScript },
                        errorMessage = "${e.message} Le texte brut a été ouvert dans le Mode Expert pour modification manuelle."
                    )
                }
                reparseScript()
            } catch (e: Exception) {
                triggerHaptic(isError = true)
                _uiState.update {
                    it.copy(
                        isPlanningBrief = false,
                        generationPhase = GenerationPhase.IDLE,
                        errorMessage = aiService.translateAiError(e)
                    )
                }
            }
        }
    }

    fun applyPlanToExpertEditor() {
        val plan = _uiState.value.briefPlan ?: return
        updateExpertScript(plan.annotatedScript, plan.annotatedScript.length)
        _uiState.update {
            it.copy(
                mode = StudioMode.EXPERT,
                selectedVoice = plan.selectedVoice,
                selectedModelId = plan.recommendedModel
            )
        }
        saveNamedVersion("Plan Brief (${plan.selectedVoice})")
    }

    fun generateFromCurrentPlan() {
        val plan = _uiState.value.briefPlan ?: return
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            applyPlanAndGenerate(plan)
        }
    }

    private suspend fun applyPlanAndGenerate(plan: BriefPlan) {
        updateExpertScript(plan.annotatedScript, plan.annotatedScript.length)
        _uiState.update {
            it.copy(
                selectedVoice = plan.selectedVoice,
                selectedModelId = plan.recommendedModel
            )
        }
        saveNamedVersion("Mise en scène (${plan.selectedVoice})")
        delay(80)
        runSequentialGenerationPipeline(resumeOnly = false)
    }

    fun refineExistingScript() {
        val state = _uiState.value
        val instruction = state.refinePrompt.trim()
        if (instruction.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Indiquez ce que vous souhaitez ajuster (ex. « plus lent », « ambiance moins forte »).") }
            return
        }
        if (state.expertScript.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Aucun script à affiner pour l'instant.") }
            return
        }
        refreshNetworkStatus()
        if (_uiState.value.isOffline) {
            _uiState.update { it.copy(errorMessage = "Vous êtes hors ligne. Connectez-vous pour demander un affinage à l'IA.") }
            return
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefining = true, errorMessage = null) }
            try {
                val (updatedScript, explanation) = aiService.refineAnnotatedScript(
                    currentScript = state.expertScript,
                    instruction = instruction,
                    customAmbienceNames = state.customAmbiences.map { it.name }
                )
                updateExpertScript(updatedScript, updatedScript.length)
                saveNamedVersion("Affinage : ${instruction.take(28)}")
                _uiState.update {
                    it.copy(
                        isRefining = false,
                        refinePrompt = "",
                        lastRefinementNote = explanation
                    )
                }
                delay(80)
                runSequentialGenerationPipeline(resumeOnly = false)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isRefining = false) }
                throw e
            } catch (e: Exception) {
                triggerHaptic(isError = true)
                _uiState.update {
                    it.copy(
                        isRefining = false,
                        errorMessage = e.message ?: "Impossible d'affiner le script."
                    )
                }
            }
        }
    }

    // --- Pipeline de génération séquentielle & Mixage ---

    fun generateAudioFromExpertScript(resumeOnly: Boolean = false) {
        val state = _uiState.value
        if (state.parseResult.segments.isEmpty()) {
            triggerHaptic(isError = true)
            _uiState.update {
                it.copy(errorMessage = "Votre script ne contient aucun texte parlé. Écrivez une phrase avant de générer l'audio.")
            }
            return
        }
        val blockingErrors = state.parseResult.diagnostics.filter { it.isError }
        if (blockingErrors.isNotEmpty()) {
            triggerHaptic(isError = true)
            _uiState.update {
                it.copy(errorMessage = "Corrigez le crochet signalé en rouge : ${blockingErrors.first().messageFr}")
            }
            return
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            runSequentialGenerationPipeline(resumeOnly = resumeOnly)
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        _uiState.update {
            it.copy(
                isPlanningBrief = false,
                isRefining = false,
                generationPhase = GenerationPhase.IDLE
            )
        }
        _toastEvents.tryEmit(StudioToastEvent.Info("Génération interrompue. Les segments déjà générés restent en cache."))
    }

    private suspend fun runSequentialGenerationPipeline(resumeOnly: Boolean) {
        // Assurer un parseResult à jour
        val stateSnapshot = _uiState.value
        val customNames = stateSnapshot.customAmbiences.map { it.name }.toSet()
        val parsed = withContext(Dispatchers.Default) {
            BracketParser.parse(
                script = stateSnapshot.expertScript,
                defaultVoice = stateSnapshot.selectedVoice,
                defaultTone = stateSnapshot.settings.defaultTone,
                customAmbienceNames = customNames
            )
        }
        val segments = parsed.segments
        if (segments.isEmpty()) {
            _uiState.update { it.copy(generationPhase = GenerationPhase.IDLE, errorMessage = "Aucun segment parlé détecté.") }
            return
        }

        triggerHaptic(isError = false)
        _uiState.update {
            it.copy(
                parseResult = parsed,
                generationPhase = GenerationPhase.GENERATING_VOICES,
                currentSegmentIndex = 0,
                totalSegmentsCount = segments.size,
                errorMessage = null
            )
        }

        val segmentPcmMap = HashMap<String, String>()
        var anySegmentFailed = false

        for ((idx, seg) in segments.withIndex()) {
            _uiState.update { it.copy(currentSegmentIndex = idx + 1) }

            val currentRuntime = _uiState.value.segmentStates[seg.id]
            val fp = computeSegmentFingerprint(seg)

            // Si reprise ou déjà prêt avec la bonne empreinte
            if (resumeOnly && currentRuntime?.status == SegmentStatus.READY && currentRuntime.fingerprint == fp && currentRuntime.activeTake != null) {
                segmentPcmMap[seg.id] = currentRuntime.activeTake!!.pcmFilePath
                continue
            }

            // Vérifier le cache disque Room par empreinte
            val cached = repository.getCachedTake(fp)
            if (cached != null) {
                _uiState.update { st ->
                    val map = st.segmentStates.toMutableMap()
                    val existingTakes = map[seg.id]?.takes?.takeIf { it.isNotEmpty() } ?: listOf(cached)
                    map[seg.id] = SegmentRuntimeState(
                        segmentId = seg.id,
                        fingerprint = fp,
                        status = SegmentStatus.READY,
                        fromCache = true,
                        takes = existingTakes,
                        activeTakeIndex = (map[seg.id]?.activeTakeIndex ?: 0).coerceIn(0, existingTakes.lastIndex)
                    )
                    st.copy(
                        segmentStates = map,
                        sessionUsage = st.sessionUsage.copy(cachedHitsCount = st.sessionUsage.cachedHitsCount + 1)
                    )
                }
                val chosenPath = _uiState.value.segmentStates[seg.id]?.activeTake?.pcmFilePath ?: cached.pcmFilePath
                segmentPcmMap[seg.id] = chosenPath
                continue
            }

            // Appel Gemini TTS réel
            _uiState.update { st ->
                val map = st.segmentStates.toMutableMap()
                map[seg.id] = (map[seg.id] ?: SegmentRuntimeState(seg.id, fp)).copy(
                    status = SegmentStatus.GENERATING,
                    errorMessage = null
                )
                st.copy(segmentStates = map)
            }

            try {
                val effectiveLang = resolveEffectiveLanguageCode(seg.text)
                val normalizedText = PronunciationNormalizer.applyAll(
                    text = seg.text,
                    languageCode = effectiveLang,
                    normalizeStandardTokens = _uiState.value.settings.pronunciationNormalizationEnabled,
                    customDictionary = _uiState.value.dictionary
                )
                val samples = aiService.synthesizeSegmentPcm(
                    spokenText = normalizedText,
                    voiceName = seg.voice,
                    tone = seg.tone,
                    speed = seg.speed,
                    activeAmbience = seg.activeAmbience,
                    modelId = _uiState.value.selectedModelId,
                    languageCode = effectiveLang
                )
                val take = repository.saveSegmentTakeToDisk(fp, samples, saveAsPrimaryCache = true)
                settingsStore.incrementUsage(normalizedText.length)

                _uiState.update { st ->
                    val map = st.segmentStates.toMutableMap()
                    map[seg.id] = SegmentRuntimeState(
                        segmentId = seg.id,
                        fingerprint = fp,
                        status = SegmentStatus.READY,
                        fromCache = false,
                        takes = listOf(take),
                        activeTakeIndex = 0
                    )
                    st.copy(
                        segmentStates = map,
                        sessionUsage = st.sessionUsage.copy(
                            apiCallsCount = st.sessionUsage.apiCallsCount + 1,
                            generatedCharactersCount = st.sessionUsage.generatedCharactersCount + normalizedText.length
                        )
                    )
                }
                segmentPcmMap[seg.id] = take.pcmFilePath
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                anySegmentFailed = true
                triggerHaptic(isError = true)
                val msg = e.message ?: "Échec de génération du segment ${idx + 1}"
                _uiState.update { st ->
                    val map = st.segmentStates.toMutableMap()
                    map[seg.id] = (map[seg.id] ?: SegmentRuntimeState(seg.id, fp)).copy(
                        status = SegmentStatus.ERROR,
                        errorMessage = msg
                    )
                    val cleanMsg = msg.trimEnd('.', ' ')
                    st.copy(
                        segmentStates = map,
                        generationPhase = GenerationPhase.IDLE,
                        errorMessage = "Segment ${idx + 1} sur ${segments.size} en erreur : $cleanMsg. Touchez « Reprendre » pour continuer sans perdre les segments déjà prêts."
                    )
                }
                break
            }
        }

        if (anySegmentFailed) return

        // Étape Mixage final
        mixCurrentSegments(parsed.events, segmentPcmMap, notifyOnFinish = true)
    }

    private suspend fun mixCurrentSegments(
        events: List<TimelineEvent>,
        segmentPcmMap: Map<String, String>,
        notifyOnFinish: Boolean
    ) {
        _uiState.update { it.copy(generationPhase = GenerationPhase.MIXING) }
        val st = _uiState.value
        val customPaths = st.customAmbiences.associate { it.name to it.pcmFilePath }

        val mixResult = withContext(Dispatchers.Default) {
            AudioMixerEngine.mixTimeline(
                events = events,
                segmentPcmPaths = segmentPcmMap,
                mixConfig = st.mixConfig,
                customAmbiencePaths = customPaths
            )
        }

        mixedFinalPcm = mixResult.mixedPcm
        voiceOnlyFinalPcm = mixResult.voiceOnlyPcm

        val activeBuffer = if (st.isVoiceOnlyPreview) voiceOnlyFinalPcm else mixedFinalPcm
        playerEngine.loadBuffer("final", activeBuffer, autoPlay = false)

        var savedGenId = _uiState.value.activeGenerationId
        if (notifyOnFinish && mixResult.mixedPcm.isNotEmpty()) {
            val currentState = _uiState.value
            val segCount = events.count { it is TimelineEvent.VoiceSegment }
            val ambSummary = currentState.briefPlan?.ambiences
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { "${it.name} (${it.volumePercent}%)" }
                ?: events.filterIsInstance<TimelineEvent.AmbienceEvent>()
                    .mapNotNull { it.ambienceName }
                    .distinct()
                    .joinToString(", ")
            val genTitle = currentState.projectTitle.ifBlank {
                currentState.briefDescription.trim().take(36).ifBlank {
                    "Génération ${currentState.generations.size + 1}"
                }
            }
            val saved = repository.saveGenerationRecord(
                projectId = currentState.activeProjectId,
                title = genTitle,
                sourceMode = currentState.mode,
                annotatedScript = currentState.expertScript,
                directorExplanation = currentState.briefPlan?.directorExplanation.orEmpty(),
                selectedVoice = currentState.selectedVoice,
                modelId = currentState.selectedModelId,
                mixedPcm = mixResult.mixedPcm,
                voiceOnlyPcm = mixResult.voiceOnlyPcm,
                metrics = mixResult.metrics,
                ambiencesSummary = ambSummary,
                segmentCount = segCount
            )
            savedGenId = saved.id
        }

        _uiState.update {
            it.copy(
                generationPhase = GenerationPhase.DONE,
                audioMetrics = mixResult.metrics,
                hasFinalAudio = activeBuffer.isNotEmpty(),
                activeGenerationId = savedGenId
            )
        }
        saveProjectImmediately()

        if (notifyOnFinish) {
            triggerHaptic(isError = false)
            if (_uiState.value.settings.completionSoundEnabled) {
                playCompletionChime()
            }
            if (isAppInBackground) {
                postAudioReadyNotification()
            }
            delay(900)
            _uiState.update { it.copy(generationPhase = GenerationPhase.IDLE) }
        } else {
            _uiState.update { it.copy(generationPhase = GenerationPhase.IDLE) }
        }
    }

    fun selectAndPlayGeneration(record: GenerationRecord) {
        viewModelScope.launch {
            val isSameActive = _uiState.value.activeGenerationId == record.id && _uiState.value.hasFinalAudio
            if (isSameActive) {
                switchToFinalPlayback()
            } else {
                loadGenerationIntoPlayer(record, autoPlay = true)
            }
        }
    }

    private suspend fun loadGenerationIntoPlayer(record: GenerationRecord, autoPlay: Boolean) {
        val loadedMixed = withContext(Dispatchers.IO) { PcmUtils.readFloatPcmFromFile(File(record.mixedPcmPath)) }
        if (loadedMixed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Le fichier audio de cette génération n'est plus disponible sur l'appareil.") }
            return
        }
        val loadedVoiceOnly = withContext(Dispatchers.IO) { PcmUtils.readFloatPcmFromFile(File(record.voiceOnlyPcmPath)) }
        mixedFinalPcm = loadedMixed
        voiceOnlyFinalPcm = if (loadedVoiceOnly.isNotEmpty()) loadedVoiceOnly else loadedMixed

        val st = _uiState.value
        val activeBuf = if (st.isVoiceOnlyPreview) voiceOnlyFinalPcm else mixedFinalPcm
        val peaks = record.waveformPeaks.ifEmpty {
            withContext(Dispatchers.Default) { PcmUtils.computeWaveformPeaks(activeBuf) }
        }
        val restoredMetrics = AudioMetrics(
            durationSec = record.durationSec,
            peakDb = record.peakDb,
            integratedLufs = record.integratedLufs,
            isClippingRisk = false,
            isTooQuiet = record.integratedLufs < -28f,
            waveformPeaks = peaks,
            segmentTimings = emptyList()
        )
        playerEngine.loadBuffer("final", activeBuf, autoPlay = autoPlay)
        _uiState.update {
            it.copy(
                activeGenerationId = record.id,
                audioMetrics = restoredMetrics,
                hasFinalAudio = true
            )
        }
    }

    fun openGenerationInExpert(record: GenerationRecord) {
        updateExpertScript(record.annotatedScript, record.annotatedScript.length)
        _uiState.update {
            it.copy(
                mode = StudioMode.EXPERT,
                selectedVoice = record.selectedVoice,
                selectedModelId = record.modelId
            )
        }
    }

    fun deleteGeneration(record: GenerationRecord) {
        viewModelScope.launch {
            val wasActive = _uiState.value.activeGenerationId == record.id
            repository.deleteGeneration(record)
            if (wasActive) {
                playerEngine.stop()
                mixedFinalPcm = FloatArray(0)
                voiceOnlyFinalPcm = FloatArray(0)
                _uiState.update {
                    it.copy(
                        activeGenerationId = null,
                        hasFinalAudio = false,
                        audioMetrics = null
                    )
                }
            }
            _toastEvents.tryEmit(StudioToastEvent.Info("Génération supprimée."))
        }
    }

    fun retrySingleSegment(segmentId: String, asNewTake: Boolean = false) {
        val seg = _uiState.value.parseResult.segments.firstOrNull { it.id == segmentId } ?: return
        refreshNetworkStatus()
        if (_uiState.value.isOffline) {
            _uiState.update { it.copy(errorMessage = "Hors ligne : connexion requise pour régénérer ce segment.") }
            return
        }

        viewModelScope.launch {
            val fp = computeSegmentFingerprint(seg)
            _uiState.update { st ->
                val map = st.segmentStates.toMutableMap()
                map[segmentId] = (map[segmentId] ?: SegmentRuntimeState(segmentId, fp)).copy(
                    status = SegmentStatus.GENERATING,
                    errorMessage = null
                )
                st.copy(segmentStates = map, errorMessage = null)
            }

            try {
                val effectiveLang = resolveEffectiveLanguageCode(seg.text)
                val normalizedText = PronunciationNormalizer.applyAll(
                    text = seg.text,
                    languageCode = effectiveLang,
                    normalizeStandardTokens = _uiState.value.settings.pronunciationNormalizationEnabled,
                    customDictionary = _uiState.value.dictionary
                )
                val samples = aiService.synthesizeSegmentPcm(
                    spokenText = normalizedText,
                    voiceName = seg.voice,
                    tone = seg.tone,
                    speed = seg.speed,
                    activeAmbience = seg.activeAmbience,
                    modelId = _uiState.value.selectedModelId,
                    languageCode = effectiveLang
                )
                val newTake = repository.saveSegmentTakeToDisk(
                    fingerprint = fp,
                    samples = samples,
                    saveAsPrimaryCache = !asNewTake
                )
                settingsStore.incrementUsage(normalizedText.length)

                _uiState.update { st ->
                    val map = st.segmentStates.toMutableMap()
                    val prevTakes = if (asNewTake) map[segmentId]?.takes.orEmpty() else emptyList()
                    // Limiter à 5 prises par segment pour préserver la mémoire
                    val updatedTakes = (prevTakes + newTake).takeLast(5)
                    map[segmentId] = SegmentRuntimeState(
                        segmentId = segmentId,
                        fingerprint = fp,
                        status = SegmentStatus.READY,
                        fromCache = false,
                        takes = updatedTakes,
                        activeTakeIndex = updatedTakes.lastIndex
                    )
                    st.copy(
                        segmentStates = map,
                        sessionUsage = st.sessionUsage.copy(
                            apiCallsCount = st.sessionUsage.apiCallsCount + 1,
                            generatedCharactersCount = st.sessionUsage.generatedCharactersCount + normalizedText.length
                        )
                    )
                }

                // Re-mixer immédiatement si tous les segments sont prêts
                remixIfAllSegmentsReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                triggerHaptic(isError = true)
                _uiState.update { st ->
                    val map = st.segmentStates.toMutableMap()
                    map[segmentId] = (map[segmentId] ?: SegmentRuntimeState(segmentId, fp)).copy(
                        status = SegmentStatus.ERROR,
                        errorMessage = e.message ?: "Échec de régénération"
                    )
                    st.copy(segmentStates = map, errorMessage = e.message)
                }
            }
        }
    }

    fun selectSegmentTake(segmentId: String, takeIndex: Int) {
        _uiState.update { st ->
            val map = st.segmentStates.toMutableMap()
            val cur = map[segmentId] ?: return@update st
            if (takeIndex !in cur.takes.indices) return@update st
            map[segmentId] = cur.copy(activeTakeIndex = takeIndex)
            st.copy(segmentStates = map)
        }
        viewModelScope.launch { remixIfAllSegmentsReady() }
    }

    fun playSegmentSolo(segmentId: String) {
        val runtime = _uiState.value.segmentStates[segmentId] ?: return
        val take = runtime.activeTake ?: return
        val samples = PcmUtils.readFloatPcmFromFile(File(take.pcmFilePath))
        if (samples.isNotEmpty()) {
            playerEngine.playSamplesOnce("segment:$segmentId", samples)
        }
    }

    fun auditionSelectedPassage(passageText: String) {
        val cleaned = passageText.replace(Regex("\\[[^\\]]*\\]"), " ").trim()
        if (cleaned.isBlank()) {
            _toastEvents.tryEmit(StudioToastEvent.Info("Sélectionnez du texte parlé dans l'éditeur pour l'écouter."))
            return
        }
        viewModelScope.launch {
            try {
                val st = _uiState.value
                val samples = aiService.synthesizeSegmentPcm(
                    spokenText = cleaned.take(450),
                    voiceName = st.selectedVoice,
                    tone = st.settings.defaultTone,
                    speed = com.example.domain.model.SpeechSpeed.NORMAL,
                    activeAmbience = null,
                    modelId = st.selectedModelId,
                    languageCode = resolveEffectiveLanguageCode(cleaned)
                )
                playerEngine.playSamplesOnce("passage", samples)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = aiService.translateAiError(e)) }
            }
        }
    }

    private suspend fun remixIfAllSegmentsReady() {
        val st = _uiState.value
        val segs = st.parseResult.segments
        if (segs.isEmpty()) return
        val pcmMap = HashMap<String, String>()
        for (s in segs) {
            val path = st.segmentStates[s.id]?.activeTake?.pcmFilePath ?: return
            pcmMap[s.id] = path
        }
        mixCurrentSegments(st.parseResult.events, pcmMap, notifyOnFinish = false)
    }

    // --- Réglages de mixage en direct ---

    fun updateMixConfig(transform: (MixConfig) -> MixConfig) {
        _uiState.update { it.copy(mixConfig = transform(it.mixConfig)) }
        scheduleAutoSave()
        viewModelScope.launch {
            remixIfAllSegmentsReady()
        }
    }

    fun toggleVoiceOnlyPreview(voiceOnly: Boolean) {
        _uiState.update { it.copy(isVoiceOnlyPreview = voiceOnly) }
        val buf = if (voiceOnly) voiceOnlyFinalPcm else mixedFinalPcm
        if (buf.isNotEmpty()) {
            playerEngine.loadBuffer("final", buf, autoPlay = false)
        }
    }

    fun switchToFinalPlayback() {
        val buf = if (_uiState.value.isVoiceOnlyPreview) voiceOnlyFinalPcm else mixedFinalPcm
        if (buf.isNotEmpty()) {
            if (playbackState.value.activeTag != "final") {
                playerEngine.loadBuffer("final", buf, autoPlay = true)
            } else {
                playerEngine.togglePlayPause()
            }
        }
    }

    // --- Échantillons de voix & Test A/B ---

    fun auditionVoiceSample(voiceName: String) {
        if (_uiState.value.isOffline) {
            _uiState.update { it.copy(voicePreviewError = "Connexion Internet requise pour écouter un échantillon de voix.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(previewingVoiceName = voiceName, voicePreviewError = null) }
            try {
                val lang = _uiState.value.selectedLanguage
                val sampleLine = if (lang == LanguageOption.EN_US || lang == LanguageOption.EN_GB) {
                    "Hello, I am $voiceName, ready to bring your script to life in Voxa Studio."
                } else {
                    "Bonjour, je suis $voiceName, prête à donner vie à votre texte dans Voxa Studio."
                }
                val fp = PcmUtils.sha256Fingerprint(
                    text = sampleLine,
                    voice = voiceName,
                    tone = "naturel et chaleureux",
                    speed = "normal",
                    modelId = _uiState.value.selectedModelId,
                    languageCode = if (lang == LanguageOption.AUTO) "fr-FR" else lang.code
                )
                val cached = repository.getCachedTake(fp)
                val samples = if (cached != null) {
                    PcmUtils.readFloatPcmFromFile(File(cached.pcmFilePath))
                } else {
                    val generated = aiService.synthesizeSegmentPcm(
                        spokenText = sampleLine,
                        voiceName = voiceName,
                        tone = "naturel et chaleureux",
                        speed = com.example.domain.model.SpeechSpeed.NORMAL,
                        activeAmbience = null,
                        modelId = _uiState.value.selectedModelId,
                        languageCode = if (lang == LanguageOption.AUTO) "fr-FR" else lang.code
                    )
                    repository.saveSegmentTakeToDisk(fp, generated, saveAsPrimaryCache = true)
                    settingsStore.incrementUsage(sampleLine.length)
                    generated
                }
                playerEngine.playSamplesOnce("voice:$voiceName", samples)
            } catch (e: Exception) {
                _uiState.update { it.copy(voicePreviewError = aiService.translateAiError(e)) }
            } finally {
                _uiState.update { it.copy(previewingVoiceName = null) }
            }
        }
    }

    fun updateAbTestPhrase(phrase: String) {
        _uiState.update { it.copy(abTestPhrase = phrase) }
    }

    fun updateAbSlot(isSlotA: Boolean, voiceName: String? = null, tone: String? = null) {
        _uiState.update { st ->
            if (isSlotA) {
                st.copy(abSlotA = st.abSlotA.copy(voiceName = voiceName ?: st.abSlotA.voiceName, tone = tone ?: st.abSlotA.tone))
            } else {
                st.copy(abSlotB = st.abSlotB.copy(voiceName = voiceName ?: st.abSlotB.voiceName, tone = tone ?: st.abSlotB.tone))
            }
        }
    }

    fun runAbComparison() {
        val phrase = _uiState.value.abTestPhrase.trim()
        if (phrase.isBlank()) {
            _toastEvents.tryEmit(StudioToastEvent.Info("Saisissez une phrase à comparer avant de lancer le test A/B."))
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    abSlotA = it.abSlotA.copy(isLoading = true, error = null),
                    abSlotB = it.abSlotB.copy(isLoading = true, error = null)
                )
            }
            val lang = resolveEffectiveLanguageCode(phrase)
            val modelId = _uiState.value.selectedModelId

            suspend fun synthSlot(slot: AbTestSlot): AbTestSlot {
                return try {
                    val pcm = aiService.synthesizeSegmentPcm(
                        spokenText = phrase,
                        voiceName = slot.voiceName,
                        tone = slot.tone,
                        speed = com.example.domain.model.SpeechSpeed.NORMAL,
                        activeAmbience = null,
                        modelId = modelId,
                        languageCode = lang
                    )
                    settingsStore.incrementUsage(phrase.length)
                    slot.copy(
                        isLoading = false,
                        pcmSamples = pcm,
                        durationSec = pcm.size.toFloat() / PcmUtils.SAMPLE_RATE
                    )
                } catch (e: Exception) {
                    slot.copy(isLoading = false, error = aiService.translateAiError(e))
                }
            }

            val resA = synthSlot(_uiState.value.abSlotA)
            _uiState.update { it.copy(abSlotA = resA) }
            val resB = synthSlot(_uiState.value.abSlotB)
            _uiState.update { it.copy(abSlotB = resB) }
        }
    }

    fun playAbSlot(isSlotA: Boolean) {
        val slot = if (isSlotA) _uiState.value.abSlotA else _uiState.value.abSlotB
        val samples = slot.pcmSamples ?: return
        playerEngine.playSamplesOnce(if (isSlotA) "ab:A" else "ab:B", samples)
    }

    // --- Export & Partage ---

    fun getExportBuffer(voiceOnly: Boolean): FloatArray {
        return if (voiceOnly) voiceOnlyFinalPcm else mixedFinalPcm
    }

    fun exportAudioToUri(uri: Uri, format: AudioExportFormat, voiceOnly: Boolean) {
        val samples = getExportBuffer(voiceOnly)
        if (samples.isEmpty()) {
            _toastEvents.tryEmit(StudioToastEvent.Info("Aucun audio généré à exporter."))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AudioExporter.writeToSafUri(appContext, samples, format, uri)
                _toastEvents.tryEmit(StudioToastEvent.Info("Fichier ${format.extension.uppercase()} enregistré avec succès."))
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Échec de l'export : ${e.localizedMessage}") }
            }
        }
    }

    fun createShareAudioIntent(format: AudioExportFormat, voiceOnly: Boolean) =
        AudioExporter.createShareIntent(
            context = appContext,
            samples = getExportBuffer(voiceOnly),
            format = format,
            baseName = _uiState.value.projectTitle.ifBlank { "voxa_studio_audio" }
        )

    // --- Projets, Versions, Dictionnaire, Ambiances & Réglages ---

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(650)
            saveProjectImmediately()
        }
    }

    private suspend fun saveProjectImmediately() {
        val st = _uiState.value
        val hasContent = st.briefDescription.isNotBlank() ||
            st.briefRawText.isNotBlank() ||
            st.expertScript.isNotBlank() ||
            st.projectTitle.isNotBlank()
        if (!hasContent) return

        val computedTitle = st.projectTitle.ifBlank {
            val source = st.briefDescription.ifBlank { st.briefRawText }.ifBlank { st.expertScript }
            source.replace(Regex("\\[[^\\]]*\\]"), "").trim().take(36).ifBlank { "Projet sans titre" }
        }
        val project = ProjectItem(
            id = st.activeProjectId,
            title = computedTitle,
            mode = st.mode,
            briefDescription = st.briefDescription,
            briefRawText = st.briefRawText,
            expertScript = st.expertScript,
            defaultVoice = st.selectedVoice,
            ttsModelId = st.selectedModelId,
            languageCode = st.selectedLanguage.code,
            mixConfig = st.mixConfig,
            updatedAt = System.currentTimeMillis()
        )
        repository.saveProject(project)
        settingsStore.setActiveProjectId(project.id)
        _uiState.update { it.copy(saveStatusLabel = "Enregistré") }
    }

    fun createNewBlankProject() {
        val newId = UUID.randomUUID().toString()
        val settings = _uiState.value.settings
        playerEngine.stop()
        mixedFinalPcm = FloatArray(0)
        voiceOnlyFinalPcm = FloatArray(0)
        undoStack.clear()
        redoStack.clear()
        _uiState.update {
            StudioUiState(
                activeProjectId = newId,
                mode = StudioMode.BRIEF,
                selectedVoice = settings.defaultVoice,
                selectedModelId = settings.defaultModelId,
                selectedLanguage = LanguageOption.fromCode(settings.defaultLanguageCode),
                projects = it.projects,
                dictionary = it.dictionary,
                customAmbiences = it.customAmbiences,
                recentTones = it.recentTones,
                settings = settings,
                sessionUsage = it.sessionUsage,
                cumulativeUsage = it.cumulativeUsage,
                isOffline = it.isOffline
            )
        }
        observeProjectVersions(newId)
        observeProjectGenerations(newId)
        viewModelScope.launch { settingsStore.setActiveProjectId(newId) }
    }

    fun openProject(project: ProjectItem) {
        viewModelScope.launch {
            loadProjectIntoEditor(project, _uiState.value.settings)
            settingsStore.setActiveProjectId(project.id)
        }
    }

    private fun loadProjectIntoEditor(project: ProjectItem, settings: StudioSettings) {
        playerEngine.stop()
        mixedFinalPcm = FloatArray(0)
        voiceOnlyFinalPcm = FloatArray(0)
        undoStack.clear()
        redoStack.clear()

        _uiState.update {
            it.copy(
                activeProjectId = project.id,
                projectTitle = project.title,
                mode = project.mode,
                briefDescription = project.briefDescription,
                briefRawText = project.briefRawText,
                expertScript = project.expertScript,
                cursorPosition = project.expertScript.length,
                selectedVoice = project.defaultVoice,
                selectedModelId = project.ttsModelId,
                selectedLanguage = LanguageOption.fromCode(project.languageCode),
                mixConfig = project.mixConfig,
                briefPlan = null,
                generations = emptyList(),
                activeGenerationId = null,
                hasFinalAudio = false,
                audioMetrics = null,
                settings = settings
            )
        }
        observeProjectVersions(project.id)
        observeProjectGenerations(project.id)
        reparseScript()
    }

    fun duplicateProject(project: ProjectItem) {
        viewModelScope.launch {
            val copy = project.copy(
                id = UUID.randomUUID().toString(),
                title = "${project.title} (copie)",
                updatedAt = System.currentTimeMillis()
            )
            repository.saveProject(copy)
            _toastEvents.tryEmit(StudioToastEvent.Info("Projet dupliqué."))
        }
    }

    fun deleteProject(project: ProjectItem) {
        viewModelScope.launch {
            repository.deleteProject(project.id)
            if (_uiState.value.activeProjectId == project.id) {
                createNewBlankProject()
            }
            _toastEvents.tryEmit(StudioToastEvent.UndoDeleteProject(project))
        }
    }

    fun restoreDeletedProject(project: ProjectItem) {
        viewModelScope.launch {
            repository.saveProject(project)
            openProject(project)
        }
    }

    fun saveNamedVersion(label: String) {
        val st = _uiState.value
        if (st.expertScript.isBlank()) return
        viewModelScope.launch {
            repository.saveScriptVersion(st.activeProjectId, st.expertScript, label)
        }
    }

    fun restoreVersion(version: ScriptVersion) {
        updateExpertScript(version.script, version.script.length)
        _uiState.update { it.copy(mode = StudioMode.EXPERT) }
        _toastEvents.tryEmit(StudioToastEvent.Info("Version restaurée."))
    }

    fun exportCurrentProjectJsonString(): String {
        val st = _uiState.value
        val project = ProjectItem(
            id = st.activeProjectId,
            title = st.projectTitle.ifBlank { "Projet Voxa Studio" },
            mode = st.mode,
            briefDescription = st.briefDescription,
            briefRawText = st.briefRawText,
            expertScript = st.expertScript,
            defaultVoice = st.selectedVoice,
            ttsModelId = st.selectedModelId,
            languageCode = st.selectedLanguage.code,
            mixConfig = st.mixConfig,
            updatedAt = System.currentTimeMillis()
        )
        return repository.exportProjectToJson(project)
    }

    fun importFileFromUri(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Impossible de lire le fichier sélectionné.")
                val lower = displayName.lowercase()
                if (lower.endsWith(".json") || raw.trimStart().startsWith("{")) {
                    val imported = repository.parseImportedProjectJson(
                        rawJson = raw,
                        fallbackVoice = _uiState.value.settings.defaultVoice,
                        fallbackModel = _uiState.value.settings.defaultModelId
                    )
                    repository.saveProject(imported)
                    withContext(Dispatchers.Main) {
                        openProject(imported)
                        _toastEvents.tryEmit(StudioToastEvent.Info("Projet « ${imported.title} » importé."))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        updateExpertScript(raw, raw.length)
                        _uiState.update { it.copy(mode = StudioMode.EXPERT) }
                        _toastEvents.tryEmit(StudioToastEvent.Info("Texte importé dans l'éditeur."))
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Import impossible : ${e.localizedMessage}") }
            }
        }
    }

    fun importCustomAmbience(name: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val imported = repository.importCustomAmbience(name, uri)
                _toastEvents.tryEmit(
                    StudioToastEvent.Info("Ambiance « ${imported.name} » ajoutée. Utilisez [ambiance: ${imported.name}, 25%].")
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Impossible d'importer l'audio : ${e.localizedMessage}") }
            }
        }
    }

    fun deleteCustomAmbience(amb: com.example.domain.model.CustomAmbience) {
        viewModelScope.launch {
            repository.deleteCustomAmbience(amb)
            _toastEvents.tryEmit(StudioToastEvent.Info("Ambiance supprimée."))
        }
    }

    fun addPronunciationEntry(word: String, phonetic: String) {
        if (word.isBlank() || phonetic.isBlank()) return
        viewModelScope.launch {
            repository.upsertPronunciation(word, phonetic)
            _toastEvents.tryEmit(StudioToastEvent.Info("Règle de prononciation ajoutée."))
        }
    }

    fun deletePronunciationEntry(id: String) {
        viewModelScope.launch {
            repository.deletePronunciation(id)
        }
    }

    fun updateSettings(transform: (StudioSettings) -> StudioSettings) {
        viewModelScope.launch {
            settingsStore.updateSettings(transform)
        }
    }

    fun showInfoMessage(message: String) {
        _toastEvents.tryEmit(StudioToastEvent.Info(message))
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, voicePreviewError = null) }
    }

    // --- Retours haptiques, son de fin et notification ---

    fun triggerHaptic(isError: Boolean = false) {
        if (!_uiState.value.settings.hapticsEnabled) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = if (isError) longArrayOf(0, 28, 35, 28) else longArrayOf(0, 18)
                val amps = if (isError) intArrayOf(0, 140, 0, 140) else intArrayOf(0, 90)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (isError) 45L else 18L)
            }
        } catch (_: Exception) {
        }
    }

    private fun playCompletionChime() {
        val chime = AmbienceSynthesizer.generateCompletionChime()
        playerEngine.playSamplesOnce("chime", chime)
        // Recharge ensuite le tampon final sans interrompre le chime immédiatement
        viewModelScope.launch {
            delay(650)
            if (playbackState.value.activeTag == "chime") {
                val buf = if (_uiState.value.isVoiceOnlyPreview) voiceOnlyFinalPcm else mixedFinalPcm
                if (buf.isNotEmpty()) {
                    playerEngine.loadBuffer("final", buf, autoPlay = false)
                }
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                "voxa_render",
                appContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = appContext.getString(R.string.notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postAudioReadyNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val notification = NotificationCompat.Builder(appContext, "voxa_render")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appContext.getString(R.string.notification_audio_ready_title))
            .setContentText(appContext.getString(R.string.notification_audio_ready_body))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(1042, notification)
    }

    override fun onCleared() {
        super.onCleared()
        playerEngine.release()
    }
}

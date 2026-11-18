package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import com.example.ai.ApiPoolCoordinator
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
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiPoolStrategy
import com.example.domain.model.AudioMetrics
import com.example.domain.model.BriefPlan
import com.example.domain.model.DialogueCompiler
import com.example.domain.model.DialogueLine
import com.example.domain.model.GenerationRecord
import com.example.domain.model.LanguageOption
import com.example.domain.model.MixConfig
import com.example.domain.model.ProjectItem
import com.example.domain.model.ScriptVersion
import com.example.domain.model.SegmentRuntimeState
import com.example.domain.model.SegmentStatus
import com.example.domain.model.SpeechSpeed
import com.example.domain.model.StudioMode
import com.example.domain.model.StudioPreset
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
                repository.allGenerationsFlow.collect { allGens ->
                    _uiState.update { it.copy(allGenerations = allGens) }
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

    // --- Modèles Studio (Presets) ---

    fun applyStudioPreset(preset: StudioPreset) {
        _uiState.update {
            it.copy(
                appliedPresetId = preset.id,
                projectTitle = it.projectTitle.ifBlank { preset.titleFr },
                briefDescription = preset.briefDescription,
                briefRawText = if (preset.sampleRawText.isNotBlank()) preset.sampleRawText else it.briefRawText,
                targetDuration = preset.targetDuration,
                genderPreference = preset.genderPreference,
                selectedVoice = preset.suggestedVoice,
                mixConfig = preset.mixConfig,
                briefPlan = null,
                errorMessage = null
            )
        }
        scheduleAutoSave()
        _toastEvents.tryEmit(StudioToastEvent.Info("Modèle « ${preset.titleFr} » appliqué."))
    }

    // --- Mode Dialogue multi-voix ---

    fun addDialogueLine() {
        _uiState.update { st ->
            val lastVoice = st.dialogueLines.lastOrNull()?.voiceName ?: st.selectedVoice
            val nextVoice = if (lastVoice.equals("Kore", ignoreCase = true)) "Puck" else "Kore"
            val nextLine = DialogueLine(
                id = UUID.randomUUID().toString(),
                voiceName = nextVoice,
                tone = st.settings.defaultTone,
                speed = SpeechSpeed.NORMAL,
                pauseBeforeSec = if (st.dialogueLines.isEmpty()) 0f else 0.4f,
                text = ""
            )
            st.copy(dialogueLines = st.dialogueLines + nextLine)
        }
    }

    fun updateDialogueLine(lineId: String, transform: (DialogueLine) -> DialogueLine) {
        _uiState.update { st ->
            st.copy(
                dialogueLines = st.dialogueLines.map { line ->
                    if (line.id == lineId) transform(line) else line
                }
            )
        }
    }

    fun removeDialogueLine(lineId: String) {
        _uiState.update { st ->
            if (st.dialogueLines.size <= 1) {
                st.copy(dialogueLines = st.dialogueLines.map { if (it.id == lineId) it.copy(text = "") else it })
            } else {
                st.copy(dialogueLines = st.dialogueLines.filterNot { it.id == lineId })
            }
        }
    }

    fun moveDialogueLine(index: Int, delta: Int) {
        _uiState.update { st ->
            val target = index + delta
            if (index !in st.dialogueLines.indices || target !in st.dialogueLines.indices) return@update st
            val mutable = st.dialogueLines.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(target, item)
            st.copy(dialogueLines = mutable)
        }
    }

    fun setDialogueAmbience(name: String?, volumePercent: Int? = null) {
        _uiState.update { st ->
            st.copy(
                dialogueAmbienceName = name,
                dialogueAmbienceVolumePercent = (volumePercent ?: st.dialogueAmbienceVolumePercent).coerceIn(5, 80)
            )
        }
    }

    fun openDialogueInExpert() {
        val st = _uiState.value
        val compiled = DialogueCompiler.compileScript(
            lines = st.dialogueLines,
            ambienceName = st.dialogueAmbienceName,
            ambienceVolumePercent = st.dialogueAmbienceVolumePercent
        )
        if (compiled.isBlank()) {
            _toastEvents.tryEmit(StudioToastEvent.Info("Écrivez au moins une réplique avant d'ouvrir dans l'éditeur Expert."))
            return
        }
        val firstVoice = st.dialogueLines.firstOrNull { it.text.isNotBlank() }?.voiceName ?: st.selectedVoice
        updateExpertScript(compiled, compiled.length)
        _uiState.update { it.copy(mode = StudioMode.EXPERT, selectedVoice = firstVoice) }
        saveNamedVersion("Dialogue multi-voix")
    }

    fun generateFromDialogue() {
        val st = _uiState.value
        val compiled = DialogueCompiler.compileScript(
            lines = st.dialogueLines,
            ambienceName = st.dialogueAmbienceName,
            ambienceVolumePercent = st.dialogueAmbienceVolumePercent
        )
        if (compiled.isBlank()) {
            triggerHaptic(isError = true)
            _uiState.update { it.copy(errorMessage = "Ajoutez du texte à au moins une réplique pour générer le dialogue.") }
            return
        }
        refreshNetworkStatus()
        if (_uiState.value.isOffline) {
            triggerHaptic(isError = true)
            _uiState.update { it.copy(errorMessage = "Connexion Internet requise pour générer les voix du dialogue.") }
            return
        }
        val firstVoice = st.dialogueLines.firstOrNull { it.text.isNotBlank() }?.voiceName ?: st.selectedVoice
        updateExpertScript(compiled, compiled.length)
        _uiState.update { it.copy(selectedVoice = firstVoice) }
        saveNamedVersion("Dialogue (${st.dialogueLines.count { it.text.isNotBlank() }} répliques)")
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            delay(60)
            runSequentialGenerationPipeline(resumeOnly = false)
        }
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

                val activeKeys = state.settings.activeApiKeys()
                val plan = aiService.generateBriefPlan(
                    description = state.briefDescription,
                    rawText = state.briefRawText,
                    targetDuration = state.targetDuration,
                    languageLabel = langLabel,
                    genderPreference = state.genderPreference,
                    preferredModelId = state.selectedModelId,
                    apiKeys = activeKeys,
                    poolStrategy = state.settings.apiPoolStrategy,
                    allowModelFallback = state.settings.modelFallbackOnQuota
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
                        errorMessage = aiService.translateAiError(
                            e,
                            usingPersonalKey = state.settings.activeApiKeys().isNotEmpty()
                        )
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
                    customAmbienceNames = state.customAmbiences.map { it.name },
                    apiKeys = state.settings.activeApiKeys(),
                    poolStrategy = state.settings.apiPoolStrategy,
                    allowModelFallback = state.settings.modelFallbackOnQuota
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
        val pendingSegments = mutableListOf<Triple<Int, TimelineEvent.VoiceSegment, String>>()
        val completedCount = AtomicInteger(0)

        for ((idx, seg) in segments.withIndex()) {
            val currentRuntime = _uiState.value.segmentStates[seg.id]
            val fp = computeSegmentFingerprint(seg)

            // Si reprise ou déjà prêt avec la bonne empreinte
            if (resumeOnly && currentRuntime?.status == SegmentStatus.READY && currentRuntime.fingerprint == fp && currentRuntime.activeTake != null) {
                segmentPcmMap[seg.id] = currentRuntime.activeTake!!.pcmFilePath
                val done = completedCount.incrementAndGet()
                _uiState.update { it.copy(currentSegmentIndex = done) }
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
                val done = completedCount.incrementAndGet()
                _uiState.update { it.copy(currentSegmentIndex = done) }
                continue
            }

            pendingSegments.add(Triple(idx, seg, fp))
        }

        if (pendingSegments.isNotEmpty()) {
            val currentSettings = _uiState.value.settings
            val activeKeys = currentSettings.activeApiKeys()
            val poolStrategy = currentSettings.apiPoolStrategy
            val allowModelFallback = currentSettings.modelFallbackOnQuota
            val concurrency = currentSettings.effectiveConcurrency().coerceAtLeast(1)
            val semaphore = Semaphore(concurrency)
            val mapMutex = Mutex()
            var firstFailure: Pair<Int, String>? = null

            coroutineScope {
                pendingSegments.map { (idx, seg, fp) ->
                    async {
                        semaphore.withPermit {
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
                                    normalizeStandardTokens = currentSettings.pronunciationNormalizationEnabled,
                                    customDictionary = _uiState.value.dictionary
                                )
                                val samples = aiService.synthesizeSegmentPcm(
                                    spokenText = normalizedText,
                                    voiceName = seg.voice,
                                    tone = seg.tone,
                                    speed = seg.speed,
                                    activeAmbience = seg.activeAmbience,
                                    modelId = _uiState.value.selectedModelId,
                                    languageCode = effectiveLang,
                                    apiKeys = activeKeys,
                                    poolStrategy = poolStrategy,
                                    allowModelFallback = allowModelFallback
                                )
                                val take = repository.saveSegmentTakeToDisk(fp, samples, saveAsPrimaryCache = true)
                                settingsStore.incrementUsage(normalizedText.length)

                                mapMutex.withLock {
                                    segmentPcmMap[seg.id] = take.pcmFilePath
                                }
                                val done = completedCount.incrementAndGet()
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
                                        currentSegmentIndex = done,
                                        segmentStates = map,
                                        sessionUsage = st.sessionUsage.copy(
                                            apiCallsCount = st.sessionUsage.apiCallsCount + 1,
                                            generatedCharactersCount = st.sessionUsage.generatedCharactersCount + normalizedText.length
                                        )
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val msg = e.message ?: "Échec de génération du segment ${idx + 1}"
                                mapMutex.withLock {
                                    if (firstFailure == null) {
                                        firstFailure = idx to msg
                                    }
                                }
                                _uiState.update { st ->
                                    val map = st.segmentStates.toMutableMap()
                                    map[seg.id] = (map[seg.id] ?: SegmentRuntimeState(seg.id, fp)).copy(
                                        status = SegmentStatus.ERROR,
                                        errorMessage = msg
                                    )
                                    st.copy(segmentStates = map)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            firstFailure?.let { (failedIdx, msg) ->
                triggerHaptic(isError = true)
                val cleanMsg = msg.trimEnd('.', ' ')
                _uiState.update {
                    it.copy(
                        generationPhase = GenerationPhase.IDLE,
                        errorMessage = "Segment ${failedIdx + 1} sur ${segments.size} en erreur : $cleanMsg. Touchez « Reprendre » pour continuer sans perdre les segments déjà prêts."
                    )
                }
                return
            }
        }

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

            // Export automatique vers le dossier choisi si activé
            val cfg = currentState.settings
            if (cfg.autoExportEnabled && cfg.autoExportTreeUri.isNotBlank()) {
                val fmt = if (cfg.autoExportFormat.equals("WAV", ignoreCase = true)) {
                    AudioExportFormat.WAV
                } else {
                    AudioExportFormat.M4A
                }
                val buf = if (cfg.autoExportVoiceOnly) mixResult.voiceOnlyPcm else mixResult.mixedPcm
                val autoRes = repository.autoExportToFolderUri(
                    treeUriString = cfg.autoExportTreeUri,
                    record = saved,
                    samples = buf,
                    format = fmt,
                    voiceOnly = cfg.autoExportVoiceOnly
                )
                autoRes.onSuccess { fileName ->
                    _toastEvents.tryEmit(StudioToastEvent.Info("Export auto enregistré : $fileName"))
                }.onFailure { err ->
                    _toastEvents.tryEmit(
                        StudioToastEvent.Info("Export auto ignoré : ${err.localizedMessage ?: "dossier inaccessible"}")
                    )
                }
            }
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

    fun openHistoryGenerationInStudio(record: GenerationRecord) {
        viewModelScope.launch {
            val parentProject = _uiState.value.projects.firstOrNull { it.id == record.projectId }
            if (parentProject != null && parentProject.id != _uiState.value.activeProjectId) {
                loadProjectIntoEditor(parentProject, _uiState.value.settings)
                settingsStore.setActiveProjectId(parentProject.id)
            }
            updateExpertScript(record.annotatedScript, record.annotatedScript.length)
            _uiState.update {
                it.copy(
                    mode = StudioMode.EXPERT,
                    selectedVoice = record.selectedVoice,
                    selectedModelId = record.modelId
                )
            }
            loadGenerationIntoPlayer(record, autoPlay = false)
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

    fun toggleGenerationFavorite(record: GenerationRecord) {
        viewModelScope.launch {
            repository.setGenerationFavorite(record.id, !record.isFavorite)
        }
    }

    fun renameGeneration(record: GenerationRecord, newTitle: String) {
        viewModelScope.launch {
            repository.renameGeneration(record.id, newTitle)
            _toastEvents.tryEmit(StudioToastEvent.Info("Génération renommée."))
        }
    }

    fun updateGenerationTags(record: GenerationRecord, tags: List<String>) {
        viewModelScope.launch {
            repository.updateGenerationTags(record.id, tags)
            _toastEvents.tryEmit(StudioToastEvent.Info("Tags mis à jour."))
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
                val cfg = _uiState.value.settings
                val samples = aiService.synthesizeSegmentPcm(
                    spokenText = normalizedText,
                    voiceName = seg.voice,
                    tone = seg.tone,
                    speed = seg.speed,
                    activeAmbience = seg.activeAmbience,
                    modelId = _uiState.value.selectedModelId,
                    languageCode = effectiveLang,
                    apiKeys = cfg.activeApiKeys(),
                    poolStrategy = cfg.apiPoolStrategy,
                    allowModelFallback = cfg.modelFallbackOnQuota
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
                    languageCode = resolveEffectiveLanguageCode(cleaned),
                    apiKeys = st.settings.activeApiKeys(),
                    poolStrategy = st.settings.apiPoolStrategy,
                    allowModelFallback = st.settings.modelFallbackOnQuota
                )
                playerEngine.playSamplesOnce("passage", samples)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = aiService.translateAiError(
                            e,
                            usingPersonalKey = _uiState.value.settings.activeApiKeys().isNotEmpty()
                        )
                    )
                }
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
                    val cfg = _uiState.value.settings
                    val generated = aiService.synthesizeSegmentPcm(
                        spokenText = sampleLine,
                        voiceName = voiceName,
                        tone = "naturel et chaleureux",
                        speed = com.example.domain.model.SpeechSpeed.NORMAL,
                        activeAmbience = null,
                        modelId = _uiState.value.selectedModelId,
                        languageCode = if (lang == LanguageOption.AUTO) "fr-FR" else lang.code,
                        apiKeys = cfg.activeApiKeys(),
                        poolStrategy = cfg.apiPoolStrategy,
                        allowModelFallback = cfg.modelFallbackOnQuota
                    )
                    repository.saveSegmentTakeToDisk(fp, generated, saveAsPrimaryCache = true)
                    settingsStore.incrementUsage(sampleLine.length)
                    generated
                }
                playerEngine.playSamplesOnce("voice:$voiceName", samples)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        voicePreviewError = aiService.translateAiError(
                            e,
                            usingPersonalKey = _uiState.value.settings.activeApiKeys().isNotEmpty()
                        )
                    )
                }
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
            val cfg = _uiState.value.settings
            val activeKeys = cfg.activeApiKeys()

            suspend fun synthSlot(slot: AbTestSlot): AbTestSlot {
                return try {
                    val pcm = aiService.synthesizeSegmentPcm(
                        spokenText = phrase,
                        voiceName = slot.voiceName,
                        tone = slot.tone,
                        speed = com.example.domain.model.SpeechSpeed.NORMAL,
                        activeAmbience = null,
                        modelId = modelId,
                        languageCode = lang,
                        apiKeys = activeKeys,
                        poolStrategy = cfg.apiPoolStrategy,
                        allowModelFallback = cfg.modelFallbackOnQuota
                    )
                    settingsStore.incrementUsage(phrase.length)
                    slot.copy(
                        isLoading = false,
                        pcmSamples = pcm,
                        durationSec = pcm.size.toFloat() / PcmUtils.SAMPLE_RATE
                    )
                } catch (e: Exception) {
                    slot.copy(
                        isLoading = false,
                        error = aiService.translateAiError(
                            e,
                            usingPersonalKey = activeKeys.isNotEmpty()
                        )
                    )
                }
            }

            if (cfg.effectiveConcurrency() >= 2) {
                coroutineScope {
                    val defA = async {
                        val r = synthSlot(_uiState.value.abSlotA)
                        _uiState.update { it.copy(abSlotA = r) }
                    }
                    val defB = async {
                        val r = synthSlot(_uiState.value.abSlotB)
                        _uiState.update { it.copy(abSlotB = r) }
                    }
                    defA.await()
                    defB.await()
                }
            } else {
                val resA = synthSlot(_uiState.value.abSlotA)
                _uiState.update { it.copy(abSlotA = resA) }
                val resB = synthSlot(_uiState.value.abSlotB)
                _uiState.update { it.copy(abSlotB = resB) }
            }
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

    fun exportAudioToUri(
        uri: Uri,
        format: AudioExportFormat,
        voiceOnly: Boolean,
        record: GenerationRecord? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val samples = if (record != null) {
                    repository.readGenerationAudioBuffer(record, voiceOnly)
                } else {
                    getExportBuffer(voiceOnly)
                }
                if (samples.isEmpty()) {
                    _toastEvents.tryEmit(StudioToastEvent.Info("Aucun audio disponible pour cette génération."))
                    return@launch
                }
                AudioExporter.writeToSafUri(appContext, samples, format, uri)
                _toastEvents.tryEmit(StudioToastEvent.Info("Fichier ${format.extension.uppercase()} enregistré avec succès."))
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Échec de l'export : ${e.localizedMessage}") }
            }
        }
    }

    fun exportSrtToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val st = _uiState.value
                val segments = st.parseResult.segments
                val timings = st.audioMetrics?.segmentTimings.orEmpty()
                val totalSec = st.audioMetrics?.durationSec ?: st.parseResult.estimatedTotalDurationSec
                val srt = AudioExporter.buildSrtContent(segments, timings, totalSec)
                if (srt.isBlank()) {
                    _toastEvents.tryEmit(StudioToastEvent.Info("Aucun segment disponible pour générer les sous-titres .srt."))
                    return@launch
                }
                AudioExporter.writeTextToSafUri(appContext, srt, uri)
                _toastEvents.tryEmit(StudioToastEvent.Info("Sous-titres .srt enregistrés."))
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Échec de l'export .srt : ${e.localizedMessage}") }
            }
        }
    }

    fun exportBatchZipToUri(
        records: List<GenerationRecord>,
        format: AudioExportFormat,
        voiceOnly: Boolean,
        destinationUri: Uri
    ) {
        if (records.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingZip = true) }
            try {
                val count = repository.exportBatchZipToUri(records, format, voiceOnly, destinationUri)
                _toastEvents.tryEmit(
                    StudioToastEvent.Info("Archive ZIP créée ($count audio(s) + scripts).")
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Échec de l'export ZIP : ${e.localizedMessage}") }
            } finally {
                _uiState.update { it.copy(isExportingZip = false) }
            }
        }
    }

    fun setAutoExportFolder(treeUri: Uri, label: String) {
        viewModelScope.launch {
            settingsStore.updateSettings {
                it.copy(
                    autoExportEnabled = true,
                    autoExportTreeUri = treeUri.toString(),
                    autoExportFolderLabel = label.ifBlank { "Dossier sélectionné" }
                )
            }
            _toastEvents.tryEmit(StudioToastEvent.Info("Dossier d'export automatique configuré."))
        }
    }

    fun clearAutoExportFolder() {
        viewModelScope.launch {
            settingsStore.updateSettings {
                it.copy(
                    autoExportEnabled = false,
                    autoExportTreeUri = "",
                    autoExportFolderLabel = ""
                )
            }
        }
    }

    fun shareAudio(
        format: AudioExportFormat,
        voiceOnly: Boolean,
        record: GenerationRecord? = null,
        onIntentReady: (Intent) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    val samples = if (record != null) {
                        repository.readGenerationAudioBuffer(record, voiceOnly)
                    } else {
                        getExportBuffer(voiceOnly)
                    }
                    if (samples.isEmpty()) return@withContext null
                    val rawBase = (record?.title ?: _uiState.value.projectTitle).ifBlank { "voxa_studio_audio" }
                    val baseName = if (voiceOnly) "${rawBase}_voix_seule" else rawBase
                    AudioExporter.createShareIntent(
                        context = appContext,
                        samples = samples,
                        format = format,
                        baseName = baseName
                    )
                }
                if (intent == null) {
                    _toastEvents.tryEmit(StudioToastEvent.Info("Aucun audio disponible pour le partage."))
                } else {
                    onIntentReady(intent)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Impossible de préparer le partage : ${e.localizedMessage}") }
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
                allGenerations = it.allGenerations,
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

    fun savePersonalGeminiApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        viewModelScope.launch {
            settingsStore.updateSettings { current ->
                val updatedPool = if (cleaned.isEmpty()) {
                    emptyList()
                } else {
                    val existingIdx = current.apiKeyPool.indexOfFirst { it.apiKey.trim() == cleaned }
                    if (existingIdx >= 0) {
                        current.apiKeyPool.mapIndexed { idx, entry ->
                            if (idx == existingIdx) entry.copy(enabled = true) else entry
                        }
                    } else {
                        listOf(
                            ApiKeyEntry(
                                id = UUID.randomUUID().toString(),
                                label = "Clé #${current.apiKeyPool.size + 1}",
                                apiKey = cleaned,
                                enabled = true
                            )
                        ) + current.apiKeyPool
                    }
                }
                current.copy(
                    personalGeminiApiKey = cleaned,
                    apiKeyPool = updatedPool
                )
            }
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    voicePreviewError = null,
                    apiKeyTestState = if (cleaned.isEmpty()) {
                        ApiKeyTestState(
                            isTesting = false,
                            isSuccess = null,
                            message = "Clé effacée. Mode Firebase AI Logic actif."
                        )
                    } else {
                        ApiKeyTestState(
                            isTesting = false,
                            isSuccess = true,
                            message = "Clé API Gemini enregistrée sur cet appareil."
                        )
                    }
                )
            }
            _toastEvents.tryEmit(
                StudioToastEvent.Info(
                    if (cleaned.isEmpty()) "Clé personnelle supprimée."
                    else "Clé API Gemini personnelle activée."
                )
            )
        }
    }

    fun addApiKeyToPool(label: String, rawKey: String) {
        val cleanKey = rawKey.trim().trim('"', '\'')
        if (cleanKey.isEmpty()) {
            _uiState.update {
                it.copy(
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = false,
                        isSuccess = false,
                        message = "Collez une clé API Gemini (AIza…) avant de l'ajouter."
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            var wasDuplicate = false
            settingsStore.updateSettings { current ->
                val existing = current.apiKeyPool.firstOrNull { it.apiKey.trim() == cleanKey }
                val updatedPool = if (existing != null) {
                    wasDuplicate = true
                    current.apiKeyPool.map { entry ->
                        if (entry.id == existing.id) {
                            entry.copy(
                                label = label.trim().ifBlank { entry.label },
                                enabled = true
                            )
                        } else entry
                    }
                } else {
                    val autoLabel = label.trim().ifBlank { "Clé #${current.apiKeyPool.size + 1}" }
                    current.apiKeyPool + ApiKeyEntry(
                        id = UUID.randomUUID().toString(),
                        label = autoLabel,
                        apiKey = cleanKey,
                        enabled = true
                    )
                }
                val firstActive = updatedPool.firstOrNull { it.enabled }?.apiKey ?: updatedPool.firstOrNull()?.apiKey.orEmpty()
                current.copy(
                    personalGeminiApiKey = firstActive,
                    apiKeyPool = updatedPool
                )
            }
            ApiPoolCoordinator.markSuccess(cleanKey)
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    voicePreviewError = null,
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = false,
                        isSuccess = true,
                        message = if (wasDuplicate) "Clé déjà présente : réactivée dans le pool."
                        else "Clé ajoutée au pool multi-API."
                    )
                )
            }
        }
    }

    fun importApiKeysBatch(rawText: String) {
        val candidates = rawText
            .split(Regex("[\\s,;]+"))
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.isNotEmpty() }
            .distinct()
        if (candidates.isEmpty()) {
            _toastEvents.tryEmit(StudioToastEvent.Info("Aucune clé détectée dans le texte saisi."))
            return
        }
        viewModelScope.launch {
            var addedCount = 0
            settingsStore.updateSettings { current ->
                val existingSet = current.apiKeyPool.map { it.apiKey.trim() }.toSet()
                val newKeys = candidates.filter { it !in existingSet }
                addedCount = newKeys.size
                if (newKeys.isEmpty()) return@updateSettings current

                var indexOffset = current.apiKeyPool.size
                val additions = newKeys.map { k ->
                    indexOffset += 1
                    ApiKeyEntry(
                        id = UUID.randomUUID().toString(),
                        label = "Clé #$indexOffset",
                        apiKey = k,
                        enabled = true
                    )
                }
                val updatedPool = current.apiKeyPool + additions
                val firstActive = updatedPool.firstOrNull { it.enabled }?.apiKey.orEmpty()
                current.copy(
                    personalGeminiApiKey = firstActive,
                    apiKeyPool = updatedPool
                )
            }
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    voicePreviewError = null,
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = false,
                        isSuccess = addedCount > 0,
                        message = if (addedCount > 0) "$addedCount clé(s) importée(s) dans le pool."
                        else "Toutes ces clés étaient déjà dans votre pool."
                    )
                )
            }
        }
    }

    fun toggleApiKeyEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.updateSettings { current ->
                val updatedPool = current.apiKeyPool.map { entry ->
                    if (entry.id == id) entry.copy(enabled = enabled) else entry
                }
                val firstActive = updatedPool.firstOrNull { it.enabled }?.apiKey.orEmpty()
                current.copy(
                    personalGeminiApiKey = firstActive,
                    apiKeyPool = updatedPool
                )
            }
        }
    }

    fun removeApiKeyFromPool(id: String) {
        viewModelScope.launch {
            settingsStore.updateSettings { current ->
                val updatedPool = current.apiKeyPool.filterNot { it.id == id }
                val firstActive = updatedPool.firstOrNull { it.enabled }?.apiKey
                    ?: updatedPool.firstOrNull()?.apiKey.orEmpty()
                current.copy(
                    personalGeminiApiKey = firstActive,
                    apiKeyPool = updatedPool
                )
            }
            _toastEvents.tryEmit(StudioToastEvent.Info("Clé retirée du pool."))
        }
    }

    fun testSinglePoolKey(id: String) {
        val entry = _uiState.value.settings.apiKeyPool.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = true,
                        testingEntryId = id,
                        isSuccess = null,
                        message = "Vérification de ${entry.label}…"
                    )
                )
            }
            val res = aiService.verifyPersonalApiKey(entry.apiKey)
            val ok = res.isSuccess
            val statusMsg = res.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Clé invalide" }
            )
            settingsStore.updateSettings { current ->
                current.copy(
                    apiKeyPool = current.apiKeyPool.map { item ->
                        if (item.id == id) {
                            item.copy(lastStatusOk = ok, lastStatusMessage = statusMsg)
                        } else item
                    }
                )
            }
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = false,
                        testingEntryId = null,
                        isSuccess = ok,
                        message = "${entry.label} : $statusMsg"
                    )
                )
            }
        }
    }

    fun testAllPoolKeys() {
        val pool = _uiState.value.settings.apiKeyPool
        if (pool.isEmpty()) {
            _uiState.update {
                it.copy(
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = false,
                        isSuccess = false,
                        message = "Ajoutez au moins une clé API dans le pool avant de lancer le test."
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = true,
                        isSuccess = null,
                        message = "Test parallèle de ${pool.size} clé(s) en cours…"
                    )
                )
            }
            val testedPool = aiService.verifyApiKeyPool(pool)
            val validCount = testedPool.count { it.lastStatusOk == true }
            settingsStore.updateSettings { current ->
                val byId = testedPool.associateBy { it.id }
                current.copy(
                    apiKeyPool = current.apiKeyPool.map { byId[it.id] ?: it }
                )
            }
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = false,
                        isSuccess = validCount > 0,
                        message = "$validCount/${testedPool.size} clé(s) valide(s) dans le pool."
                    )
                )
            }
        }
    }

    fun setApiPoolStrategy(strategy: ApiPoolStrategy) {
        updateSettings { it.copy(apiPoolStrategy = strategy) }
    }

    fun setModelFallbackOnQuota(enabled: Boolean) {
        updateSettings { it.copy(modelFallbackOnQuota = enabled) }
    }

    fun testPersonalGeminiApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        if (cleaned.isEmpty()) {
            _uiState.update {
                it.copy(
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = false,
                        isSuccess = false,
                        message = "Collez votre clé API Gemini avant de lancer le test."
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    apiKeyTestState = ApiKeyTestState(
                        isTesting = true,
                        isSuccess = null,
                        message = "Vérification auprès de Google AI Studio…"
                    )
                )
            }
            val result = aiService.verifyPersonalApiKey(cleaned)
            result.fold(
                onSuccess = { okMsg ->
                    settingsStore.updateSettings { current ->
                        val exists = current.apiKeyPool.any { it.apiKey.trim() == cleaned }
                        val updatedPool = if (exists) {
                            current.apiKeyPool.map { item ->
                                if (item.apiKey.trim() == cleaned) {
                                    item.copy(enabled = true, lastStatusOk = true, lastStatusMessage = okMsg)
                                } else item
                            }
                        } else {
                            current.apiKeyPool + ApiKeyEntry(
                                id = UUID.randomUUID().toString(),
                                label = "Clé #${current.apiKeyPool.size + 1}",
                                apiKey = cleaned,
                                enabled = true,
                                lastStatusOk = true,
                                lastStatusMessage = okMsg
                            )
                        }
                        current.copy(
                            personalGeminiApiKey = current.personalGeminiApiKey.ifBlank { cleaned },
                            apiKeyPool = updatedPool
                        )
                    }
                    _uiState.update {
                        it.copy(
                            errorMessage = null,
                            voicePreviewError = null,
                            apiKeyTestState = ApiKeyTestState(
                                isTesting = false,
                                isSuccess = true,
                                message = "$okMsg Clé ajoutée et activée dans le pool."
                            )
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            apiKeyTestState = ApiKeyTestState(
                                isTesting = false,
                                isSuccess = false,
                                message = err.message ?: "Impossible de valider cette clé."
                            )
                        )
                    }
                }
            )
        }
    }

    fun clearApiKeyTestState() {
        _uiState.update { it.copy(apiKeyTestState = ApiKeyTestState()) }
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

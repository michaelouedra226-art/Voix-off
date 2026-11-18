package com.example.viewmodel

import com.example.domain.model.AudioMetrics
import com.example.domain.model.BriefPlan
import com.example.domain.model.ContextualToneSuggestion
import com.example.domain.model.CustomAmbience
import com.example.domain.model.GenerationRecord
import com.example.domain.model.LanguageOption
import com.example.domain.model.MixConfig
import com.example.domain.model.ParseResult
import com.example.domain.model.ProjectItem
import com.example.domain.model.PronunciationEntry
import com.example.domain.model.ScriptVersion
import com.example.domain.model.SegmentRuntimeState
import com.example.domain.model.SessionUsage
import com.example.domain.model.StudioMode
import com.example.domain.model.StudioSettings
import com.example.domain.model.TargetDurationOption
import com.example.domain.model.VoiceGenderPreference
import com.example.domain.script.LanguageDetectionResult

enum class GenerationPhase(val labelFr: String) {
    IDLE("Prêt"),
    ANALYZING("Analyse de ta demande"),
    SCRIPTING("Écriture de la mise en scène"),
    GENERATING_VOICES("Génération des voix"),
    MIXING("Mixage et ambiance"),
    DONE("Terminé")
}

data class AbTestSlot(
    val voiceName: String = "Kore",
    val tone: String = "naturel",
    val isLoading: Boolean = false,
    val pcmSamples: FloatArray? = null,
    val durationSec: Float = 0f,
    val error: String? = null
)

data class UndoDeletedProject(
    val project: ProjectItem
)

data class ApiKeyTestState(
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String? = null
)

data class StudioUiState(
    val activeProjectId: String = "",
    val projectTitle: String = "",
    val mode: StudioMode = StudioMode.BRIEF,

    // Mode Brief
    val briefDescription: String = "",
    val briefRawText: String = "",
    val targetDuration: TargetDurationOption = TargetDurationOption.FREE,
    val selectedLanguage: LanguageOption = LanguageOption.AUTO,
    val genderPreference: VoiceGenderPreference = VoiceGenderPreference.AUTO,
    val briefPlan: BriefPlan? = null,
    val isPlanningBrief: Boolean = false,
    val refinePrompt: String = "",
    val isRefining: Boolean = false,
    val lastRefinementNote: String? = null,

    // Mode Expert
    val expertScript: String = "",
    val cursorPosition: Int = 0,
    val selectedVoice: String = "Kore",
    val selectedModelId: String = "gemini-3.1-flash-tts-preview",
    val parseResult: ParseResult = ParseResult(
        events = emptyList(),
        segments = emptyList(),
        tagSpans = emptyList(),
        diagnostics = emptyList(),
        characterCount = 0,
        spokenCharacterCount = 0,
        wordCount = 0,
        estimatedTotalDurationSec = 0f
    ),
    val languageDetection: LanguageDetectionResult? = null,
    val contextualSuggestion: ContextualToneSuggestion? = null,
    val dismissedSuggestionIndex: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,

    // Génération & Chronologie
    val generationPhase: GenerationPhase = GenerationPhase.IDLE,
    val currentSegmentIndex: Int = 0,
    val totalSegmentsCount: Int = 0,
    val segmentStates: Map<String, SegmentRuntimeState> = emptyMap(),
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val infoBannerMessage: String? = null,
    val saveStatusLabel: String = "",

    // Mixage & Lecteur final
    val mixConfig: MixConfig = MixConfig(),
    val audioMetrics: AudioMetrics? = null,
    val hasFinalAudio: Boolean = false,
    val isVoiceOnlyPreview: Boolean = false,

    // Ressources & Outils
    val generations: List<GenerationRecord> = emptyList(),
    val activeGenerationId: String? = null,
    val projects: List<ProjectItem> = emptyList(),
    val scriptVersions: List<ScriptVersion> = emptyList(),
    val dictionary: List<PronunciationEntry> = emptyList(),
    val customAmbiences: List<CustomAmbience> = emptyList(),
    val recentTones: List<String> = emptyList(),
    val settings: StudioSettings = StudioSettings(),
    val sessionUsage: SessionUsage = SessionUsage(),
    val cumulativeUsage: SessionUsage = SessionUsage(),

    // Échantillons voix & Test A/B
    val previewingVoiceName: String? = null,
    val voicePreviewError: String? = null,
    val abTestPhrase: String = "",
    val abSlotA: AbTestSlot = AbTestSlot(voiceName = "Kore", tone = "chaleureux"),
    val abSlotB: AbTestSlot = AbTestSlot(voiceName = "Charon", tone = "sérieux"),
    val apiKeyTestState: ApiKeyTestState = ApiKeyTestState()
)

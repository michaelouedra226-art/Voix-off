package com.example.domain.model

import com.example.ui.theme.ThemeMode

enum class StudioMode {
    BRIEF,
    EXPERT
}

enum class SpeechSpeed(val labelFr: String, val directiveFr: String, val wpmFactor: Float) {
    LENT("lent", "lent et posé", 0.78f),
    NORMAL("normal", "naturel et équilibré", 1.0f),
    RAPIDE("rapide", "dynamique et soutenu", 1.24f);

    companion object {
        fun fromToken(raw: String): SpeechSpeed? {
            return when (raw.trim().lowercase()) {
                "lent", "slow", "calme" -> LENT
                "normal", "moyen", "standard" -> NORMAL
                "rapide", "vite", "fast", "dynamique" -> RAPIDE
                else -> null
            }
        }
    }
}

enum class VoiceGenderPreference(val labelFr: String) {
    AUTO("Indifférent"),
    FEMININE("Voix féminine"),
    MASCULINE("Voix masculine")
}

data class GeminiVoice(
    val name: String,
    val genderFr: String,
    val isFeminine: Boolean,
    val timbreFr: String,
    val descriptionFr: String
)

object VoiceCatalog {
    // Les 14 voix Gemini officielles disponibles dans Voxa Studio
    val voices: List<GeminiVoice> = listOf(
        GeminiVoice("Kore", "Féminine", true, "Ferme et claire", "Voix féminine posée, articulée et fiable, idéale pour la narration et les annonces."),
        GeminiVoice("Puck", "Masculine", false, "Enjouée et vive", "Voix masculine dynamique et expressive, parfaite pour les publicités et récits rythmés."),
        GeminiVoice("Charon", "Masculine", false, "Grave et informative", "Voix masculine profonde, calme et autoritaire pour documentaires et formats sérieux."),
        GeminiVoice("Zephyr", "Féminine", true, "Lumineuse et aérienne", "Voix féminine fraîche, souriante et moderne, adaptée aux contenus lifestyle et pédagogiques."),
        GeminiVoice("Fenrir", "Masculine", false, "Énergique et intense", "Voix masculine passionnée avec beaucoup de relief pour bandes-annonces et récits épiques."),
        GeminiVoice("Leda", "Féminine", true, "Jeune et spontanée", "Voix féminine vive et naturelle, proche de la conversation authentique."),
        GeminiVoice("Aoede", "Féminine", true, "Douce et mélodieuse", "Voix féminine fluide et apaisante, idéale pour contes, méditations et poésie."),
        GeminiVoice("Sulafat", "Féminine", true, "Chaleureuse et enveloppante", "Voix féminine accueillante et rassurante, parfaite pour marques bienveillantes et guides."),
        GeminiVoice("Algieba", "Masculine", false, "Veloutée et fluide", "Voix masculine chaleureuse au grain soyeux, adaptée aux histoires et podcasts."),
        GeminiVoice("Gacrux", "Féminine", true, "Mature et assurée", "Voix féminine posée, crédible et structurée pour le corporate et l'e-learning."),
        GeminiVoice("Achird", "Masculine", false, "Amicale et accessible", "Voix masculine naturelle et empathique pour tutoriels et messages clients."),
        GeminiVoice("Enceladus", "Masculine", false, "Soufflée et intime", "Voix masculine feutrée et proche du micro pour confidences, ASMR et suspense."),
        GeminiVoice("Algenib", "Masculine", false, "Texturée et grave", "Voix masculine rauque et charismatique, marquante pour le cinéma et les slogans."),
        GeminiVoice("Umbriel", "Masculine", false, "Détendue et posée", "Voix masculine décontractée et souple pour récits du quotidien et podcasts.")
    )

    fun findByName(name: String): GeminiVoice? =
        voices.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    val voiceNames: List<String> = voices.map { it.name }
}

object PresetTones {
    val tones: List<String> = listOf(
        "naturel",
        "chaleureux",
        "pub dynamique",
        "cinéma",
        "calme",
        "corporate",
        "conteur",
        "joyeux",
        "sérieux",
        "chuchoté"
    )
}

data class AmbiencePreset(
    val id: String,
    val name: String,
    val categoryFr: String,
    val sceneHintFr: String,
    val defaultVolume: Float = 0.20f
)

object AmbienceCatalog {
    // 14 ambiances sonores synthétisées en boucle sans coupure via DSP
    val presets: List<AmbiencePreset> = listOf(
        AmbiencePreset("pluie_douce", "pluie douce", "Nature", "atmosphère intime sous une pluie légère", 0.25f),
        AmbiencePreset("orage_lointain", "orage lointain", "Nature", "climat tendu avec grondement d'orage au loin", 0.25f),
        AmbiencePreset("vent", "vent", "Nature", "espace ouvert balayé par le vent", 0.22f),
        AmbiencePreset("vagues", "vagues", "Nature", "bord de mer apaisant au rythme des vagues", 0.25f),
        AmbiencePreset("foret_oiseaux", "forêt et oiseaux", "Nature", "clairière matinale lumineuse en forêt", 0.22f),
        AmbiencePreset("nuit_grillons", "nuit et grillons", "Nature", "nuit d'été paisible et confidentielle", 0.20f),
        AmbiencePreset("cafe_anime", "café animé", "Lieux", "bistrot convivial avec murmures en arrière-plan", 0.20f),
        AmbiencePreset("marche_anime", "marché animé", "Lieux", "lieu vivant et animé en plein air", 0.20f),
        AmbiencePreset("feu_camp", "feu de camp", "Nature", "veillée chaleureuse près d'un feu crépitant", 0.22f),
        AmbiencePreset("studio_calme", "studio calme", "Studio", "proximité micro nette et feutrée", 0.15f),
        AmbiencePreset("drone_cinematique", "drone cinématique", "Musique", "scène ample et cinématographique", 0.22f),
        AmbiencePreset("nappe_douce", "nappe douce", "Musique", "fond musical doux, émotionnel et enveloppant", 0.20f),
        AmbiencePreset("suspense", "suspense", "Musique", "tension dramatique et mystère", 0.22f),
        AmbiencePreset("joyeux_leger", "joyeux léger", "Musique", "ambiance lumineuse, positive et légère", 0.20f)
    )

    fun findByName(name: String): AmbiencePreset? {
        val cleaned = name.trim().lowercase()
        return presets.firstOrNull {
            it.name.lowercase() == cleaned || it.id.lowercase() == cleaned
        }
    }
}

enum class TtsModelOption(
    val modelId: String,
    val labelFr: String,
    val descriptionFr: String
) {
    GEMINI_3_1_FLASH_TTS(
        modelId = "gemini-3.1-flash-tts-preview",
        labelFr = "Gemini 3.1 Flash TTS (Recommandé)",
        descriptionFr = "Dernière génération rapide et expressive via Firebase AI Logic"
    ),
    GEMINI_2_5_FLASH_TTS(
        modelId = "gemini-2.5-flash-preview-tts",
        labelFr = "Gemini 2.5 Flash TTS (Rapide)",
        descriptionFr = "Synthèse rapide pour itérations et brouillons"
    ),
    GEMINI_2_5_PRO_TTS(
        modelId = "gemini-2.5-pro-preview-tts",
        labelFr = "Gemini 2.5 Pro TTS (Qualité max)",
        descriptionFr = "Interprétation nuancée et diction studio haute fidélité"
    );

    companion object {
        fun fromId(id: String): TtsModelOption =
            entries.firstOrNull { it.modelId == id } ?: GEMINI_3_1_FLASH_TTS
    }
}

enum class LanguageOption(val code: String, val labelFr: String) {
    AUTO("auto", "Auto"),
    FR("fr-FR", "Français"),
    EN_US("en-US", "Anglais (US)"),
    EN_GB("en-GB", "Anglais (UK)"),
    ES("es-ES", "Espagnol"),
    DE("de-DE", "Allemand"),
    IT("it-IT", "Italien"),
    PT("pt-BR", "Portugais"),
    AR("ar-XA", "Arabe");

    companion object {
        fun fromCode(code: String): LanguageOption =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
    }
}

enum class TargetDurationOption(val seconds: Int?, val labelFr: String) {
    FREE(null, "Libre"),
    SEC_15(15, "15 s"),
    SEC_30(30, "30 s"),
    SEC_60(60, "1 min"),
    SEC_120(120, "2 min")
}

// --- Éléments issus du parseur ---

enum class TagType {
    TONE,
    VOICE,
    SPEED,
    PAUSE,
    AMBIENCE,
    ERROR
}

data class ParsedTagSpan(
    val startIndex: Int,
    val endIndex: Int,
    val type: TagType,
    val rawText: String,
    val isMalformed: Boolean = false
)

data class DiagnosticIssue(
    val startIndex: Int,
    val endIndex: Int,
    val rawTag: String,
    val messageFr: String,
    val isError: Boolean = false
)

sealed interface TimelineEvent {
    val id: String

    data class VoiceSegment(
        override val id: String,
        val index: Int,
        val text: String,
        val voice: String,
        val tone: String,
        val speed: SpeechSpeed,
        val activeAmbience: String?,
        val sourceStartIndex: Int,
        val sourceEndIndex: Int,
        val estimatedDurationSec: Float
    ) : TimelineEvent

    data class PauseEvent(
        override val id: String,
        val durationSec: Float,
        val sourceStartIndex: Int
    ) : TimelineEvent

    data class AmbienceEvent(
        override val id: String,
        val ambienceName: String?, // null signifie [ambiance: stop]
        val volume: Float,
        val sourceStartIndex: Int
    ) : TimelineEvent
}

data class ParseResult(
    val events: List<TimelineEvent>,
    val segments: List<TimelineEvent.VoiceSegment>,
    val tagSpans: List<ParsedTagSpan>,
    val diagnostics: List<DiagnosticIssue>,
    val characterCount: Int,
    val spokenCharacterCount: Int,
    val wordCount: Int,
    val estimatedTotalDurationSec: Float
)

// --- Prises, statuts de génération et défauts détectés ---

enum class SegmentStatus {
    PENDING,
    GENERATING,
    READY,
    ERROR
}

enum class DefectType(val labelFr: String) {
    TOO_SHORT("Segment très court (< 0,25 s)"),
    LONG_SILENCE("Silence interne prolongé détecté"),
    EMPTY_AUDIO("Audio quasi silencieux ou vide")
}

data class SegmentTake(
    val takeId: String,
    val fingerprint: String,
    val pcmFilePath: String,
    val durationSec: Float,
    val peakDb: Float,
    val createdAt: Long,
    val defect: DefectType? = null
)

data class SegmentRuntimeState(
    val segmentId: String,
    val fingerprint: String,
    val status: SegmentStatus = SegmentStatus.PENDING,
    val fromCache: Boolean = false,
    val errorMessage: String? = null,
    val takes: List<SegmentTake> = emptyList(),
    val activeTakeIndex: Int = 0
) {
    val activeTake: SegmentTake?
        get() = takes.getOrNull(activeTakeIndex)
}

// --- Paramètres de mixage et métriques audio ---

data class MixConfig(
    val voiceVolume: Float = 1.0f,        // 0.2 à 1.5
    val ambienceVolume: Float = 1.0f,     // 0.0 à 1.5 (multiplieur global)
    val duckingAmountDb: Float = 10.0f,   // 0 à 18 dB
    val fadeDurationSec: Float = 1.5f,    // 0.3 à 3.0 s
    val crossfadeMs: Int = 80             // 60 à 100 ms
)

data class AudioMetrics(
    val durationSec: Float,
    val peakDb: Float,
    val integratedLufs: Float,
    val isClippingRisk: Boolean,
    val isTooQuiet: Boolean,
    val waveformPeaks: List<Float>,
    val segmentTimings: List<SegmentTimingRange>
)

data class SegmentTimingRange(
    val segmentId: String,
    val startSec: Float,
    val endSec: Float
)

// --- Mode Brief & Plan de réalisation ---

data class ToneChangePlan(
    val cue: String,
    val excerpt: String,
    val reason: String
)

data class AmbiencePlanItem(
    val name: String,
    val volumePercent: Int
)

data class BriefPlan(
    val annotatedScript: String,
    val selectedVoice: String,
    val recommendedModel: String,
    val ambiences: List<AmbiencePlanItem>,
    val estimatedDurationSeconds: Int,
    val toneChanges: List<ToneChangePlan>,
    val directorExplanation: String
)

// --- Projets, versions, dictionnaire, réglages, usage ---

data class ProjectItem(
    val id: String,
    val title: String,
    val mode: StudioMode,
    val briefDescription: String,
    val briefRawText: String,
    val expertScript: String,
    val defaultVoice: String,
    val ttsModelId: String,
    val languageCode: String,
    val mixConfig: MixConfig,
    val updatedAt: Long
)

data class ScriptVersion(
    val id: String,
    val projectId: String,
    val script: String,
    val label: String,
    val createdAt: Long
)

data class GenerationRecord(
    val id: String,
    val projectId: String,
    val title: String,
    val sourceMode: StudioMode,
    val annotatedScript: String,
    val directorExplanation: String,
    val selectedVoice: String,
    val modelId: String,
    val mixedPcmPath: String,
    val voiceOnlyPcmPath: String,
    val durationSec: Float,
    val peakDb: Float,
    val integratedLufs: Float,
    val waveformPeaks: List<Float>,
    val ambiencesSummary: String,
    val segmentCount: Int,
    val createdAt: Long
)

data class PronunciationEntry(
    val id: String,
    val originalWord: String,
    val phoneticReplacement: String
)

data class CustomAmbience(
    val id: String,
    val name: String,
    val pcmFilePath: String,
    val durationSec: Float,
    val createdAt: Long
)

data class StudioSettings(
    val defaultModelId: String = TtsModelOption.GEMINI_3_1_FLASH_TTS.modelId,
    val defaultVoice: String = "Kore",
    val defaultTone: String = "naturel",
    val defaultLanguageCode: String = LanguageOption.AUTO.code,
    val hapticsEnabled: Boolean = true,
    val completionSoundEnabled: Boolean = true,
    val pronunciationNormalizationEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK
)

data class SessionUsage(
    val apiCallsCount: Int = 0,
    val generatedCharactersCount: Int = 0,
    val cachedHitsCount: Int = 0
)

data class ContextualToneSuggestion(
    val insertIndex: Int,
    val excerpt: String,
    val suggestedTone: String,
    val reasonFr: String
)

data class AutocompleteContext(
    val bracketStartIndex: Int,
    val query: String,
    val isInsideCommandValue: Boolean,
    val commandKey: String?,
    val suggestions: List<AutocompleteSuggestion>
)

data class AutocompleteSuggestion(
    val displayLabel: String,
    val insertText: String,
    val category: TagType,
    val subtitle: String? = null
)

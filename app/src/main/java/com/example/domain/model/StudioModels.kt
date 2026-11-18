package com.example.domain.model

import com.example.ui.theme.ThemeMode

enum class StudioMode {
    BRIEF,
    DIALOGUE,
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
        descriptionFr = "Dernière génération rapide et expressive pour voix off studio"
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

// --- Paramètres de mixage, post-traitement DSP et métriques audio ---

enum class ReverbPreset(val labelFr: String, val wetMix: Float, val roomScale: Float) {
    NONE("Aucune", 0f, 0f),
    STUDIO("Studio", 0.14f, 0.45f),
    HALL("Salle", 0.28f, 0.78f),
    CINEMA("Cinéma", 0.38f, 1.0f);

    companion object {
        fun fromName(name: String?): ReverbPreset =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: NONE
    }
}

enum class LoudnessTarget(val targetLufs: Float, val labelFr: String, val hintFr: String) {
    LUFS_14(-14.0f, "-14 LUFS", "YouTube / Spotify / Reels"),
    LUFS_16(-16.0f, "-16 LUFS", "Podcast / Radio (standard)"),
    LUFS_19(-19.0f, "-19 LUFS", "Narration douce / Livre audio");

    companion object {
        fun closestTo(lufs: Float): LoudnessTarget =
            entries.minByOrNull { kotlin.math.abs(it.targetLufs - lufs) } ?: LUFS_16
    }
}

data class MixConfig(
    val voiceVolume: Float = 1.0f,        // 0.2 à 1.5
    val ambienceVolume: Float = 1.0f,     // 0.0 à 1.5 (multiplieur global)
    val duckingAmountDb: Float = 10.0f,   // 0 à 18 dB
    val fadeDurationSec: Float = 1.5f,    // 0.3 à 3.0 s
    val crossfadeMs: Int = 80,            // 60 à 100 ms
    val trimStartSec: Float = 0f,         // 0.0 à 5.0 s
    val trimEndSec: Float = 0f,           // 0.0 à 5.0 s
    val targetLufs: Float = -16.0f,       // -14, -16, -19 LUFS
    val reverbPreset: ReverbPreset = ReverbPreset.NONE,
    val playbackSpeed: Float = 1.0f       // 0.85x à 1.20x
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
    val createdAt: Long,
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList()
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

enum class ApiPoolStrategy(val labelFr: String, val descriptionFr: String) {
    PARALLEL_BOOST(
        "Turbo Parallèle",
        "Synthétise plusieurs segments simultanément (1 par clé active) avec rotation et bascule automatique."
    ),
    ROUND_ROBIN(
        "Rotation équilibrée",
        "Répartit chaque appel tour à tour entre vos clés actives pour cumuler vos tokens."
    ),
    FAILOVER(
        "Priorité + Secours",
        "Utilise la première clé et bascule sur la suivante dès qu'un quota (429) est atteint."
    );

    companion object {
        fun fromName(raw: String?): ApiPoolStrategy =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PARALLEL_BOOST
    }
}

data class ApiKeyEntry(
    val id: String,
    val label: String,
    val apiKey: String,
    val enabled: Boolean = true,
    val lastStatusOk: Boolean? = null,
    val lastStatusMessage: String? = null
) {
    fun maskedPreview(): String {
        val k = apiKey.trim()
        return when {
            k.length <= 8 -> "••••"
            else -> "${k.take(6)}••••${k.takeLast(4)}"
        }
    }
}

data class StudioSettings(
    val defaultModelId: String = TtsModelOption.GEMINI_3_1_FLASH_TTS.modelId,
    val defaultVoice: String = "Kore",
    val defaultTone: String = "naturel",
    val defaultLanguageCode: String = LanguageOption.AUTO.code,
    val hapticsEnabled: Boolean = true,
    val completionSoundEnabled: Boolean = true,
    val pronunciationNormalizationEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val personalGeminiApiKey: String = "",
    val apiKeyPool: List<ApiKeyEntry> = emptyList(),
    val apiPoolStrategy: ApiPoolStrategy = ApiPoolStrategy.PARALLEL_BOOST,
    val modelFallbackOnQuota: Boolean = true,
    val autoExportEnabled: Boolean = false,
    val autoExportTreeUri: String = "",
    val autoExportFolderLabel: String = "",
    val autoExportFormat: String = "M4A",
    val autoExportVoiceOnly: Boolean = false
) {
    fun activeApiKeys(): List<String> {
        if (apiKeyPool.isNotEmpty()) {
            val enabledKeys = apiKeyPool
                .filter { it.enabled && it.apiKey.isNotBlank() }
                .map { it.apiKey.trim() }
                .distinct()
            val primary = personalGeminiApiKey.trim()
            val primaryInPool = apiKeyPool.any { it.apiKey.trim() == primary }
            return if (primary.isNotEmpty() && !primaryInPool) {
                (listOf(primary) + enabledKeys).distinct()
            } else {
                enabledKeys
            }
        }
        val primary = personalGeminiApiKey.trim()
        return if (primary.isNotEmpty()) listOf(primary) else emptyList()
    }

    fun effectiveConcurrency(): Int {
        val activeCount = activeApiKeys().size
        return when {
            activeCount <= 1 -> 1
            apiPoolStrategy == ApiPoolStrategy.PARALLEL_BOOST -> activeCount.coerceAtMost(4)
            else -> 1
        }
    }
}

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

// --- Mode Dialogue multi-voix assisté ---

data class DialogueLine(
    val id: String,
    val voiceName: String = "Kore",
    val tone: String = "naturel",
    val speed: SpeechSpeed = SpeechSpeed.NORMAL,
    val pauseBeforeSec: Float = 0.4f,
    val text: String = ""
)

object DialogueCompiler {
    fun compileScript(
        lines: List<DialogueLine>,
        ambienceName: String? = null,
        ambienceVolumePercent: Int = 20
    ): String {
        val spokenLines = lines.filter { it.text.isNotBlank() }
        if (spokenLines.isEmpty()) return ""

        val sb = StringBuilder()
        if (!ambienceName.isNullOrBlank()) {
            val pct = ambienceVolumePercent.coerceIn(5, 80)
            sb.append("[ambiance: ").append(ambienceName.trim()).append(", ").append(pct).append("%]\n")
        }

        spokenLines.forEachIndexed { index, line ->
            if (index > 0 && line.pauseBeforeSec >= 0.2f) {
                val pauseFormatted = "%.1f".format(java.util.Locale.US, line.pauseBeforeSec.coerceIn(0.2f, 5.0f))
                sb.append("[pause: ").append(pauseFormatted).append("s]\n")
            }
            val safeVoice = VoiceCatalog.findByName(line.voiceName)?.name ?: "Kore"
            val safeTone = line.tone.trim().ifBlank { "naturel" }
            sb.append("[voix: ").append(safeVoice).append("] ")
            sb.append("[ton: ").append(safeTone).append("] ")
            sb.append("[vitesse: ").append(line.speed.labelFr).append("] ")
            sb.append(line.text.trim())
            if (index < spokenLines.lastIndex) {
                sb.append('\n')
            }
        }
        return sb.toString().trim()
    }
}

// --- Modèles / Presets « Studio » prêts à l'emploi ---

data class StudioPreset(
    val id: String,
    val titleFr: String,
    val subtitleFr: String,
    val briefDescription: String,
    val sampleRawText: String,
    val suggestedVoice: String,
    val targetDuration: TargetDurationOption,
    val genderPreference: VoiceGenderPreference,
    val mixConfig: MixConfig
)

object StudioPresetCatalog {
    val presets: List<StudioPreset> = listOf(
        StudioPreset(
            id = "pub_radio_30",
            titleFr = "Pub radio 30s",
            subtitleFr = "Énergique • -14 LUFS",
            briefDescription = "Spot publicitaire radio de 30 secondes percutant, voix dynamique et chaleureuse, rythme rapide avec musique joyeuse légère en arrière-plan.",
            sampleRawText = "",
            suggestedVoice = "Puck",
            targetDuration = TargetDurationOption.SEC_30,
            genderPreference = VoiceGenderPreference.MASCULINE,
            mixConfig = MixConfig(
                voiceVolume = 1.08f,
                ambienceVolume = 0.9f,
                duckingAmountDb = 12f,
                targetLufs = -14.0f,
                reverbPreset = ReverbPreset.NONE
            )
        ),
        StudioPreset(
            id = "reel_tiktok",
            titleFr = "Reel / TikTok",
            subtitleFr = "Hook viral • 15s",
            briefDescription = "Vidéo courte verticale 15 secondes très dynamique dès la première seconde, voix jeune et spontanée, débit vif avec micro-pauses d'accroche.",
            sampleRawText = "",
            suggestedVoice = "Leda",
            targetDuration = TargetDurationOption.SEC_15,
            genderPreference = VoiceGenderPreference.FEMININE,
            mixConfig = MixConfig(
                voiceVolume = 1.12f,
                ambienceVolume = 0.85f,
                duckingAmountDb = 11f,
                targetLufs = -14.0f,
                reverbPreset = ReverbPreset.STUDIO
            )
        ),
        StudioPreset(
            id = "podcast_intro",
            titleFr = "Intro Podcast",
            subtitleFr = "Chaleureux • -16 LUFS",
            briefDescription = "Introduction de podcast accueillante et complice, voix posée proche du micro avec nappe douce en fondu d'entrée et de sortie.",
            sampleRawText = "",
            suggestedVoice = "Sulafat",
            targetDuration = TargetDurationOption.SEC_60,
            genderPreference = VoiceGenderPreference.FEMININE,
            mixConfig = MixConfig(
                voiceVolume = 1.0f,
                ambienceVolume = 0.95f,
                duckingAmountDb = 11f,
                fadeDurationSec = 2.0f,
                targetLufs = -16.0f,
                reverbPreset = ReverbPreset.STUDIO
            )
        ),
        StudioPreset(
            id = "corporate_accueil",
            titleFr = "Accueil pro",
            subtitleFr = "Corporate • Net",
            briefDescription = "Message d'accueil téléphonique ou présentation d'entreprise professionnelle, articulation claire, ton rassurant et crédible sans musique envahissante.",
            sampleRawText = "",
            suggestedVoice = "Gacrux",
            targetDuration = TargetDurationOption.SEC_30,
            genderPreference = VoiceGenderPreference.FEMININE,
            mixConfig = MixConfig(
                voiceVolume = 1.0f,
                ambienceVolume = 0.65f,
                duckingAmountDb = 12f,
                targetLufs = -16.0f,
                reverbPreset = ReverbPreset.NONE
            )
        ),
        StudioPreset(
            id = "narration_conte",
            titleFr = "Conte / Récit",
            subtitleFr = "Immersif • -19 LUFS",
            briefDescription = "Narration calme et cinématographique, rythme posé avec respirations entre les phrases, pluie douce en fond et légère réverbération de salle.",
            sampleRawText = "",
            suggestedVoice = "Aoede",
            targetDuration = TargetDurationOption.SEC_120,
            genderPreference = VoiceGenderPreference.AUTO,
            mixConfig = MixConfig(
                voiceVolume = 1.0f,
                ambienceVolume = 1.05f,
                duckingAmountDb = 10f,
                fadeDurationSec = 2.4f,
                targetLufs = -19.0f,
                reverbPreset = ReverbPreset.HALL
            )
        )
    )
}

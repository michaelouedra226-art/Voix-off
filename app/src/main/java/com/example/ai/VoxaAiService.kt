package com.example.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.audio.PcmUtils
import com.example.domain.model.AmbienceCatalog
import com.example.domain.model.AmbiencePlanItem
import com.example.domain.model.BriefPlan
import com.example.domain.model.SpeechSpeed
import com.example.domain.model.TargetDurationOption
import com.example.domain.model.ToneChangePlan
import com.example.domain.model.TtsModelOption
import com.example.domain.model.VoiceCatalog
import com.example.domain.model.VoiceGenderPreference
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class BriefPlanningException(
    message: String,
    val fallbackScript: String
) : Exception(message)

/**
 * Service d'appels réels à Gemini via Firebase AI Logic :
 * - Planification et affinage de mise en scène audio via gemini-3.8-flash (JSON structuré)
 * - Synthèse vocale Gemini TTS avec direction d'acteur structurée et réessai progressif
 */
class VoxaAiService(private val context: Context) {

    companion object {
        private const val TAG = "VoxaAiService"
        private const val DIRECTOR_MODEL = "gemini-3.8-flash"
    }

    private val briefSchema = Schema.obj(
        properties = mapOf(
            "annotatedScript" to Schema.string(
                description = "Le script complet annoté avec la syntaxe entre crochets [voix: ...], [ton: ...], [vitesse: ...], [pause: ...s], [ambiance: ..., X%], [ambiance: stop]."
            ),
            "selectedVoice" to Schema.string(
                description = "Le nom exact d'une des 14 voix Gemini choisie pour ce projet."
            ),
            "recommendedModel" to Schema.string(
                description = "Identifiant du modèle TTS recommandé."
            ),
            "ambiences" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "name" to Schema.string(description = "Nom exact du préréglage d'ambiance"),
                        "volumePercent" to Schema.integer(description = "Volume entre 5 et 60")
                    )
                ),
                description = "Liste des ambiances utilisées et leur volume en pourcentage"
            ),
            "estimatedDurationSeconds" to Schema.integer(
                description = "Durée totale estimée en secondes"
            ),
            "toneChanges" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "cue" to Schema.string(description = "La consigne de ton appliquée"),
                        "excerpt" to Schema.string(description = "Court extrait du texte concerné"),
                        "reason" to Schema.string(description = "Pourquoi ce changement à cet endroit")
                    )
                ),
                description = "Résumé des variations de ton dans le script"
            ),
            "directorExplanation" to Schema.string(
                description = "Une phrase claire en français expliquant les choix artistiques."
            )
        )
    )

    private val refineSchema = Schema.obj(
        properties = mapOf(
            "annotatedScript" to Schema.string(
                description = "Le script annoté mis à jour selon la demande d'affinage."
            ),
            "selectedVoice" to Schema.string(
                description = "La voix principale résultante."
            ),
            "explanation" to Schema.string(
                description = "Explication courte en français de la modification effectuée."
            )
        )
    )

    private val directorSystemInstruction by lazy {
        val voicesInfo = VoiceCatalog.voices.joinToString("\n") {
            "- ${it.name} (${it.genderFr}) : ${it.timbreFr} — ${it.descriptionFr}"
        }
        val ambiencesInfo = AmbienceCatalog.presets.joinToString(", ") { it.name }
        content {
            text(
                """
                Tu es un directeur artistique audio senior francophone.
                Ta mission : transformer une description (et éventuellement un texte fourni par l'utilisateur) en un script de mise en scène audio prêt pour la synthèse vocale.

                SYNTAXE EXACTE DES CONSIGNES ENTRE CROCHETS :
                - [voix: NomDeLaVoix] (persiste jusqu'au prochain changement)
                - [ton: description libre du jeu et de l'émotion] (persiste jusqu'au prochain changement)
                - [vitesse: lent | normal | rapide] (persiste jusqu'au prochain changement)
                - [pause: 1.5s] (entre 0.2s et 10s)
                - [ambiance: nom exact, 25%] (entre 5% et 60%, ou 20% par défaut)
                - [ambiance: stop]

                VOIX DISPONIBLES (utilise EXACTEMENT un de ces noms) :
                $voicesInfo

                AMBIANCES DISPONIBLES (utilise EXACTEMENT un de ces noms si une ambiance est pertinente) :
                $ambiencesInfo

                MODÈLE TTS RECOMMANDÉ :
                - gemini-3.1-flash-tts-preview (modèle officiel rapide et expressif par défaut — utilise toujours le modèle préféré indiqué dans les réglages)

                RÈGLE D'OR ABSOLUE :
                - Si l'utilisateur fournit un texte non vide dans "Texte fourni", tu ne dois JAMAIS modifier, reformuler, corriger ou supprimer ses mots, sauf s'il demande explicitement de réécrire dans sa description. Tu ajoutes UNIQUEMENT les consignes entre crochets aux bons endroits (changements de ton, pauses naturelles, ambiance, vitesse, voix).
                - Si le texte fourni est vide, rédige un texte naturel et percutant répondant exactement à la description, dans la langue demandée et calibré pour la durée cible.
                - Réponds uniquement en JSON valide conforme au schéma.
                """.trimIndent()
            )
        }
    }

    private val briefModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = DIRECTOR_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = briefSchema
                temperature = 0.5f
            },
            systemInstruction = directorSystemInstruction
        )
    }

    private val refineModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = DIRECTOR_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = refineSchema
                temperature = 0.3f
            },
            systemInstruction = directorSystemInstruction
        )
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun generateBriefPlan(
        description: String,
        rawText: String,
        targetDuration: TargetDurationOption,
        languageLabel: String,
        genderPreference: VoiceGenderPreference,
        preferredModelId: String
    ): BriefPlan {
        if (!isNetworkAvailable()) {
            throw BriefPlanningException(
                "Vous êtes hors ligne. Vérifiez votre connexion Internet puis réessayez.",
                rawText.ifBlank { description }
            )
        }

        val durationConstraint = targetDuration.seconds?.let { "${it} secondes environ" } ?: "libre"
        val genderConstraint = when (genderPreference) {
            VoiceGenderPreference.AUTO -> "au choix selon le contexte"
            VoiceGenderPreference.FEMININE -> "voix féminine souhaitée"
            VoiceGenderPreference.MASCULINE -> "voix masculine souhaitée"
        }

        val prompt = buildString {
            appendLine("Demande du client : ${description.ifBlank { "Produire une voix off professionnelle pour le texte ci-dessous." }}")
            appendLine("Langue souhaitée : $languageLabel")
            appendLine("Durée cible : $durationConstraint")
            appendLine("Préférence de voix : $genderConstraint")
            appendLine("Modèle TTS préféré dans les réglages : $preferredModelId")
            if (rawText.isNotBlank()) {
                appendLine("Texte fourni (NE PAS MODIFIER LES MOTS, ajouter uniquement les crochets) :")
                appendLine(rawText)
            } else {
                appendLine("Texte fourni : (vide — rédige le script complet correspondant à la demande avec les consignes entre crochets).")
            }
        }

        var lastError: Exception? = null
        // 1 tentative initiale + 1 réessai automatique si JSON invalide
        for (attempt in 0..1) {
            try {
                val response = withTimeout(60_000L) {
                    briefModel.generateContent(prompt)
                }
                val jsonText = response.text
                    ?: throw IllegalStateException("Réponse vide du directeur artistique.")
                return parseBriefPlanJson(jsonText, preferredModelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tentative $attempt de planification échouée", e)
                lastError = e
                if (attempt == 0 && e is org.json.JSONException) {
                    continue
                }
                if (attempt == 0 && e.message?.contains("JSON", ignoreCase = true) == true) {
                    continue
                }
                break
            }
        }

        val readable = translateAiError(lastError ?: Exception("Erreur inconnue"))
        throw BriefPlanningException(readable, rawText.ifBlank { description })
    }

    suspend fun refineAnnotatedScript(
        currentScript: String,
        instruction: String,
        customAmbienceNames: List<String>
    ): Pair<String, String> {
        if (!isNetworkAvailable()) {
            throw IllegalStateException("Vous êtes hors ligne. L'affinage par l'IA nécessite une connexion Internet.")
        }

        val customNote = if (customAmbienceNames.isNotEmpty()) {
            "\nAmbiances personnalisées disponibles : ${customAmbienceNames.joinToString(", ")}"
        } else ""

        val prompt = """
            Voici le script annoté actuel :
            $currentScript

            Demande de modification de l'utilisateur : "$instruction"
            $customNote

            Consigne importante : ne modifie QUE les balises ou passages concernés par la demande afin de conserver au maximum les autres segments identiques pour le cache audio. Ne modifie les mots parlés que si la demande le demande explicitement.
        """.trimIndent()

        try {
            val response = withTimeout(60_000L) {
                refineModel.generateContent(prompt)
            }
            val jsonText = response.text ?: throw IllegalStateException("Réponse vide lors de l'affinage.")
            val obj = JSONObject(jsonText)
            val updated = obj.getString("annotatedScript").trim()
            val explanation = obj.optString("explanation", "Mise en scène mise à jour.")
            if (updated.isBlank()) throw IllegalStateException("Le script mis à jour est vide.")
            return updated to explanation
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Échec de l'affinage", e)
            throw IllegalStateException(translateAiError(e), e)
        }
    }

    /**
     * Appelle Gemini TTS via Firebase AI Logic avec direction d'acteur structurée
     * et réessai progressif sur erreurs 429 / 5xx / timeout.
     */
    @OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)
    suspend fun synthesizeSegmentPcm(
        spokenText: String,
        voiceName: String,
        tone: String,
        speed: SpeechSpeed,
        activeAmbience: String?,
        modelId: String,
        languageCode: String
    ): FloatArray {
        if (!isNetworkAvailable()) {
            throw IllegalStateException("Hors ligne : impossible de contacter Gemini TTS.")
        }

        val resolvedVoice = VoiceCatalog.findByName(voiceName)?.name ?: "Kore"
        val resolvedModel = TtsModelOption.fromId(modelId).modelId

        val speechCfg = if (languageCode != "auto" && languageCode.isNotBlank()) {
            SpeechConfig(voice = Voice(resolvedVoice), languageCode = languageCode)
        } else {
            SpeechConfig(voice = Voice(resolvedVoice))
        }

        val ttsModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = resolvedModel,
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = speechCfg
            }
        )

        val ambienceHint = activeAmbience?.let {
            AmbienceCatalog.findByName(it)?.sceneHintFr ?: "avec en arrière-plan sonore : $it"
        } ?: "voix studio nette et directe"

        val actorPrompt = buildString {
            append("Scène : $ambienceHint. ")
            append("Consigne de jeu (ne jamais prononcer ces indications) : dis avec un ton $tone, rythme ${speed.directiveFr}. ")
            append("Texte exact à dire :\n$spokenText")
        }

        val backoffDelaysMs = longArrayOf(1200L, 2600L, 5000L)
        var lastException: Exception? = null

        for (attempt in 0..backoffDelaysMs.size) {
            try {
                val response = withTimeout(60_000L) {
                    ttsModel.generateContent(actorPrompt)
                }

                val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
                val audioPart = parts.filterIsInstance<InlineDataPart>().firstOrNull()
                if (audioPart != null && audioPart.inlineData.isNotEmpty()) {
                    return PcmUtils.pcm16ToFloatArray(audioPart.inlineData)
                }
                val textFallback = response.text?.take(120)
                Log.w(TAG, "TTS sans donnée audio (parts=${parts.size}, text=$textFallback)")
                throw IllegalStateException("Le modèle TTS n'a renvoyé aucune donnée audio pour ce segment.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Échec TTS tentative ${attempt + 1}", e)
                val retryable = isRetryableError(e)
                if (retryable && attempt < backoffDelaysMs.size) {
                    delay(backoffDelaysMs[attempt])
                    continue
                }
                throw IllegalStateException(translateAiError(e), e)
            }
        }

        throw IllegalStateException(translateAiError(lastException ?: Exception("Échec TTS")))
    }

    private fun parseBriefPlanJson(rawJson: String, fallbackModelId: String): BriefPlan {
        val obj = JSONObject(rawJson)
        val annotated = obj.getString("annotatedScript").trim()
        if (annotated.isBlank()) throw org.json.JSONException("annotatedScript vide")

        val voiceRaw = obj.optString("selectedVoice", "Kore")
        val voice = VoiceCatalog.findByName(voiceRaw)?.name ?: "Kore"
        val rawRecModel = obj.optString("recommendedModel", fallbackModelId).trim()
        val recModel = if (rawRecModel == TtsModelOption.GEMINI_3_1_FLASH_TTS.modelId) {
            rawRecModel
        } else {
            fallbackModelId.ifBlank { TtsModelOption.GEMINI_3_1_FLASH_TTS.modelId }
        }
        val estSec = obj.optInt("estimatedDurationSeconds", 30).coerceAtLeast(3)
        val explanation = obj.optString("directorExplanation", "Mise en scène structurée selon votre brief.")

        val ambiences = mutableListOf<AmbiencePlanItem>()
        val ambArr = obj.optJSONArray("ambiences")
        if (ambArr != null) {
            for (i in 0 until ambArr.length()) {
                val item = ambArr.optJSONObject(i) ?: continue
                val n = item.optString("name").trim()
                if (n.isNotEmpty()) {
                    ambiences.add(AmbiencePlanItem(n, item.optInt("volumePercent", 20).coerceIn(5, 80)))
                }
            }
        }

        val toneChanges = mutableListOf<ToneChangePlan>()
        val toneArr = obj.optJSONArray("toneChanges")
        if (toneArr != null) {
            for (i in 0 until toneArr.length()) {
                val item = toneArr.optJSONObject(i) ?: continue
                toneChanges.add(
                    ToneChangePlan(
                        cue = item.optString("cue", ""),
                        excerpt = item.optString("excerpt", ""),
                        reason = item.optString("reason", "")
                    )
                )
            }
        }

        return BriefPlan(
            annotatedScript = annotated,
            selectedVoice = voice,
            recommendedModel = recModel,
            ambiences = ambiences,
            estimatedDurationSeconds = estSec,
            toneChanges = toneChanges,
            directorExplanation = explanation
        )
    }

    private fun isRetryableError(e: Exception): Boolean {
        if (e is TimeoutCancellationException) return true
        val msg = (e.message ?: "").lowercase()
        return msg.contains("429") ||
            msg.contains("quota") ||
            msg.contains("rate limit") ||
            msg.contains("resource exhausted") ||
            msg.contains("500") ||
            msg.contains("502") ||
            msg.contains("503") ||
            msg.contains("504") ||
            msg.contains("unavailable") ||
            msg.contains("timeout")
    }

    fun translateAiError(e: Throwable): String {
        val raw = e.message.orEmpty()
        val msg = raw.lowercase()
        return when {
            e is TimeoutCancellationException || msg.contains("timeout") ->
                "Délai d'attente dépassé (60 s). Réessayez avec un segment plus court ou vérifiez votre connexion."
            !isNetworkAvailable() || msg.contains("unable to resolve host") || msg.contains("network") ->
                "Hors ligne : aucune connexion Internet détectée. Vos projets et audios déjà générés restent accessibles localement."
            msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted") ->
                "Quota Gemini temporairement atteint (429). Patientez quelques instants avant de relancer la génération."
            msg.contains("403") || msg.contains("app check") || msg.contains("attestation") ->
                "Accès refusé par Firebase App Check (403). Vérifiez la configuration Firebase du projet ou relancez l'application."
            msg.contains("401") || msg.contains("unauthenticated") || msg.contains("api key") ->
                "Authentification refusée par le service Gemini. Vérifiez l'activation de Firebase AI Logic."
            msg.contains("404") || msg.contains("not found") ->
                "Modèle sélectionné indisponible sur cette région. Essayez Gemini 3.1 Flash TTS dans les Réglages."
            raw.isNotBlank() ->
                "Erreur du service audio : $raw"
            else ->
                "Une erreur inattendue est survenue lors de l'appel à Gemini."
        }
    }
}

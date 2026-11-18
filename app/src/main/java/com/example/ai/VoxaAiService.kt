package com.example.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.audio.PcmUtils
import com.example.domain.model.AmbienceCatalog
import com.example.domain.model.AmbiencePlanItem
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiPoolStrategy
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class BriefPlanningException(
    message: String,
    val fallbackScript: String
) : Exception(message)

/**
 * Service d'appels réels à Gemini via pool multi-clés REST direct ou Firebase AI Logic :
 * - Planification et affinage de mise en scène audio (JSON structuré)
 * - Synthèse vocale Gemini TTS avec rotation de clés, bascule sur quota et réessai progressif
 */
class VoxaAiService(private val context: Context) {

    companion object {
        private const val TAG = "VoxaAiService"
        private const val DIRECTOR_MODEL = "gemini-3.8-flash"
        private const val DIRECTOR_FALLBACK_MODEL = "gemini-2.5-flash"
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

    private val directorSystemPromptText: String by lazy {
        val voicesInfo = VoiceCatalog.voices.joinToString("\n") {
            "- ${it.name} (${it.genderFr}) : ${it.timbreFr} — ${it.descriptionFr}"
        }
        val ambiencesInfo = AmbienceCatalog.presets.joinToString(", ") { it.name }
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
    }

    private val directorSystemInstruction by lazy {
        content {
            text(directorSystemPromptText)
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

    private fun resolveEffectiveKeys(apiKey: String, apiKeys: List<String>): List<String> =
        (apiKeys + listOf(apiKey))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    suspend fun verifyPersonalApiKey(apiKey: String): Result<String> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return Result.failure(IllegalArgumentException("Saisissez une clé API Gemini avant de tester."))
        }
        if (!isNetworkAvailable()) {
            return Result.failure(IllegalStateException("Hors ligne : impossible de vérifier la clé API Gemini."))
        }
        return try {
            val res = withTimeout(20_000L) {
                GeminiDirectRestClient.verifyApiKey(cleanKey)
            }
            res.fold(
                onSuccess = {
                    ApiPoolCoordinator.markSuccess(cleanKey)
                    Result.success(it)
                },
                onFailure = { err ->
                    Result.failure(IllegalStateException(translateAiError(err, usingPersonalKey = true), err))
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(translateAiError(e, usingPersonalKey = true), e))
        }
    }

    suspend fun verifyApiKeyPool(entries: List<ApiKeyEntry>): List<ApiKeyEntry> = coroutineScope {
        entries.map { entry ->
            async {
                val res = verifyPersonalApiKey(entry.apiKey)
                res.fold(
                    onSuccess = { msg -> entry.copy(lastStatusOk = true, lastStatusMessage = msg) },
                    onFailure = { err ->
                        entry.copy(
                            lastStatusOk = false,
                            lastStatusMessage = err.message ?: "Clé invalide"
                        )
                    }
                )
            }
        }.awaitAll()
    }

    suspend fun generateBriefPlan(
        description: String,
        rawText: String,
        targetDuration: TargetDurationOption,
        languageLabel: String,
        genderPreference: VoiceGenderPreference,
        preferredModelId: String,
        apiKey: String = "",
        apiKeys: List<String> = emptyList(),
        poolStrategy: ApiPoolStrategy = ApiPoolStrategy.PARALLEL_BOOST,
        allowModelFallback: Boolean = true
    ): BriefPlan {
        val effectiveKeys = resolveEffectiveKeys(apiKey, apiKeys)
        val usePersonalKey = effectiveKeys.isNotEmpty()

        if (!usePersonalKey && !ensureFirebaseAppInitialized(context)) {
            throw BriefPlanningException(
                "Configuration Firebase absente dans cet APK. Ajoutez une ou plusieurs clés API Gemini dans Réglages ou vérifiez app/google-services.json.",
                rawText.ifBlank { description }
            )
        }
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

        val directorModels = if (usePersonalKey && allowModelFallback) {
            listOf(DIRECTOR_MODEL, DIRECTOR_FALLBACK_MODEL)
        } else {
            listOf(DIRECTOR_MODEL)
        }

        var lastError: Exception? = null
        // 1 tentative initiale + 1 réessai automatique si JSON invalide
        for (attempt in 0..1) {
            try {
                val jsonText = withTimeout(60_000L) {
                    if (usePersonalKey) {
                        var modelError: Exception? = null
                        var output: String? = null
                        for ((mIdx, dirModel) in directorModels.withIndex()) {
                            try {
                                output = ApiPoolCoordinator.executeAcrossPool(effectiveKeys, poolStrategy) { selectedKey ->
                                    GeminiDirectRestClient.generateStructuredJson(
                                        apiKey = selectedKey,
                                        modelId = dirModel,
                                        systemInstruction = directorSystemPromptText,
                                        prompt = prompt,
                                        responseSchemaJson = GeminiDirectRestClient.buildBriefResponseSchemaJson(),
                                        temperature = 0.5f
                                    )
                                }
                                break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                modelError = e
                                if (mIdx == directorModels.lastIndex || !isQuotaOrModelUnavailable(e)) {
                                    throw e
                                }
                            }
                        }
                        output ?: throw (modelError ?: IllegalStateException("Réponse vide du directeur artistique."))
                    } else {
                        val response = briefModel.generateContent(prompt)
                        response.text ?: throw IllegalStateException("Réponse vide du directeur artistique.")
                    }
                }
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

        val readable = translateAiError(lastError ?: Exception("Erreur inconnue"), usingPersonalKey = usePersonalKey)
        throw BriefPlanningException(readable, rawText.ifBlank { description })
    }

    suspend fun refineAnnotatedScript(
        currentScript: String,
        instruction: String,
        customAmbienceNames: List<String>,
        apiKey: String = "",
        apiKeys: List<String> = emptyList(),
        poolStrategy: ApiPoolStrategy = ApiPoolStrategy.PARALLEL_BOOST,
        allowModelFallback: Boolean = true
    ): Pair<String, String> {
        val effectiveKeys = resolveEffectiveKeys(apiKey, apiKeys)
        val usePersonalKey = effectiveKeys.isNotEmpty()

        if (!usePersonalKey && !ensureFirebaseAppInitialized(context)) {
            throw IllegalStateException("Configuration Firebase absente dans cet APK. Ajoutez une clé API Gemini dans Réglages ou vérifiez app/google-services.json.")
        }
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

        val directorModels = if (usePersonalKey && allowModelFallback) {
            listOf(DIRECTOR_MODEL, DIRECTOR_FALLBACK_MODEL)
        } else {
            listOf(DIRECTOR_MODEL)
        }

        try {
            val jsonText = withTimeout(60_000L) {
                if (usePersonalKey) {
                    var modelError: Exception? = null
                    var output: String? = null
                    for ((mIdx, dirModel) in directorModels.withIndex()) {
                        try {
                            output = ApiPoolCoordinator.executeAcrossPool(effectiveKeys, poolStrategy) { selectedKey ->
                                GeminiDirectRestClient.generateStructuredJson(
                                    apiKey = selectedKey,
                                    modelId = dirModel,
                                    systemInstruction = directorSystemPromptText,
                                    prompt = prompt,
                                    responseSchemaJson = GeminiDirectRestClient.buildRefineResponseSchemaJson(),
                                    temperature = 0.3f
                                )
                            }
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            modelError = e
                            if (mIdx == directorModels.lastIndex || !isQuotaOrModelUnavailable(e)) {
                                throw e
                            }
                        }
                    }
                    output ?: throw (modelError ?: IllegalStateException("Réponse vide lors de l'affinage."))
                } else {
                    val response = refineModel.generateContent(prompt)
                    response.text ?: throw IllegalStateException("Réponse vide lors de l'affinage.")
                }
            }
            val obj = JSONObject(jsonText)
            val updated = obj.getString("annotatedScript").trim()
            val explanation = obj.optString("explanation", "Mise en scène mise à jour.")
            if (updated.isBlank()) throw IllegalStateException("Le script mis à jour est vide.")
            return updated to explanation
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Échec de l'affinage", e)
            throw IllegalStateException(translateAiError(e, usingPersonalKey = usePersonalKey), e)
        }
    }

    /**
     * Appelle Gemini TTS via pool multi-clés (ou Firebase AI Logic si aucune clé personnelle),
     * avec bascule immédiate entre clés, bascule optionnelle de modèle sur 429, et réessai progressif.
     */
    @OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)
    suspend fun synthesizeSegmentPcm(
        spokenText: String,
        voiceName: String,
        tone: String,
        speed: SpeechSpeed,
        activeAmbience: String?,
        modelId: String,
        languageCode: String,
        apiKey: String = "",
        apiKeys: List<String> = emptyList(),
        poolStrategy: ApiPoolStrategy = ApiPoolStrategy.PARALLEL_BOOST,
        allowModelFallback: Boolean = true
    ): FloatArray {
        val effectiveKeys = resolveEffectiveKeys(apiKey, apiKeys)
        val usePersonalKey = effectiveKeys.isNotEmpty()

        if (!usePersonalKey && !ensureFirebaseAppInitialized(context)) {
            throw IllegalStateException("Configuration Firebase absente dans cet APK. Ajoutez une ou plusieurs clés API Gemini dans Réglages ou vérifiez app/google-services.json.")
        }
        if (!isNetworkAvailable()) {
            throw IllegalStateException("Hors ligne : impossible de contacter Gemini TTS.")
        }

        val resolvedVoice = VoiceCatalog.findByName(voiceName)?.name ?: "Kore"
        val resolvedModel = TtsModelOption.fromId(modelId).modelId
        val modelChain = if (usePersonalKey && allowModelFallback) {
            (listOf(resolvedModel) + TtsModelOption.entries.map { it.modelId }).distinct()
        } else {
            listOf(resolvedModel)
        }

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
                if (usePersonalKey) {
                    return withTimeout(65_000L) {
                        var modelErr: Exception? = null
                        var pcmResult: FloatArray? = null
                        for ((mIdx, candidateModel) in modelChain.withIndex()) {
                            try {
                                pcmResult = ApiPoolCoordinator.executeAcrossPool(effectiveKeys, poolStrategy) { selectedKey ->
                                    GeminiDirectRestClient.synthesizeTtsPcm(
                                        apiKey = selectedKey,
                                        modelId = candidateModel,
                                        actorPrompt = actorPrompt,
                                        voiceName = resolvedVoice,
                                        languageCode = languageCode
                                    )
                                }
                                break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                modelErr = e
                                if (mIdx == modelChain.lastIndex || !isQuotaOrModelUnavailable(e)) {
                                    throw e
                                }
                            }
                        }
                        pcmResult ?: throw (modelErr ?: IllegalStateException("Échec TTS sur le pool multi-clés."))
                    }
                } else {
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
                }
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
                throw IllegalStateException(translateAiError(e, usingPersonalKey = usePersonalKey), e)
            }
        }

        throw IllegalStateException(translateAiError(lastException ?: Exception("Échec TTS"), usingPersonalKey = usePersonalKey))
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

    private fun unwrapPoolError(e: Throwable): Throwable =
        if (e is ApiPoolExhaustedException) e.lastError else e

    private fun isQuotaOrModelUnavailable(e: Exception): Boolean {
        val root = unwrapPoolError(e)
        if (root is GeminiRestHttpException) {
            return root.statusCode == 429 || root.statusCode == 404
        }
        val msg = (root.message ?: "").lowercase()
        return msg.contains("429") ||
            msg.contains("quota") ||
            msg.contains("rate limit") ||
            msg.contains("resource exhausted") ||
            msg.contains("404") ||
            msg.contains("not found")
    }

    private fun isRetryableError(e: Exception): Boolean {
        val root = unwrapPoolError(e)
        if (root is TimeoutCancellationException) return true
        if (root is GeminiRestHttpException) {
            return root.statusCode == 429 || root.statusCode in 500..599
        }
        val msg = (root.message ?: "").lowercase()
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

    fun translateAiError(e: Throwable, usingPersonalKey: Boolean = false): String {
        val poolCountSuffix = if (e is ApiPoolExhaustedException && e.attemptedKeysCount > 1) {
            " (${e.attemptedKeysCount} clés du pool testées)"
        } else {
            ""
        }
        val root = unwrapPoolError(e)

        if (root is GeminiRestHttpException) {
            val lower = root.message.lowercase()
            return when {
                root.statusCode == 400 && (root.apiStatus == "API_KEY_INVALID" || lower.contains("api key") || lower.contains("api_key_invalid")) ->
                    "Clé API Gemini invalide$poolCountSuffix. Vérifiez vos clés dans Réglages > Pool multi-API Gemini."
                root.statusCode == 401 || root.statusCode == 403 ->
                    "Clé API Gemini refusée (${root.statusCode})$poolCountSuffix. Vérifiez les restrictions ou la validité de vos clés dans Google AI Studio."
                root.statusCode == 404 ->
                    "Modèle introuvable pour votre clé API (404)$poolCountSuffix. Essayez un autre modèle TTS ou activez la bascule de modèle dans les Réglages."
                root.statusCode == 429 ->
                    "Quota atteint sur l'ensemble des clés$poolCountSuffix (429). Patientez quelques instants ou ajoutez une autre clé dans Réglages."
                else ->
                    "Erreur de l'API Gemini (${root.statusCode})$poolCountSuffix : ${root.message}"
            }
        }

        val raw = root.message.orEmpty()
        val msg = raw.lowercase()
        return when {
            msg.contains("default firebaseapp is not initialized") || msg.contains("initializeapp(context)") ->
                "Firebase n'est pas initialisé dans cet APK. Ajoutez une ou plusieurs clés API Gemini dans Réglages ou vérifiez app/google-services.json."
            root is TimeoutCancellationException || msg.contains("timeout") ->
                "Délai d'attente dépassé (60 s)$poolCountSuffix. Réessayez avec un segment plus court ou vérifiez votre connexion."
            !isNetworkAvailable() || msg.contains("unable to resolve host") || msg.contains("network") ->
                "Hors ligne : aucune connexion Internet détectée. Vos projets et audios déjà générés restent accessibles localement."
            msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted") ->
                "Quota Gemini temporairement atteint$poolCountSuffix (429). Patientez quelques instants avant de relancer la génération."
            usingPersonalKey && (msg.contains("403") || msg.contains("401") || msg.contains("api key") || msg.contains("permission_denied")) ->
                "Clé API Gemini refusée$poolCountSuffix. Vérifiez vos clés dans Réglages > Pool multi-API Gemini."
            msg.contains("403") || msg.contains("app check") || msg.contains("attestation") ->
                if (com.example.BuildConfig.DEBUG) {
                    "Accès refusé par Firebase App Check (403) sur cet APK Debug hors d'AI Studio. Ajoutez votre clé API Gemini dans Réglages pour générer directement depuis ce téléphone."
                } else {
                    "Accès refusé par Firebase App Check (403). Vérifiez la connexion réseau, l'heure de l'appareil ou ajoutez votre clé API Gemini dans Réglages."
                }
            msg.contains("401") || msg.contains("unauthenticated") || msg.contains("api key") ->
                "Authentification refusée par le service Gemini$poolCountSuffix. Vérifiez vos clés API dans Réglages ou l'activation de Firebase AI Logic."
            msg.contains("404") || msg.contains("not found") ->
                "Modèle sélectionné indisponible sur cette région$poolCountSuffix. Essayez Gemini 3.1 Flash TTS ou Gemini 2.5 Flash TTS dans les Réglages."
            raw.isNotBlank() ->
                "Erreur du service audio$poolCountSuffix : $raw"
            else ->
                "Une erreur inattendue est survenue lors de l'appel à Gemini$poolCountSuffix."
        }
    }
}

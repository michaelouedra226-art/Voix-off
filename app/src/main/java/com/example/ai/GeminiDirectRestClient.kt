package com.example.ai

import android.util.Base64
import com.example.audio.PcmUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GeminiRestHttpException(
    val statusCode: Int,
    val apiStatus: String?,
    override val message: String
) : Exception(message)

/**
 * Client REST direct vers l'API Gemini Developer (generativelanguage.googleapis.com),
 * utilisé uniquement lorsque l'utilisateur saisit sa propre clé API dans Réglages.
 */
object GeminiDirectRestClient {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(65, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun verifyApiKey(apiKey: String): Result<String> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return Result.failure(IllegalArgumentException("Saisissez une clé API Gemini avant de tester."))
        }
        val request = Request.Builder()
            .url("$BASE_URL/models?pageSize=1")
            .header("x-goog-api-key", cleanKey)
            .get()
            .build()

        return try {
            awaitCall(request)
            Result.success("Clé API Gemini valide et prête.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateStructuredJson(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        prompt: String,
        responseSchemaJson: JSONObject,
        temperature: Float
    ): String {
        val cleanKey = apiKey.trim()
        val payload = buildStructuredJsonRequestBody(
            systemInstruction = systemInstruction,
            prompt = prompt,
            responseSchemaJson = responseSchemaJson,
            temperature = temperature
        )
        val request = Request.Builder()
            .url("$BASE_URL/models/$modelId:generateContent")
            .header("x-goog-api-key", cleanKey)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val rawResponse = awaitCall(request)
        return extractTextFromResponse(rawResponse)
    }

    suspend fun synthesizeTtsPcm(
        apiKey: String,
        modelId: String,
        actorPrompt: String,
        voiceName: String,
        languageCode: String
    ): FloatArray {
        val cleanKey = apiKey.trim()
        val payload = buildTtsRequestBody(
            actorPrompt = actorPrompt,
            voiceName = voiceName,
            languageCode = languageCode
        )
        val request = Request.Builder()
            .url("$BASE_URL/models/$modelId:generateContent")
            .header("x-goog-api-key", cleanKey)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val rawResponse = awaitCall(request)
        val b64Audio = extractAudioBase64FromResponse(rawResponse)
        val rawBytes = Base64.decode(b64Audio, Base64.DEFAULT)
        val pcmBytes = stripWavHeaderIfPresent(rawBytes)
        if (pcmBytes.isEmpty()) {
            throw IllegalStateException("Le modèle TTS n'a renvoyé aucune donnée audio exploitable.")
        }
        return PcmUtils.pcm16ToFloatArray(pcmBytes)
    }

    private suspend fun awaitCall(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val bodyStr = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        if (!resp.isSuccessful) {
                            val err = parseErrorResponse(resp.code, bodyStr)
                            if (continuation.isActive) {
                                continuation.resumeWithException(err)
                            }
                            return
                        }
                        if (continuation.isActive) {
                            continuation.resume(bodyStr)
                        }
                    }
                }
            })
        }

    fun buildBriefResponseSchemaJson(): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            put("annotatedScript", JSONObject().apply {
                put("type", "STRING")
                put("description", "Le script complet annoté avec la syntaxe entre crochets [voix: ...], [ton: ...], [vitesse: ...], [pause: ...s], [ambiance: ..., X%], [ambiance: stop].")
            })
            put("selectedVoice", JSONObject().apply {
                put("type", "STRING")
                put("description", "Le nom exact d'une des 14 voix Gemini choisie pour ce projet.")
            })
            put("recommendedModel", JSONObject().apply {
                put("type", "STRING")
                put("description", "Identifiant du modèle TTS recommandé.")
            })
            put("ambiences", JSONObject().apply {
                put("type", "ARRAY")
                put("description", "Liste des ambiances utilisées et leur volume en pourcentage")
                put("items", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().put("type", "STRING"))
                        put("volumePercent", JSONObject().put("type", "INTEGER"))
                    })
                    put("required", JSONArray().put("name").put("volumePercent"))
                })
            })
            put("estimatedDurationSeconds", JSONObject().apply {
                put("type", "INTEGER")
                put("description", "Durée totale estimée en secondes")
            })
            put("toneChanges", JSONObject().apply {
                put("type", "ARRAY")
                put("description", "Résumé des variations de ton dans le script")
                put("items", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("cue", JSONObject().put("type", "STRING"))
                        put("excerpt", JSONObject().put("type", "STRING"))
                        put("reason", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray().put("cue").put("excerpt").put("reason"))
                })
            })
            put("directorExplanation", JSONObject().apply {
                put("type", "STRING")
                put("description", "Une phrase claire en français expliquant les choix artistiques.")
            })
        })
        put("required", JSONArray().apply {
            put("annotatedScript")
            put("selectedVoice")
            put("recommendedModel")
            put("ambiences")
            put("estimatedDurationSeconds")
            put("toneChanges")
            put("directorExplanation")
        })
    }

    fun buildRefineResponseSchemaJson(): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            put("annotatedScript", JSONObject().put("type", "STRING"))
            put("selectedVoice", JSONObject().put("type", "STRING"))
            put("explanation", JSONObject().put("type", "STRING"))
        })
        put("required", JSONArray().apply {
            put("annotatedScript")
            put("selectedVoice")
            put("explanation")
        })
    }

    fun buildStructuredJsonRequestBody(
        systemInstruction: String,
        prompt: String,
        responseSchemaJson: JSONObject,
        temperature: Float
    ): JSONObject = JSONObject().apply {
        put("systemInstruction", JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
        })
        put("contents", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        }))
        put("generationConfig", JSONObject().apply {
            put("responseMimeType", "application/json")
            put("responseSchema", responseSchemaJson)
            put("temperature", temperature.toDouble())
        })
    }

    fun buildTtsRequestBody(
        actorPrompt: String,
        voiceName: String,
        languageCode: String
    ): JSONObject = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", actorPrompt)))
        }))
        put("generationConfig", JSONObject().apply {
            put("responseModalities", JSONArray().put("AUDIO"))
            put("speechConfig", JSONObject().apply {
                if (languageCode != "auto" && languageCode.isNotBlank()) {
                    put("languageCode", languageCode)
                }
                put("voiceConfig", JSONObject().apply {
                    put("prebuiltVoiceConfig", JSONObject().apply {
                        put("voiceName", voiceName)
                    })
                })
            })
        })
    }

    fun extractTextFromResponse(rawJson: String): String {
        val root = JSONObject(rawJson)
        val candidates = root.optJSONArray("candidates")
        val firstCandidate = candidates?.optJSONObject(0)
            ?: throw IllegalStateException("Aucun résultat renvoyé par Gemini.")
        val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Réponse vide du directeur artistique.")

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (text.isNotEmpty()) sb.append(text)
        }
        val result = sb.toString().trim()
        if (result.isEmpty()) {
            val finishReason = firstCandidate.optString("finishReason", "")
            throw IllegalStateException(
                if (finishReason.isNotBlank()) "Réponse bloquée ou vide ($finishReason)."
                else "Réponse vide du directeur artistique."
            )
        }
        return result
    }

    fun extractAudioBase64FromResponse(rawJson: String): String {
        val root = JSONObject(rawJson)
        val candidates = root.optJSONArray("candidates")
        val firstCandidate = candidates?.optJSONObject(0)
            ?: throw IllegalStateException("Le modèle TTS n'a renvoyé aucun candidat audio.")
        val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                val data = inline?.optString("data").orEmpty()
                if (data.isNotBlank()) return data
            }
        }
        val finishReason = firstCandidate.optString("finishReason", "")
        throw IllegalStateException(
            if (finishReason.isNotBlank() && finishReason != "STOP") {
                "Le modèle TTS n'a pas pu générer l'audio ($finishReason)."
            } else {
                "Le modèle TTS n'a renvoyé aucune donnée audio pour ce segment."
            }
        )
    }

    fun stripWavHeaderIfPresent(bytes: ByteArray): ByteArray {
        val isRiffWav = bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
        return if (isRiffWav) bytes.copyOfRange(44, bytes.size) else bytes
    }

    fun parseErrorResponse(statusCode: Int, rawBody: String): GeminiRestHttpException {
        var msg = rawBody.take(300)
        var status: String? = null
        try {
            val errObj = JSONObject(rawBody).optJSONObject("error")
            if (errObj != null) {
                msg = errObj.optString("message", msg)
                status = errObj.optString("status", "").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
        }
        if (msg.isBlank()) msg = "Erreur HTTP $statusCode"
        return GeminiRestHttpException(statusCode = statusCode, apiStatus = status, message = msg)
    }
}

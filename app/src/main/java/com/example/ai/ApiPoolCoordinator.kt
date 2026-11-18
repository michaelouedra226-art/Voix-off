package com.example.ai

import com.example.domain.model.ApiPoolStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ApiPoolRuntimeStats(
    val totalRequests: Int = 0,
    val failoverSwitches: Int = 0,
    val cooldownKeysCount: Int = 0,
    val lastActiveKeySuffix: String? = null
)

class ApiPoolExhaustedException(
    val attemptedKeysCount: Int,
    val lastError: Exception
) : Exception(lastError.message ?: "Toutes les clés API ont échoué", lastError)

/**
 * Coordinateur thread-safe pour pool multi-clés API Gemini :
 * - Rotation équilibrée (Round-Robin / Turbo Parallèle) ou bascule prioritaire (Failover)
 * - Mise en pause temporaire (cooldown) des clés ayant atteint un quota 429 ou une erreur d'accès
 * - Bascule immédiate vers une autre clé disponible sans bloquer les autres segments
 */
object ApiPoolCoordinator {

    private val roundRobinCursor = AtomicInteger(0)
    private val cooldownUntilMsByKeyId = ConcurrentHashMap<String, Long>()
    private val totalRequestsCounter = AtomicInteger(0)
    private val failoverCounter = AtomicInteger(0)

    private val _stats = MutableStateFlow(ApiPoolRuntimeStats())
    val stats: StateFlow<ApiPoolRuntimeStats> = _stats.asStateFlow()

    fun selectOrderedCandidates(
        keys: List<String>,
        strategy: ApiPoolStrategy,
        nowMs: Long = System.currentTimeMillis()
    ): List<String> {
        val cleaned = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.size == 1) return cleaned

        val ready = mutableListOf<String>()
        val cooling = mutableListOf<Pair<String, Long>>()

        for (key in cleaned) {
            val until = cooldownUntilMsByKeyId[keyFingerprint(key)] ?: 0L
            if (until <= nowMs) {
                ready.add(key)
            } else {
                cooling.add(key to until)
            }
        }

        val orderedReady = if (ready.size > 1 && strategy != ApiPoolStrategy.FAILOVER) {
            val start = Math.floorMod(roundRobinCursor.getAndIncrement(), ready.size)
            List(ready.size) { offset -> ready[(start + offset) % ready.size] }
        } else {
            ready
        }

        val orderedCooling = cooling.sortedBy { it.second }.map { it.first }
        return orderedReady + orderedCooling
    }

    fun markRateLimited(apiKey: String, durationMs: Long = 45_000L, nowMs: Long = System.currentTimeMillis()) {
        val fp = keyFingerprint(apiKey)
        cooldownUntilMsByKeyId[fp] = nowMs + durationMs.coerceAtLeast(1_000L)
        publishStats(lastSuffix = null, nowMs = nowMs)
    }

    fun markAuthFailed(apiKey: String, durationMs: Long = 180_000L, nowMs: Long = System.currentTimeMillis()) {
        val fp = keyFingerprint(apiKey)
        cooldownUntilMsByKeyId[fp] = nowMs + durationMs.coerceAtLeast(1_000L)
        publishStats(lastSuffix = null, nowMs = nowMs)
    }

    fun markSuccess(apiKey: String, nowMs: Long = System.currentTimeMillis()) {
        cooldownUntilMsByKeyId.remove(keyFingerprint(apiKey))
        val suffix = apiKey.trim().takeLast(4).takeIf { it.isNotEmpty() }
        publishStats(lastSuffix = suffix, nowMs = nowMs)
    }

    fun isInCooldown(apiKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = cooldownUntilMsByKeyId[keyFingerprint(apiKey)] ?: 0L
        return until > nowMs
    }

    fun clearAll() {
        cooldownUntilMsByKeyId.clear()
        roundRobinCursor.set(0)
        totalRequestsCounter.set(0)
        failoverCounter.set(0)
        _stats.value = ApiPoolRuntimeStats()
    }

    suspend fun <T> executeAcrossPool(
        keys: List<String>,
        strategy: ApiPoolStrategy,
        block: suspend (apiKey: String) -> T
    ): T {
        val candidates = selectOrderedCandidates(keys, strategy)
        require(candidates.isNotEmpty()) { "Aucune clé API disponible dans le pool." }

        var lastError: Exception? = null
        for ((idx, key) in candidates.withIndex()) {
            totalRequestsCounter.incrementAndGet()
            try {
                val result = block(key)
                markSuccess(key)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val shouldFailover = classifyAndPenalize(key, e)
                if (!shouldFailover || idx == candidates.lastIndex) {
                    break
                }
                failoverCounter.incrementAndGet()
                publishStats(lastSuffix = null)
            }
        }
        val finalErr = lastError ?: IllegalStateException("Toutes les clés API du pool ont échoué.")
        throw if (candidates.size > 1) {
            ApiPoolExhaustedException(candidates.size, finalErr)
        } else {
            finalErr
        }
    }

    private fun classifyAndPenalize(apiKey: String, e: Exception): Boolean {
        return when (e) {
            is GeminiRestHttpException -> {
                when {
                    e.statusCode == 429 -> {
                        markRateLimited(apiKey)
                        true
                    }
                    e.statusCode == 400 && (e.apiStatus == "API_KEY_INVALID" || e.message.contains("api key", ignoreCase = true)) -> {
                        markAuthFailed(apiKey)
                        true
                    }
                    e.statusCode == 401 || e.statusCode == 403 -> {
                        markAuthFailed(apiKey)
                        true
                    }
                    e.statusCode == 404 || e.statusCode in 500..599 -> true
                    else -> false
                }
            }
            is TimeoutCancellationException, is IOException -> true
            else -> {
                val msg = (e.message ?: "").lowercase()
                if (msg.contains("429") || msg.contains("quota") || msg.contains("rate limit") || msg.contains("resource exhausted")) {
                    markRateLimited(apiKey)
                    true
                } else {
                    msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") || msg.contains("timeout")
                }
            }
        }
    }

    private fun publishStats(lastSuffix: String?, nowMs: Long = System.currentTimeMillis()) {
        val activeCooldowns = cooldownUntilMsByKeyId.values.count { it > nowMs }
        val prev = _stats.value
        _stats.value = ApiPoolRuntimeStats(
            totalRequests = totalRequestsCounter.get(),
            failoverSwitches = failoverCounter.get(),
            cooldownKeysCount = activeCooldowns,
            lastActiveKeySuffix = lastSuffix ?: prev.lastActiveKeySuffix
        )
    }

    private fun keyFingerprint(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.trim().toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

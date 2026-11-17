package com.example.domain.script

import java.util.Locale

data class LanguageDetectionResult(
    val primaryLanguageCode: String,
    val primaryLabelFr: String,
    val hasMixedLanguages: Boolean,
    val detectedLanguagesFr: List<String>
)

/**
 * Détection légère et hors-ligne de la langue du texte parlé
 * afin de proposer le bon code BCP-47 et d'alerter si plusieurs langues sont mélangées.
 */
object LanguageDetector {

    private val frMarkers = setOf("le", "la", "les", "de", "des", "du", "un", "une", "est", "dans", "pour", "avec", "que", "qui", "sur", "pas", "mais", "nous", "vous", "c'est", "au", "aux")
    private val enMarkers = setOf("the", "and", "is", "are", "of", "to", "in", "that", "it", "for", "with", "this", "you", "not", "was", "have", "from")
    private val esMarkers = setOf("el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "es", "por", "con", "para", "como", "pero", "más")
    private val deMarkers = setOf("der", "die", "das", "und", "ist", "in", "den", "von", "zu", "mit", "ein", "eine", "nicht", "auf", "für", "auch")

    fun detect(rawScript: String): LanguageDetectionResult {
        // Retire les crochets pour n'analyser que le texte parlé
        val spoken = rawScript.replace(Regex("\\[[^\\]]*\\]"), " ").trim()
        if (spoken.length < 15) {
            return LanguageDetectionResult("fr-FR", "Français", false, emptyList())
        }

        val words = spoken.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}']+"))
            .filter { it.length >= 2 }

        if (words.size < 4) {
            return LanguageDetectionResult("fr-FR", "Français", false, emptyList())
        }

        val frScore = words.count { it in frMarkers } + spoken.count { it in "éèêëàâùûôîïçœ" }
        val enScore = words.count { it in enMarkers }
        val esScore = words.count { it in esMarkers } + spoken.count { it in "ñáíóú¿¡" }
        val deScore = words.count { it in deMarkers } + spoken.count { it in "äöüß" }

        val scores = listOf(
            Triple("fr-FR", "Français", frScore),
            Triple("en-US", "Anglais", enScore),
            Triple("es-ES", "Espagnol", esScore),
            Triple("de-DE", "Allemand", deScore)
        ).sortedByDescending { it.third }

        val top = scores.first()
        if (top.third == 0) {
            return LanguageDetectionResult("fr-FR", "Français", false, emptyList())
        }

        val significantSecondaries = scores.drop(1).filter { it.third >= 3 && it.third >= top.third * 0.45f }
        val mixed = significantSecondaries.isNotEmpty()
        val detectedNames = (listOf(top) + significantSecondaries).map { it.second }

        return LanguageDetectionResult(
            primaryLanguageCode = top.first,
            primaryLabelFr = top.second,
            hasMixedLanguages = mixed,
            detectedLanguagesFr = detectedNames
        )
    }
}

package com.example.domain.script

import com.example.domain.model.PronunciationEntry
import java.util.Locale

/**
 * Normalisation intelligente de la prononciation avant envoi au TTS :
 * - Nombres, décimales, pourcentages, devises (€, $, £), heures (14h30), dates (JJ/MM/AAAA)
 * - Abréviations courantes (M., Mme, Dr, etc., km, min)
 * - Dictionnaire de prononciation personnel de l'utilisateur
 */
object PronunciationNormalizer {

    private val frAbbreviations = listOf(
        Regex("\\bM\\.\\s*") to "Monsieur ",
        Regex("\\bMme\\.\\s*") to "Madame ",
        Regex("\\bMlle\\.\\s*") to "Mademoiselle ",
        Regex("\\bDr\\.\\s*") to "Docteur ",
        Regex("\\bPr\\.\\s*") to "Professeur ",
        Regex("\\betc\\.") to "et cætera",
        Regex("\\b(\\d+)\\s*km/h\\b", RegexOption.IGNORE_CASE) to "$1 kilomètres heure",
        Regex("\\b(\\d+)\\s*km\\b", RegexOption.IGNORE_CASE) to "$1 kilomètres",
        Regex("\\b(\\d+)\\s*min\\b", RegexOption.IGNORE_CASE) to "$1 minutes",
        Regex("\\b(\\d+)\\s*sec\\b", RegexOption.IGNORE_CASE) to "$1 secondes"
    )

    private val enAbbreviations = listOf(
        Regex("\\bMr\\.\\s*") to "Mister ",
        Regex("\\bMrs\\.\\s*") to "Missus ",
        Regex("\\bDr\\.\\s*") to "Doctor ",
        Regex("\\betc\\.") to "et cetera",
        Regex("\\b(\\d+)\\s*km\\b", RegexOption.IGNORE_CASE) to "$1 kilometers",
        Regex("\\b(\\d+)\\s*min\\b", RegexOption.IGNORE_CASE) to "$1 minutes"
    )

    fun applyAll(
        text: String,
        languageCode: String,
        normalizeStandardTokens: Boolean,
        customDictionary: List<PronunciationEntry>
    ): String {
        var result = text

        // 1. Dictionnaire personnel (toujours appliqué en priorité sur les mots exacts)
        for (entry in customDictionary) {
            val word = entry.originalWord.trim()
            val replacement = entry.phoneticReplacement.trim()
            if (word.isNotEmpty() && replacement.isNotEmpty()) {
                val pattern = Regex("(?<!\\p{L})${Regex.escape(word)}(?!\\p{L})", RegexOption.IGNORE_CASE)
                result = pattern.replace(result, Regex.escapeReplacement(replacement))
            }
        }

        if (!normalizeStandardTokens) return result

        val isEnglish = languageCode.startsWith("en", ignoreCase = true)

        // 2. Dates JJ/MM/AAAA
        result = Regex("\\b(0?[1-9]|[12]\\d|3[01])/(0?[1-9]|1[0-2])/(\\d{4})\\b").replace(result) { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val month = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            val year = m.groupValues[3].toIntOrNull() ?: return@replace m.value
            if (isEnglish) {
                "${monthNameEn(month)} ${numberToEnglish(day)}, ${numberToEnglish(year)}"
            } else {
                val dayText = if (day == 1) "premier" else numberToFrench(day)
                "$dayText ${monthNameFr(month)} ${numberToFrench(year)}"
            }
        }

        // 3. Heures (14h30, 8h, 14:30)
        result = Regex("\\b([01]?\\d|2[0-3])[hH]([0-5]\\d)?\\b").replace(result) { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val min = m.groupValues[2].toIntOrNull()
            val hourWord = if (h == 1) "une heure" else if (h == 0) "minuit" else "${numberToFrench(h)} heures"
            if (min == null || min == 0) hourWord else "$hourWord ${numberToFrench(min)}"
        }

        // 4. Montants en devises
        result = Regex("(\\d+(?:[.,]\\d{1,2})?)\\s*(€|\\$|£)").replace(result) { m ->
            val numRaw = m.groupValues[1].replace(",", ".")
            val symbol = m.groupValues[2]
            val parts = numRaw.split(".")
            val whole = parts[0].toIntOrNull() ?: return@replace m.value
            val cents = if (parts.size > 1) parts[1].padEnd(2, '0').take(2).toIntOrNull() ?: 0 else 0
            val unitFr = when (symbol) {
                "€" -> if (whole <= 1) "euro" else "euros"
                "$" -> if (whole <= 1) "dollar" else "dollars"
                "£" -> if (whole <= 1) "livre" else "livres"
                else -> ""
            }
            if (isEnglish) {
                val unitEn = when (symbol) {
                    "€" -> if (whole == 1) "euro" else "euros"
                    "$" -> if (whole == 1) "dollar" else "dollars"
                    "£" -> if (whole == 1) "pound" else "pounds"
                    else -> ""
                }
                if (cents > 0) "${numberToEnglish(whole)} $unitEn and ${numberToEnglish(cents)} cents"
                else "${numberToEnglish(whole)} $unitEn"
            } else {
                if (cents > 0) "${numberToFrench(whole)} $unitFr ${numberToFrench(cents)}"
                else "${numberToFrench(whole)} $unitFr"
            }
        }

        // 5. Pourcentages
        result = Regex("(\\d+(?:[.,]\\d+)?)\\s*%").replace(result) { m ->
            val numWord = expandDecimal(m.groupValues[1], isEnglish)
            if (isEnglish) "$numWord percent" else "$numWord pour cent"
        }

        // 6. Abréviations
        val abbrs = if (isEnglish) enAbbreviations else frAbbreviations
        for ((regex, repl) in abbrs) {
            result = regex.replace(result, repl)
        }

        // 7. Nombres restants (jusqu'à 999 999)
        result = Regex("\\b\\d{1,6}(?:[.,]\\d{1,3})?\\b").replace(result) { m ->
            expandDecimal(m.value, isEnglish)
        }

        return result
    }

    private fun expandDecimal(raw: String, isEnglish: Boolean): String {
        val normalized = raw.replace(",", ".")
        val parts = normalized.split(".")
        val intPart = parts[0].toIntOrNull() ?: return raw
        val intText = if (isEnglish) numberToEnglish(intPart) else numberToFrench(intPart)
        if (parts.size == 1) return intText
        val decPart = parts[1].toIntOrNull() ?: return intText
        val decText = if (isEnglish) numberToEnglish(decPart) else numberToFrench(decPart)
        return if (isEnglish) "$intText point $decText" else "$intText virgule $decText"
    }

    private fun monthNameFr(m: Int): String = when (m) {
        1 -> "janvier"; 2 -> "février"; 3 -> "mars"; 4 -> "avril"
        5 -> "mai"; 6 -> "juin"; 7 -> "juillet"; 8 -> "août"
        9 -> "septembre"; 10 -> "octobre"; 11 -> "novembre"; 12 -> "décembre"
        else -> ""
    }

    private fun monthNameEn(m: Int): String = when (m) {
        1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
        5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
        9 -> "September"; 10 -> "October"; 11 -> "November"; 12 -> "December"
        else -> ""
    }

    fun numberToFrench(n: Int): String {
        if (n < 0) return "moins ${numberToFrench(-n)}"
        if (n == 0) return "zéro"
        val units = arrayOf(
            "", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize",
            "dix-sept", "dix-huit", "dix-neuf"
        )
        val tens = arrayOf("", "", "vingt", "trente", "quarante", "cinquante", "soixante")

        fun underHundred(v: Int): String {
            return when {
                v < 20 -> units[v]
                v < 70 -> {
                    val t = v / 10
                    val u = v % 10
                    when (u) {
                        0 -> tens[t]
                        1 -> "${tens[t]} et un"
                        else -> "${tens[t]}-${units[u]}"
                    }
                }
                v < 80 -> {
                    val u = v - 60
                    if (u == 11) "soixante et onze" else "soixante-${units[u]}"
                }
                else -> {
                    val u = v - 80
                    when (u) {
                        0 -> "quatre-vingts"
                        else -> "quatre-vingt-${units[u]}"
                    }
                }
            }
        }

        fun underThousand(v: Int): String {
            if (v < 100) return underHundred(v)
            val h = v / 100
            val rem = v % 100
            val prefix = when {
                h == 1 -> "cent"
                rem == 0 -> "${units[h]} cents"
                else -> "${units[h]} cent"
            }
            return if (rem == 0) prefix else "$prefix ${underHundred(rem)}"
        }

        if (n < 1000) return underThousand(n)
        val thousands = n / 1000
        val rem = n % 1000
        val kPrefix = if (thousands == 1) "mille" else "${underThousand(thousands)} mille"
        return if (rem == 0) kPrefix else "$kPrefix ${underThousand(rem)}"
    }

    fun numberToEnglish(n: Int): String {
        if (n < 0) return "minus ${numberToEnglish(-n)}"
        if (n == 0) return "zero"
        val units = arrayOf(
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
        )
        val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        fun underThousand(v: Int): String {
            return when {
                v < 20 -> units[v]
                v < 100 -> {
                    val t = v / 10
                    val u = v % 10
                    if (u == 0) tens[t] else "${tens[t]}-${units[u]}"
                }
                else -> {
                    val h = v / 100
                    val rem = v % 100
                    if (rem == 0) "${units[h]} hundred" else "${units[h]} hundred ${underThousand(rem)}"
                }
            }
        }

        if (n < 1000) return underThousand(n)
        val k = n / 1000
        val rem = n % 1000
        return if (rem == 0) "${underThousand(k)} thousand" else "${underThousand(k)} thousand ${underThousand(rem)}"
    }
}

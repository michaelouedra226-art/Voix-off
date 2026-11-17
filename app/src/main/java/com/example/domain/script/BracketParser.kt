package com.example.domain.script

import com.example.domain.model.AmbienceCatalog
import com.example.domain.model.AutocompleteContext
import com.example.domain.model.AutocompleteSuggestion
import com.example.domain.model.ContextualToneSuggestion
import com.example.domain.model.DiagnosticIssue
import com.example.domain.model.ParseResult
import com.example.domain.model.ParsedTagSpan
import com.example.domain.model.PresetTones
import com.example.domain.model.SpeechSpeed
import com.example.domain.model.TagType
import com.example.domain.model.TimelineEvent
import com.example.domain.model.VoiceCatalog
import java.util.Locale
import kotlin.math.max

/**
 * Parseur tolérant aux erreurs pour le langage de consignes entre crochets.
 * - Aucune consigne entre crochets n'est jamais envoyée comme texte à lire.
 * - Les consignes (ton, voix, vitesse) persistent jusqu'à leur remplacement.
 * - Tout crochet placé au milieu d'une phrase coupe le texte à cet endroit.
 * - Les segments longs sont découpés aux limites de phrases (900 caractères max).
 */
object BracketParser {

    const val MAX_SEGMENT_CHARS = 900

    fun parse(
        script: String,
        defaultVoice: String = "Kore",
        defaultTone: String = "naturel",
        defaultSpeed: SpeechSpeed = SpeechSpeed.NORMAL,
        customAmbienceNames: Set<String> = emptySet()
    ): ParseResult {
        if (script.isEmpty()) {
            return ParseResult(
                events = emptyList(),
                segments = emptyList(),
                tagSpans = emptyList(),
                diagnostics = emptyList(),
                characterCount = 0,
                spokenCharacterCount = 0,
                wordCount = 0,
                estimatedTotalDurationSec = 0f
            )
        }

        val events = mutableListOf<TimelineEvent>()
        val tagSpans = mutableListOf<ParsedTagSpan>()
        val diagnostics = mutableListOf<DiagnosticIssue>()

        var currentVoice = defaultVoice
        var currentTone = defaultTone
        var currentSpeed = defaultSpeed
        var currentAmbience: String? = null

        var cursor = 0
        var segmentCounter = 0
        var pauseCounter = 0
        var ambienceCounter = 0

        fun emitSpokenText(rawChunk: String, chunkStart: Int) {
            if (rawChunk.isBlank()) return
            val subChunks = splitLongChunkAtSentences(rawChunk, chunkStart, MAX_SEGMENT_CHARS)
            for ((subText, startIdx, endIdx) in subChunks) {
                val trimmed = subText.trim()
                if (trimmed.isEmpty()) continue
                val segId = "seg_${segmentCounter}"
                segmentCounter++
                val estDuration = estimateSpeechDurationSec(trimmed, currentSpeed)
                events.add(
                    TimelineEvent.VoiceSegment(
                        id = segId,
                        index = segmentCounter,
                        text = trimmed,
                        voice = currentVoice,
                        tone = currentTone,
                        speed = currentSpeed,
                        activeAmbience = currentAmbience,
                        sourceStartIndex = startIdx,
                        sourceEndIndex = endIdx,
                        estimatedDurationSec = estDuration
                    )
                )
            }
        }

        while (cursor < script.length) {
            val openIdx = script.indexOf('[', cursor)
            if (openIdx == -1) {
                emitSpokenText(script.substring(cursor), cursor)
                break
            }

            if (openIdx > cursor) {
                emitSpokenText(script.substring(cursor, openIdx), cursor)
            }

            val closeIdx = script.indexOf(']', openIdx + 1)
            val nextOpenIdx = script.indexOf('[', openIdx + 1)

            // Cas d'un crochet non fermé ou imbriqué
            if (closeIdx == -1 || (nextOpenIdx != -1 && nextOpenIdx < closeIdx)) {
                val errorEnd = if (nextOpenIdx != -1) nextOpenIdx else script.length
                val rawErr = script.substring(openIdx, errorEnd)
                tagSpans.add(
                    ParsedTagSpan(
                        startIndex = openIdx,
                        endIndex = errorEnd,
                        type = TagType.ERROR,
                        rawText = rawErr,
                        isMalformed = true
                    )
                )
                diagnostics.add(
                    DiagnosticIssue(
                        startIndex = openIdx,
                        endIndex = errorEnd,
                        rawTag = rawErr.take(40),
                        messageFr = "Crochet non fermé : ajoutez « ] » à la fin de la consigne.",
                        isError = true
                    )
                )
                cursor = errorEnd
                continue
            }

            val inner = script.substring(openIdx + 1, closeIdx)
            val tagEnd = closeIdx + 1
            val rawFullTag = script.substring(openIdx, tagEnd)

            if (inner.isBlank()) {
                tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.ERROR, rawFullTag, isMalformed = true))
                diagnostics.add(
                    DiagnosticIssue(
                        startIndex = openIdx,
                        endIndex = tagEnd,
                        rawTag = rawFullTag,
                        messageFr = "Crochet vide : précisez une consigne, par exemple [ton: chaleureux] ou [pause: 1s].",
                        isError = true
                    )
                )
                cursor = tagEnd
                continue
            }

            val colonIdx = inner.indexOf(':')
            if (colonIdx != -1) {
                val key = inner.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
                val value = inner.substring(colonIdx + 1).trim()

                when (key) {
                    "ton", "tone" -> {
                        if (value.isBlank()) {
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.ERROR, rawFullTag, isMalformed = true))
                            diagnostics.add(
                                DiagnosticIssue(
                                    startIndex = openIdx,
                                    endIndex = tagEnd,
                                    rawTag = rawFullTag,
                                    messageFr = "Consigne [ton:] vide : précisez le style souhaité après les deux-points.",
                                    isError = true
                                )
                            )
                        } else {
                            currentTone = value
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.TONE, rawFullTag))
                        }
                    }

                    "voix", "voice" -> {
                        val matched = VoiceCatalog.findByName(value)
                        if (matched == null) {
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.ERROR, rawFullTag, isMalformed = true))
                            diagnostics.add(
                                DiagnosticIssue(
                                    startIndex = openIdx,
                                    endIndex = tagEnd,
                                    rawTag = rawFullTag,
                                    messageFr = "Voix « $value » inconnue. Choisissez parmi les 14 voix Gemini (ex. Kore, Puck, Charon…).",
                                    isError = true
                                )
                            )
                        } else {
                            currentVoice = matched.name
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.VOICE, rawFullTag))
                        }
                    }

                    "vitesse", "speed", "rythme" -> {
                        val speed = SpeechSpeed.fromToken(value)
                        if (speed == null) {
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.ERROR, rawFullTag, isMalformed = true))
                            diagnostics.add(
                                DiagnosticIssue(
                                    startIndex = openIdx,
                                    endIndex = tagEnd,
                                    rawTag = rawFullTag,
                                    messageFr = "Vitesse « $value » non reconnue : utilisez lent, normal ou rapide.",
                                    isError = true
                                )
                            )
                        } else {
                            currentSpeed = speed
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.SPEED, rawFullTag))
                        }
                    }

                    "pause", "silence" -> {
                        val duration = parsePauseDurationSeconds(value)
                        if (duration == null) {
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.ERROR, rawFullTag, isMalformed = true))
                            diagnostics.add(
                                DiagnosticIssue(
                                    startIndex = openIdx,
                                    endIndex = tagEnd,
                                    rawTag = rawFullTag,
                                    messageFr = "Durée de pause invalide « $value » : indiquez entre 0.2s et 10s (ex. [pause: 1.5s]).",
                                    isError = true
                                )
                            )
                        } else {
                            val clamped = duration.coerceIn(0.2f, 10.0f)
                            if (duration < 0.2f || duration > 10.0f) {
                                diagnostics.add(
                                    DiagnosticIssue(
                                        startIndex = openIdx,
                                        endIndex = tagEnd,
                                        rawTag = rawFullTag,
                                        messageFr = "La pause a été bornée entre 0,2 s et 10 s (valeur appliquée : ${"%.1f".format(Locale.US, clamped)} s).",
                                        isError = false
                                    )
                                )
                            }
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.PAUSE, rawFullTag))
                            events.add(
                                TimelineEvent.PauseEvent(
                                    id = "pause_${pauseCounter++}",
                                    durationSec = clamped,
                                    sourceStartIndex = openIdx
                                )
                            )
                        }
                    }

                    "ambiance", "ambience" -> {
                        val parsedAmbience = parseAmbienceDirective(value, customAmbienceNames)
                        if (parsedAmbience == null) {
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.ERROR, rawFullTag, isMalformed = true))
                            diagnostics.add(
                                DiagnosticIssue(
                                    startIndex = openIdx,
                                    endIndex = tagEnd,
                                    rawTag = rawFullTag,
                                    messageFr = "Consigne ambiance invalide « $value » : utilisez un nom d'ambiance (ex. [ambiance: pluie douce, 25%]) ou [ambiance: stop].",
                                    isError = true
                                )
                            )
                        } else {
                            val (ambName, vol, warningMsg) = parsedAmbience
                            if (warningMsg != null) {
                                diagnostics.add(
                                    DiagnosticIssue(
                                        startIndex = openIdx,
                                        endIndex = tagEnd,
                                        rawTag = rawFullTag,
                                        messageFr = warningMsg,
                                        isError = false
                                    )
                                )
                            }
                            currentAmbience = ambName
                            tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.AMBIENCE, rawFullTag))
                            events.add(
                                TimelineEvent.AmbienceEvent(
                                    id = "amb_${ambienceCounter++}",
                                    ambienceName = ambName,
                                    volume = vol,
                                    sourceStartIndex = openIdx
                                )
                            )
                        }
                    }

                    else -> {
                        // Raccourci : commande inconnue traitée comme consigne de ton libre
                        currentTone = inner.trim()
                        tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.TONE, rawFullTag))
                    }
                }
            } else {
                // Raccourci : [en chuchotant], [avec un grand sourire], etc. -> ton libre
                currentTone = inner.trim()
                tagSpans.add(ParsedTagSpan(openIdx, tagEnd, TagType.TONE, rawFullTag))
            }

            cursor = tagEnd
        }

        val segments = events.filterIsInstance<TimelineEvent.VoiceSegment>()
        val spokenText = segments.joinToString(" ") { it.text }
        val words = if (spokenText.isBlank()) 0 else spokenText.trim().split(Regex("\\s+")).size
        val pauseSum = events.filterIsInstance<TimelineEvent.PauseEvent>().sumOf { it.durationSec.toDouble() }.toFloat()
        val speechSum = segments.sumOf { it.estimatedDurationSec.toDouble() }.toFloat()

        return ParseResult(
            events = events,
            segments = segments,
            tagSpans = tagSpans,
            diagnostics = diagnostics,
            characterCount = script.length,
            spokenCharacterCount = spokenText.length,
            wordCount = words,
            estimatedTotalDurationSec = speechSum + pauseSum
        )
    }

    private fun parsePauseDurationSeconds(raw: String): Float? {
        val cleaned = raw.trim().lowercase(Locale.ROOT)
            .replace(",", ".")
            .removeSuffix("sec")
            .removeSuffix("s")
            .trim()
        val num = cleaned.toFloatOrNull() ?: return null
        if (num.isNaN() || num.isInfinite() || num <= 0f) return null
        return num
    }

    private data class AmbienceParseOutcome(
        val ambienceName: String?,
        val volume: Float,
        val warningFr: String?
    )

    private fun parseAmbienceDirective(
        raw: String,
        customAmbienceNames: Set<String>
    ): AmbienceParseOutcome? {
        if (raw.isBlank()) return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val namePart = parts[0]
        if (namePart.equals("stop", ignoreCase = true) ||
            namePart.equals("aucune", ignoreCase = true) ||
            namePart.equals("off", ignoreCase = true)
        ) {
            return AmbienceParseOutcome(null, 0f, null)
        }

        val preset = AmbienceCatalog.findByName(namePart)
        val customMatch = customAmbienceNames.firstOrNull { it.equals(namePart, ignoreCase = true) }

        var volume = preset?.defaultVolume ?: 0.20f
        var warning: String? = null

        if (parts.size >= 2) {
            val volStr = parts[1].replace("%", "").replace(",", ".").trim()
            val parsedVol = volStr.toFloatOrNull()
            if (parsedVol == null) {
                warning = "Volume « ${parts[1]} » non reconnu : volume par défaut (20 %) utilisé."
            } else {
                val normalized = if (parsedVol > 1.0f) parsedVol / 100f else parsedVol
                if (normalized < 0f || normalized > 1f) {
                    warning = "Le volume doit être compris entre 0 % et 100 %."
                }
                volume = normalized.coerceIn(0.02f, 1.0f)
            }
        }

        if (preset == null && customMatch == null) {
            warning = "Ambiance « $namePart » non trouvée dans les préréglages ni dans vos imports ; synthèse douce de secours ou vérifiez l'orthographe."
        }

        val canonicalName = preset?.name ?: customMatch ?: namePart
        return AmbienceParseOutcome(canonicalName, volume, warning)
    }

    private fun splitLongChunkAtSentences(
        chunk: String,
        chunkStart: Int,
        maxChars: Int
    ): List<Triple<String, Int, Int>> {
        if (chunk.length <= maxChars) {
            return listOf(Triple(chunk, chunkStart, chunkStart + chunk.length))
        }

        val result = mutableListOf<Triple<String, Int, Int>>()
        var localStart = 0

        while (localStart < chunk.length) {
            val remaining = chunk.length - localStart
            if (remaining <= maxChars) {
                result.add(
                    Triple(
                        chunk.substring(localStart),
                        chunkStart + localStart,
                        chunkStart + chunk.length
                    )
                )
                break
            }

            val windowEnd = localStart + maxChars
            // Cherche une fin de phrase (. ! ? … ou retour à la ligne) avant maxChars
            var splitPoint = -1
            for (i in windowEnd downTo (localStart + maxChars / 3)) {
                val ch = chunk[i - 1]
                if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == '\n') {
                    splitPoint = i
                    break
                }
            }
            if (splitPoint == -1) {
                // Sinon, coupe à une virgule ou un espace
                for (i in windowEnd downTo (localStart + maxChars / 2)) {
                    val ch = chunk[i - 1]
                    if (ch == ',' || ch == ';' || ch == ' ') {
                        splitPoint = i
                        break
                    }
                }
            }
            if (splitPoint == -1) {
                splitPoint = windowEnd
            }

            result.add(
                Triple(
                    chunk.substring(localStart, splitPoint),
                    chunkStart + localStart,
                    chunkStart + splitPoint
                )
            )
            localStart = splitPoint
        }

        return result
    }

    private fun estimateSpeechDurationSec(text: String, speed: SpeechSpeed): Float {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        // ~145 mots/min en lecture voix off naturelle (~2.42 mots/sec)
        val baseSec = max(0.6f, words / (2.42f * speed.wpmFactor))
        val punctuationBonus = text.count { it == '.' || it == '!' || it == '?' } * 0.25f +
            text.count { it == ',' || it == ';' } * 0.12f
        return baseSec + punctuationBonus
    }

    /**
     * Détecte si le curseur se trouve dans un crochet en cours de saisie pour l'autocomplétion.
     */
    fun detectAutocomplete(
        script: String,
        cursorPosition: Int,
        recentTones: List<String>,
        customAmbienceNames: List<String>
    ): AutocompleteContext? {
        if (cursorPosition <= 0 || cursorPosition > script.length) return null
        val beforeCursor = script.substring(0, cursorPosition)
        val lastOpen = beforeCursor.lastIndexOf('[')
        val lastClose = beforeCursor.lastIndexOf(']')
        if (lastOpen == -1 || lastOpen < lastClose) return null

        val inside = beforeCursor.substring(lastOpen + 1)
        if (inside.contains('\n')) return null

        val colonIdx = inside.indexOf(':')
        if (colonIdx == -1) {
            val q = inside.trim().lowercase(Locale.ROOT)
            val baseCommands = listOf(
                AutocompleteSuggestion("[ton: ", "[ton: ", TagType.TONE, "Changer l'émotion ou le jeu"),
                AutocompleteSuggestion("[voix: ", "[voix: ", TagType.VOICE, "Changer de narrateur"),
                AutocompleteSuggestion("[pause: ", "[pause: ", TagType.PAUSE, "Silence réel (0.2s à 10s)"),
                AutocompleteSuggestion("[ambiance: ", "[ambiance: ", TagType.AMBIENCE, "Lancer un fond sonore"),
                AutocompleteSuggestion("[ambiance: stop]", "[ambiance: stop]", TagType.AMBIENCE, "Arrêter l'ambiance en fondu"),
                AutocompleteSuggestion("[vitesse: ", "[vitesse: ", TagType.SPEED, "lent, normal ou rapide")
            )
            val filtered = if (q.isEmpty()) baseCommands else baseCommands.filter {
                it.displayLabel.contains(q, ignoreCase = true)
            }
            return AutocompleteContext(
                bracketStartIndex = lastOpen,
                query = inside,
                isInsideCommandValue = false,
                commandKey = null,
                suggestions = filtered
            )
        } else {
            val key = inside.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
            val valQuery = inside.substring(colonIdx + 1).trimStart().lowercase(Locale.ROOT)
            val suggestions = when (key) {
                "voix", "voice" -> VoiceCatalog.voices
                    .filter { valQuery.isEmpty() || it.name.lowercase(Locale.ROOT).contains(valQuery) }
                    .map {
                        AutocompleteSuggestion(
                            displayLabel = it.name,
                            insertText = "[voix: ${it.name}]",
                            category = TagType.VOICE,
                            subtitle = "${it.genderFr} • ${it.timbreFr}"
                        )
                    }

                "ambiance", "ambience" -> {
                    val presetSuggestions = AmbienceCatalog.presets.map {
                        AutocompleteSuggestion(
                            displayLabel = it.name,
                            insertText = "[ambiance: ${it.name}, ${(it.defaultVolume * 100).toInt()}%]",
                            category = TagType.AMBIENCE,
                            subtitle = it.categoryFr
                        )
                    }
                    val customSuggestions = customAmbienceNames.map {
                        AutocompleteSuggestion(
                            displayLabel = it,
                            insertText = "[ambiance: $it, 25%]",
                            category = TagType.AMBIENCE,
                            subtitle = "Audio importé"
                        )
                    }
                    val stopItem = listOf(
                        AutocompleteSuggestion("stop", "[ambiance: stop]", TagType.AMBIENCE, "Fondu de sortie")
                    )
                    (stopItem + customSuggestions + presetSuggestions).filter {
                        valQuery.isEmpty() || it.displayLabel.lowercase(Locale.ROOT).contains(valQuery)
                    }
                }

                "vitesse", "speed", "rythme" -> SpeechSpeed.entries
                    .filter { valQuery.isEmpty() || it.labelFr.contains(valQuery) }
                    .map {
                        AutocompleteSuggestion(
                            displayLabel = it.labelFr,
                            insertText = "[vitesse: ${it.labelFr}]",
                            category = TagType.SPEED,
                            subtitle = it.directiveFr
                        )
                    }

                "pause", "silence" -> listOf("0.5s", "1s", "1.5s", "2s", "3s")
                    .filter { valQuery.isEmpty() || it.contains(valQuery) }
                    .map {
                        AutocompleteSuggestion(
                            displayLabel = it,
                            insertText = "[pause: $it]",
                            category = TagType.PAUSE,
                            subtitle = "Silence réel"
                        )
                    }

                "ton", "tone" -> (recentTones + PresetTones.tones)
                    .distinct()
                    .filter { valQuery.isEmpty() || it.lowercase(Locale.ROOT).contains(valQuery) }
                    .map {
                        AutocompleteSuggestion(
                            displayLabel = it,
                            insertText = "[ton: $it]",
                            category = TagType.TONE,
                            subtitle = if (it in recentTones) "Récent" else "Préréglage"
                        )
                    }

                else -> emptyList()
            }

            return AutocompleteContext(
                bracketStartIndex = lastOpen,
                query = valQuery,
                isInsideCommandValue = true,
                commandKey = key,
                suggestions = suggestions
            )
        }
    }

    /**
     * Suggestion contextuelle non intrusive lorsqu'un passage change nettement d'émotion
     * sans consigne [ton:] à proximité.
     */
    fun findContextualToneSuggestion(script: String): ContextualToneSuggestion? {
        if (script.length < 30) return null
        val sentences = Regex("[^.!?\\n]+[.!?…]+").findAll(script).toList()
        if (sentences.size < 2) return null

        for (match in sentences.drop(1)) {
            val rawSentence = match.value.trim()
            if (rawSentence.startsWith("[") || rawSentence.length < 15) continue
            val startIdx = match.range.first
            val contextBefore = script.substring(maxOf(0, startIdx - 45), startIdx)
            if (contextBefore.contains("[ton:", ignoreCase = true) || contextBefore.contains("]")) {
                continue
            }

            val lower = rawSentence.lowercase(Locale.ROOT)
            when {
                rawSentence.contains("?") && !rawSentence.contains("!") -> {
                    return ContextualToneSuggestion(
                        insertIndex = startIdx,
                        excerpt = rawSentence.take(55),
                        suggestedTone = "curieux et interrogatif",
                        reasonFr = "Passage interrogatif détecté sans balise de ton"
                    )
                }

                rawSentence.count { it == '!' } >= 1 &&
                    (lower.contains("enfin") || lower.contains("incroyable") || lower.contains("découvr")) -> {
                    return ContextualToneSuggestion(
                        insertIndex = startIdx,
                        excerpt = rawSentence.take(55),
                        suggestedTone = "enthousiaste et lumineux",
                        reasonFr = "Montée d'énergie exclamative détectée"
                    )
                }

                rawSentence.contains("...") || rawSentence.contains("…") -> {
                    return ContextualToneSuggestion(
                        insertIndex = startIdx,
                        excerpt = rawSentence.take(55),
                        suggestedTone = "intrigant, léger suspense",
                        reasonFr = "Effet de suspension détecté"
                    )
                }

                lower.startsWith("en conclusion") || lower.startsWith("pour finir") || lower.startsWith("alors") -> {
                    return ContextualToneSuggestion(
                        insertIndex = startIdx,
                        excerpt = rawSentence.take(55),
                        suggestedTone = "posé et conclusif",
                        reasonFr = "Transition de conclusion détectée"
                    )
                }
            }
        }
        return null
    }
}

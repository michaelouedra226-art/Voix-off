package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.audio.AudioExportFormat
import com.example.audio.PlaybackState
import com.example.domain.model.MixConfig
import com.example.domain.model.ParsedTagSpan
import com.example.domain.model.PresetTones
import com.example.domain.model.SegmentRuntimeState
import com.example.domain.model.SegmentStatus
import com.example.domain.model.TagType
import com.example.domain.model.TimelineEvent
import com.example.domain.model.VoiceCatalog
import com.example.domain.script.BracketParser
import com.example.ui.components.EmptyStudioBadge
import com.example.ui.components.FinalPlayerCard
import com.example.ui.components.LiveGenerationStatusCard
import com.example.ui.components.SegmentTimelineCard
import com.example.ui.components.SyntaxHelpCollapsibleCard
import com.example.ui.components.VoxaPrimaryActionButton
import com.example.ui.components.formatTime
import com.example.ui.theme.StudioError
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.StudioWarning
import com.example.ui.theme.TagAmbienceTurquoise
import com.example.ui.theme.TagErrorRed
import com.example.ui.theme.TagPauseSlate
import com.example.ui.theme.TagSpeedGold
import com.example.ui.theme.TagToneOrange
import com.example.ui.theme.TagVoiceViolet
import com.example.viewmodel.GenerationPhase
import com.example.viewmodel.StudioUiState
import java.util.Locale

@Composable
fun ExpertModeScreen(
    state: StudioUiState,
    playback: PlaybackState,
    onScriptChange: (String, Int) -> Unit,
    onCursorChange: (Int) -> Unit,
    onInsertTag: (String, Int?) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onAcceptSuggestion: () -> Unit,
    onDismissSuggestion: () -> Unit,
    onAuditionPassage: (String) -> Unit,
    onGenerateClick: (Boolean) -> Unit,
    onCancelGeneration: () -> Unit,
    onPlaySegmentSolo: (String) -> Unit,
    onRetrySegment: (String, Boolean) -> Unit,
    onSelectTake: (String, Int) -> Unit,
    onTogglePlayFinal: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onSeekDelta: (Float) -> Unit,
    onToggleVoiceOnly: (Boolean) -> Unit,
    onUpdateMixConfig: ((MixConfig) -> MixConfig) -> Unit,
    onExport: (AudioExportFormat, Boolean) -> Unit,
    onShare: (AudioExportFormat, Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var selectionRange by remember { mutableStateOf(TextRange(state.cursorPosition)) }
    var showVoiceDropdown by remember { mutableStateOf(false) }
    var showTonePresetMenu by remember { mutableStateOf(false) }

    val textFieldValue = remember(state.expertScript, selectionRange) {
        val safeStart = selectionRange.start.coerceIn(0, state.expertScript.length)
        val safeEnd = selectionRange.end.coerceIn(0, state.expertScript.length)
        TextFieldValue(state.expertScript, TextRange(safeStart, safeEnd))
    }

    val bracketVisualTransformation = remember(state.parseResult.tagSpans) {
        BracketSyntaxTransformation(state.parseResult.tagSpans)
    }

    val autocompleteContext = remember(
        state.expertScript,
        state.cursorPosition,
        state.recentTones,
        state.customAmbiences
    ) {
        BracketParser.detectAutocomplete(
            script = state.expertScript,
            cursorPosition = state.cursorPosition,
            recentTones = state.recentTones,
            customAmbienceNames = state.customAmbiences.map { it.name }
        )
    }

    val isGenerating = state.generationPhase != GenerationPhase.IDLE && state.generationPhase != GenerationPhase.DONE
    val cachedCount = state.parseResult.segments.count { seg ->
        state.segmentStates[seg.id]?.status == SegmentStatus.READY
    }
    val apiCallsNeeded = (state.parseResult.segments.size - cachedCount).coerceAtLeast(0)
    val hasErroredSegments = state.segmentStates.values.any { it.status == SegmentStatus.ERROR }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Barre d'outils supérieure de l'éditeur (Voix par défaut, Annuler/Rétablir, Copier, Écouter sélection)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box {
                    AssistChip(
                        onClick = { showVoiceDropdown = true },
                        label = { Text("Voix : ${state.selectedVoice}") },
                        modifier = Modifier.testTag("expert_voice_selector")
                    )
                    DropdownMenu(
                        expanded = showVoiceDropdown,
                        onDismissRequest = { showVoiceDropdown = false }
                    ) {
                        VoiceCatalog.voices.forEach { v ->
                            DropdownMenuItem(
                                text = { Text("${v.name} — ${v.genderFr} (${v.timbreFr})") },
                                onClick = {
                                    onSelectVoice(v.name)
                                    showVoiceDropdown = false
                                }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onUndo,
                        enabled = state.canUndo,
                        modifier = Modifier.size(44.dp).testTag("editor_undo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Annuler la saisie")
                    }
                    IconButton(
                        onClick = onRedo,
                        enabled = state.canRedo,
                        modifier = Modifier.size(44.dp).testTag("editor_redo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Rétablir la saisie")
                    }
                    IconButton(
                        onClick = {
                            if (state.expertScript.isNotBlank()) {
                                clipboard.setText(AnnotatedString(state.expertScript))
                                onShowMessage("Script annoté copié dans le presse-papiers.")
                            }
                        },
                        modifier = Modifier.size(44.dp).testTag("editor_copy_button")
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copier le script annoté")
                    }
                    if (!selectionRange.collapsed) {
                        val selectedSub = state.expertScript.substring(
                            selectionRange.min.coerceIn(0, state.expertScript.length),
                            selectionRange.max.coerceIn(0, state.expertScript.length)
                        )
                        IconButton(
                            onClick = { onAuditionPassage(selectedSub) },
                            modifier = Modifier.size(44.dp).testTag("audition_selection_button")
                        ) {
                            Icon(
                                Icons.Rounded.Hearing,
                                contentDescription = "Écouter le passage sélectionné",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // Autocomplétion dès que l'utilisateur tape "["
        AnimatedVisibility(visible = autocompleteContext != null && autocompleteContext.suggestions.isNotEmpty()) {
            autocompleteContext?.let { ctx ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = if (ctx.isInsideCommandValue) "Valeurs suggérées :" else "Commandes disponibles :",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            ctx.suggestions.forEach { sug ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        onInsertTag(sug.insertText, ctx.bracketStartIndex)
                                        selectionRange = TextRange((ctx.bracketStartIndex + sug.insertText.length))
                                    },
                                    label = {
                                        Column {
                                            Text(sug.displayLabel, fontWeight = FontWeight.SemiBold)
                                            sug.subtitle?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Éditeur de texte avec coloration syntaxique des consignes entre crochets
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { updated ->
                selectionRange = updated.selection
                if (updated.text != state.expertScript) {
                    onScriptChange(updated.text, updated.selection.end)
                } else {
                    onCursorChange(updated.selection.end)
                }
            },
            visualTransformation = bracketVisualTransformation,
            placeholder = {
                Text(
                    "Écrivez votre texte ici et placez des consignes entre crochets : [voix: ...], [ton: ...], [pause: 1s], [ambiance: ...].",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            minLines = 6,
            maxLines = 14,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("expert_script_editor")
        )

        // Barre de tons prédéfinis (sur demande via bouton [ton] ou rapide)
        AnimatedVisibility(visible = showTonePresetMenu) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    PresetTones.tones.forEach { tone ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onInsertTag("[ton: $tone]", null)
                                showTonePresetMenu = false
                            },
                            label = { Text(tone) }
                        )
                    }
                }
            }
        }

        // Barre d'insertion rapide au-dessus du clavier
        QuickInsertToolbar(
            onToneClick = { showTonePresetMenu = !showTonePresetMenu },
            onInsertTag = { tag -> onInsertTag(tag, null) }
        )

        // Compteurs en direct + Détection de langue
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${state.parseResult.characterCount} car. • ${state.parseResult.wordCount} mots",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "~${formatTime(state.parseResult.estimatedTotalDurationSec)} • ${state.parseResult.segments.size} seg.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Appels API prévus : $apiCallsNeeded (${cachedCount} en cache)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                state.languageDetection?.let { det ->
                    if (det.hasMixedLanguages) {
                        Text(
                            text = "Langues mélangées détectées (${det.detectedLanguagesFr.joinToString(", ")}) — passez la langue sur « Auto » pour laisser Gemini s'adapter automatiquement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioWarning
                        )
                    } else if (state.expertScript.length > 20) {
                        Text(
                            text = "Langue détectée : ${det.primaryLabelFr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Suggestion contextuelle non intrusive
        AnimatedVisibility(visible = state.contextualSuggestion != null) {
            state.contextualSuggestion?.let { sug ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TagToneOrange.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, TagToneOrange.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = TagToneOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ajouter un changement de ton ici ?",
                                style = MaterialTheme.typography.labelMedium,
                                color = TagToneOrange
                            )
                            Text(
                                text = "[ton: ${sug.suggestedTone}] • ${sug.reasonFr}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = onAcceptSuggestion) { Text("Ajouter") }
                        TextButton(onClick = onDismissSuggestion) { Text("Ignorer") }
                    }
                }
            }
        }

        // Diagnostics des crochets mal formés (signalés en orange/rouge sans jamais planter)
        if (state.parseResult.diagnostics.isNotEmpty()) {
            state.parseResult.diagnostics.forEach { diag ->
                val diagColor = if (diag.isError) TagErrorRed else TagToneOrange
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = diagColor.copy(alpha = 0.13f),
                    border = BorderStroke(1.dp, diagColor.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = diagColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${diag.rawTag} — ${diag.messageFr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Bouton Générer / Reprendre
        if (isGenerating) {
            LiveGenerationStatusCard(
                phase = state.generationPhase,
                currentSegment = state.currentSegmentIndex,
                totalSegments = state.totalSegmentsCount,
                onCancel = onCancelGeneration
            )
        } else {
            val canGenerate = state.parseResult.segments.isNotEmpty()
            VoxaPrimaryActionButton(
                text = if (hasErroredSegments) "Reprendre la génération" else "Générer la voix off",
                isEnabled = canGenerate,
                onDisabledTap = {
                    onShowMessage("Écrivez au moins une phrase dans l'éditeur avant de lancer la génération.")
                },
                onClick = { onGenerateClick(hasErroredSegments) },
                testTag = "expert_generate_button"
            )
        }

        // Lecteur final
        if (state.hasFinalAudio && state.audioMetrics != null) {
            FinalPlayerCard(
                metrics = state.audioMetrics,
                playback = playback,
                mixConfig = state.mixConfig,
                isVoiceOnlyPreview = state.isVoiceOnlyPreview,
                onTogglePlay = onTogglePlayFinal,
                onSeekTo = onSeekTo,
                onSeekDelta = onSeekDelta,
                onToggleVoiceOnly = onToggleVoiceOnly,
                onUpdateMixConfig = onUpdateMixConfig,
                onExport = onExport,
                onShare = onShare
            )
        }

        // Chronologie des segments
        Text(
            text = "Chronologie (${state.parseResult.segments.size} segments)",
            style = MaterialTheme.typography.titleLarge
        )

        if (state.parseResult.segments.isEmpty()) {
            EmptyStudioBadge(
                title = "Chronologie vide",
                subtitle = "Les segments apparaîtront ici automatiquement dès que vous saisirez du texte."
            )
        } else {
            val activeTimings = state.audioMetrics?.segmentTimings.orEmpty()
            val currentPlaySec = if (playback.activeTag == "final" && playback.isPlaying) playback.currentSec else -1f

            state.parseResult.segments.forEach { seg ->
                val runtime = state.segmentStates[seg.id]
                val timing = activeTimings.firstOrNull { it.segmentId == seg.id }
                val isHighlighted = timing != null && currentPlaySec >= timing.startSec && currentPlaySec <= timing.endSec

                SegmentTimelineCard(
                    segment = seg,
                    runtime = runtime,
                    isPlayingSolo = playback.activeTag == "segment:${seg.id}" && playback.isPlaying,
                    isCurrentlyActiveInFinal = isHighlighted,
                    onPlaySolo = { onPlaySegmentSolo(seg.id) },
                    onRegenerate = { onRetrySegment(seg.id, false) },
                    onAnotherTake = { onRetrySegment(seg.id, true) },
                    onSelectTake = { takeIdx -> onSelectTake(seg.id, takeIdx) }
                )
            }
        }

        SyntaxHelpCollapsibleCard()
    }
}

@Composable
fun QuickInsertToolbar(
    onToneClick: () -> Unit,
    onInsertTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        AssistChip(
            onClick = onToneClick,
            label = { Text("[ton]", color = TagToneOrange, fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.testTag("quick_insert_ton")
        )
        AssistChip(
            onClick = { onInsertTag("[ambiance: ") },
            label = { Text("[ambiance]", color = TagAmbienceTurquoise, fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.testTag("quick_insert_ambiance")
        )
        AssistChip(
            onClick = { onInsertTag("[pause: 1s]") },
            label = { Text("[pause]", color = TagPauseSlate, fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.testTag("quick_insert_pause")
        )
        AssistChip(
            onClick = { onInsertTag("[voix: ") },
            label = { Text("[voix]", color = TagVoiceViolet, fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.testTag("quick_insert_voix")
        )
        AssistChip(
            onClick = { onInsertTag("[vitesse: normal]") },
            label = { Text("[vitesse]", color = TagSpeedGold, fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.testTag("quick_insert_vitesse")
        )
    }
}

private class BracketSyntaxTransformation(
    private val spans: List<ParsedTagSpan>
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text.text)
        for (span in spans) {
            val start = span.startIndex.coerceIn(0, text.text.length)
            val end = span.endIndex.coerceIn(start, text.text.length)
            if (start >= end) continue
            val color: Color = when (span.type) {
                TagType.TONE -> TagToneOrange
                TagType.AMBIENCE -> TagAmbienceTurquoise
                TagType.PAUSE -> TagPauseSlate
                TagType.VOICE -> TagVoiceViolet
                TagType.SPEED -> TagSpeedGold
                TagType.ERROR -> TagErrorRed
            }
            builder.addStyle(
                style = SpanStyle(
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (span.isMalformed) TextDecoration.Underline else TextDecoration.None
                ),
                start = start,
                end = end
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

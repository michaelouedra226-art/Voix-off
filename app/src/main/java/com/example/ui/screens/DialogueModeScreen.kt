package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audio.AudioExportFormat
import com.example.audio.PlaybackState
import com.example.domain.model.AmbienceCatalog
import com.example.domain.model.DialogueCompiler
import com.example.domain.model.DialogueLine
import com.example.domain.model.MixConfig
import com.example.domain.model.PresetTones
import com.example.domain.model.SegmentStatus
import com.example.domain.model.SpeechSpeed
import com.example.domain.model.VoiceCatalog
import com.example.ui.components.FinalPlayerCard
import com.example.ui.components.LiveGenerationStatusCard
import com.example.ui.components.SegmentTimelineCard
import com.example.ui.components.VoxaPrimaryActionButton
import com.example.ui.theme.StudioError
import com.example.ui.theme.TagAmbienceTurquoise
import com.example.ui.theme.TagToneOrange
import com.example.ui.theme.TagVoiceViolet
import com.example.viewmodel.GenerationPhase
import com.example.viewmodel.StudioUiState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DialogueModeScreen(
    state: StudioUiState,
    playback: PlaybackState,
    onAddLine: () -> Unit,
    onUpdateLine: (String, (DialogueLine) -> DialogueLine) -> Unit,
    onRemoveLine: (String) -> Unit,
    onMoveLine: (Int, Int) -> Unit,
    onSetAmbience: (String?, Int?) -> Unit,
    onGenerateDialogue: () -> Unit,
    onOpenInExpert: () -> Unit,
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
    onExportSrt: () -> Unit,
    onShare: (AudioExportFormat, Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAmbienceMenu by remember { mutableStateOf(false) }
    var showCompiledPreview by remember { mutableStateOf(false) }
    val isGenerating = state.generationPhase != GenerationPhase.IDLE && state.generationPhase != GenerationPhase.DONE

    val compiledPreview = remember(
        state.dialogueLines,
        state.dialogueAmbienceName,
        state.dialogueAmbienceVolumePercent
    ) {
        DialogueCompiler.compileScript(
            lines = state.dialogueLines,
            ambienceName = state.dialogueAmbienceName,
            ambienceVolumePercent = state.dialogueAmbienceVolumePercent
        )
    }

    val allAmbienceChoices = remember(state.customAmbiences) {
        AmbienceCatalog.presets.map { it.name } + state.customAmbiences.map { it.name }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("dialogue_mode_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // En-tête + Ambiance de scène
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Dialogue multi-voix assisté",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Attribuez une voix, un ton et une pause à chaque intervenant. Voxa Studio assemble automatiquement les répliques avec fondu et ducking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box {
                        AssistChip(
                            onClick = { showAmbienceMenu = true },
                            label = {
                                Text(
                                    text = "Ambiance : ${state.dialogueAmbienceName ?: "Aucune"}",
                                    color = if (state.dialogueAmbienceName != null) TagAmbienceTurquoise else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            modifier = Modifier.testTag("dialogue_ambience_chip")
                        )
                        DropdownMenu(
                            expanded = showAmbienceMenu,
                            onDismissRequest = { showAmbienceMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Aucune ambiance") },
                                onClick = {
                                    onSetAmbience(null, null)
                                    showAmbienceMenu = false
                                }
                            )
                            allAmbienceChoices.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onSetAmbience(name, null)
                                        showAmbienceMenu = false
                                    }
                                )
                            }
                        }
                    }

                    if (state.dialogueAmbienceName != null) {
                        Text(
                            text = "${state.dialogueAmbienceVolumePercent} %",
                            style = MaterialTheme.typography.labelMedium,
                            color = TagAmbienceTurquoise
                        )
                    }
                }

                if (state.dialogueAmbienceName != null) {
                    Slider(
                        value = state.dialogueAmbienceVolumePercent.toFloat(),
                        onValueChange = { onSetAmbience(state.dialogueAmbienceName, it.roundToInt()) },
                        valueRange = 5f..60f
                    )
                }
            }
        }

        // Liste des répliques
        state.dialogueLines.forEachIndexed { index, line ->
            DialogueLineCard(
                index = index,
                totalCount = state.dialogueLines.size,
                line = line,
                onUpdate = { transform -> onUpdateLine(line.id, transform) },
                onMoveUp = { onMoveLine(index, -1) },
                onMoveDown = { onMoveLine(index, 1) },
                onDelete = { onRemoveLine(line.id) }
            )
        }

        OutlinedButton(
            onClick = onAddLine,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("dialogue_add_line_button")
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Ajouter une réplique")
        }

        // Aperçu du script compilé
        if (compiledPreview.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Script compilé (${state.dialogueLines.count { it.text.isNotBlank() }} répliques)",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        TextButton(onClick = { showCompiledPreview = !showCompiledPreview }) {
                            Icon(
                                imageVector = if (showCompiledPreview) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showCompiledPreview) "Masquer" else "Aperçu")
                        }
                    }
                    AnimatedVisibility(visible = showCompiledPreview) {
                        Text(
                            text = compiledPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        // Actions principales
        if (isGenerating) {
            LiveGenerationStatusCard(
                phase = state.generationPhase,
                currentSegment = state.currentSegmentIndex,
                totalSegments = state.totalSegmentsCount,
                onCancel = onCancelGeneration
            )
        } else {
            val hasSpokenLines = state.dialogueLines.any { it.text.isNotBlank() }
            VoxaPrimaryActionButton(
                text = "Générer le dialogue",
                isEnabled = hasSpokenLines,
                onDisabledTap = {
                    onShowMessage("Saisissez au moins une réplique avant de lancer la génération.")
                },
                onClick = onGenerateDialogue,
                testTag = "dialogue_generate_button"
            )
            OutlinedButton(
                onClick = onOpenInExpert,
                enabled = hasSpokenLines,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("dialogue_open_expert_button")
            ) {
                Text("Affiner en Mode Expert")
            }
        }

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
                onExportSrt = onExportSrt,
                onShare = onShare
            )
        }

        if (state.parseResult.segments.isNotEmpty() &&
            (isGenerating || state.segmentStates.values.any { it.status == SegmentStatus.READY || it.status == SegmentStatus.ERROR })
        ) {
            Text(
                text = "Répliques générées (${state.parseResult.segments.size})",
                style = MaterialTheme.typography.titleMedium
            )
            state.parseResult.segments.forEach { seg ->
                val runtime = state.segmentStates[seg.id]
                SegmentTimelineCard(
                    segment = seg,
                    runtime = runtime,
                    isPlayingSolo = playback.activeTag == "segment:${seg.id}" && playback.isPlaying,
                    isCurrentlyActiveInFinal = false,
                    onPlaySolo = { onPlaySegmentSolo(seg.id) },
                    onRegenerate = { onRetrySegment(seg.id, false) },
                    onAnotherTake = { onRetrySegment(seg.id, true) },
                    onSelectTake = { takeIdx -> onSelectTake(seg.id, takeIdx) }
                )
            }
        }
    }
}

@Composable
private fun DialogueLineCard(
    index: Int,
    totalCount: Int,
    line: DialogueLine,
    onUpdate: ((DialogueLine) -> DialogueLine) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showToneMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dialogue_line_card_$index")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Réplique #${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Monter la réplique")
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = index < totalCount - 1,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Descendre la réplique")
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Supprimer cette réplique",
                            tint = StudioError
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Box {
                    AssistChip(
                        onClick = { showVoiceMenu = true },
                        label = { Text(line.voiceName, color = TagVoiceViolet, fontWeight = FontWeight.SemiBold) }
                    )
                    DropdownMenu(
                        expanded = showVoiceMenu,
                        onDismissRequest = { showVoiceMenu = false }
                    ) {
                        VoiceCatalog.voices.forEach { v ->
                            DropdownMenuItem(
                                text = { Text("${v.name} — ${v.genderFr} (${v.timbreFr})") },
                                onClick = {
                                    onUpdate { it.copy(voiceName = v.name) }
                                    showVoiceMenu = false
                                }
                            )
                        }
                    }
                }

                Box {
                    AssistChip(
                        onClick = { showToneMenu = true },
                        label = { Text("Ton : ${line.tone}", color = TagToneOrange) }
                    )
                    DropdownMenu(
                        expanded = showToneMenu,
                        onDismissRequest = { showToneMenu = false }
                    ) {
                        PresetTones.tones.forEach { tone ->
                            DropdownMenuItem(
                                text = { Text(tone) },
                                onClick = {
                                    onUpdate { it.copy(tone = tone) }
                                    showToneMenu = false
                                }
                            )
                        }
                    }
                }

                SpeechSpeed.entries.forEach { sp ->
                    FilterChip(
                        selected = line.speed == sp,
                        onClick = { onUpdate { it.copy(speed = sp) } },
                        label = { Text(sp.labelFr) }
                    )
                }
            }

            OutlinedTextField(
                value = line.text,
                onValueChange = { txt -> onUpdate { it.copy(text = txt) } },
                placeholder = { Text("Saisissez la réplique de ${line.voiceName}…") },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dialogue_line_input_$index")
            )

            if (index > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Pause avant cette réplique : ${"%.1f".format(Locale.US, line.pauseBeforeSec)} s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = line.pauseBeforeSec,
                    onValueChange = { p -> onUpdate { it.copy(pauseBeforeSec = p) } },
                    valueRange = 0f..2.5f
                )
            }
        }
    }
}

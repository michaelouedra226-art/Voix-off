package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.audio.AudioExportFormat
import com.example.audio.PlaybackState
import com.example.domain.model.BriefPlan
import com.example.domain.model.GenerationRecord
import com.example.domain.model.LanguageOption
import com.example.domain.model.MixConfig
import com.example.domain.model.ScriptVersion
import com.example.domain.model.SegmentStatus
import com.example.domain.model.TargetDurationOption
import com.example.domain.model.VoiceGenderPreference
import com.example.ui.components.EmptyStudioBadge
import com.example.ui.components.FinalPlayerCard
import com.example.ui.components.LiveGenerationStatusCard
import com.example.ui.components.SegmentTimelineCard
import com.example.ui.components.SkeletonBriefPlanCard
import com.example.ui.components.SyntaxHelpCollapsibleCard
import com.example.ui.components.VoxaPrimaryActionButton
import com.example.ui.components.formatTime
import com.example.ui.theme.StudioError
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.TagAmbienceTurquoise
import com.example.ui.theme.TagToneOrange
import com.example.ui.theme.TagVoiceViolet
import com.example.viewmodel.GenerationPhase
import com.example.viewmodel.StudioUiState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun BriefModeScreen(
    state: StudioUiState,
    playback: PlaybackState,
    onUpdateDescription: (String) -> Unit,
    onUpdateRawText: (String) -> Unit,
    onSelectDuration: (TargetDurationOption) -> Unit,
    onSelectLanguage: (LanguageOption) -> Unit,
    onSelectGender: (VoiceGenderPreference) -> Unit,
    onGenerateClick: () -> Unit,
    onPreviewPlanClick: () -> Unit,
    onCancelGeneration: () -> Unit,
    onResumeErroredGeneration: () -> Unit,
    onGenerateFromPlan: () -> Unit,
    onEditPlanInExpert: () -> Unit,
    onUpdateRefinePrompt: (String) -> Unit,
    onApplyRefinement: () -> Unit,
    onRestoreVersion: (ScriptVersion) -> Unit,
    onPlaySegmentSolo: (String) -> Unit,
    onRetrySegment: (String, Boolean) -> Unit,
    onSelectTake: (String, Int) -> Unit,
    onSelectAndPlayGeneration: (GenerationRecord) -> Unit,
    onOpenGenerationInExpert: (GenerationRecord) -> Unit,
    onDeleteGeneration: (GenerationRecord) -> Unit,
    onClearError: () -> Unit,
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val generationsRequester = remember { BringIntoViewRequester() }

    var isDictating by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var showSegmentsList by remember { mutableStateOf(true) }

    val isGenerating = state.generationPhase != GenerationPhase.IDLE && state.generationPhase != GenerationPhase.DONE
    val hasErroredSegments = state.segmentStates.values.any { it.status == SegmentStatus.ERROR }

    // Défilement automatique vers la zone Générations dès le lancement ou la fin d'une génération
    LaunchedEffect(state.isPlanningBrief, state.generationPhase, state.briefPlan) {
        if (state.isPlanningBrief ||
            state.generationPhase == GenerationPhase.ANALYZING ||
            state.generationPhase == GenerationPhase.DONE ||
            state.briefPlan != null
        ) {
            runCatching { generationsRequester.bringIntoView() }
        }
    }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }

    DisposableEffect(speechRecognizer) {
        onDispose {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    fun startDictation() {
        if (speechRecognizer == null) {
            onShowMessage("La reconnaissance vocale n'est pas disponible sur cet appareil.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isDictating = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isDictating = false }
            override fun onError(error: Int) {
                isDictating = false
                if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                    onShowMessage("Dictée vocale interrompue (code $error). Réessayez dans un endroit calme.")
                }
            }
            override fun onResults(results: Bundle?) {
                isDictating = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    val existing = state.briefDescription.trim()
                    val combined = if (existing.isEmpty()) spoken else "$existing $spoken"
                    onUpdateDescription(combined)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startDictation()
        } else {
            onShowMessage("Permission micro refusée. Autorisez l'accès au microphone pour dicter votre brief.")
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Raccourci rapide vers les générations existantes si l'écran est petit
        if (state.generations.isNotEmpty() || state.hasFinalAudio || isGenerating) {
            Surface(
                onClick = {
                    coroutineScope.launch {
                        runCatching { generationsRequester.bringIntoView() }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("brief_jump_to_generations")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                isGenerating -> "Génération en cours…"
                                state.generations.isNotEmpty() -> "Voir vos générations (${state.generations.size})"
                                else -> "Voir l'audio généré"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 1. Champ Description + Bouton Micro
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Décris ce que tu veux",
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(
                        onClick = {
                            if (isDictating) {
                                speechRecognizer?.stopListening()
                                isDictating = false
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) startDictation()
                                else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("brief_mic_button")
                    ) {
                        Icon(
                            imageVector = if (isDictating) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = if (isDictating) "Arrêter la dictée vocale" else "Dicter à la voix",
                            tint = if (isDictating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (isDictating) {
                    Text(
                        text = "Écoute en cours (fr-FR)… parlez distinctement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = state.briefDescription,
                    onValueChange = onUpdateDescription,
                    placeholder = {
                        Text(
                            "Indiquez l'intention, le style de voix, l'ambiance sonore ou la progression souhaitée…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("brief_description_input")
                )

                Text(
                    text = "Ton texte (optionnel)",
                    style = MaterialTheme.typography.titleSmall
                )

                OutlinedTextField(
                    value = state.briefRawText,
                    onValueChange = onUpdateRawText,
                    placeholder = {
                        Text(
                            "Collez votre texte tel quel. S'il reste vide, le script sera rédigé à partir de votre description.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("brief_raw_text_input")
                )

                Text(
                    text = "Durée cible",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    TargetDurationOption.entries.forEach { opt ->
                        FilterChip(
                            selected = state.targetDuration == opt,
                            onClick = { onSelectDuration(opt) },
                            label = { Text(opt.labelFr) }
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box {
                        AssistChip(
                            onClick = { showLanguageMenu = true },
                            label = { Text("Langue : ${state.selectedLanguage.labelFr}") },
                            modifier = Modifier.testTag("brief_language_chip")
                        )
                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false }
                        ) {
                            LanguageOption.entries.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.labelFr) },
                                    onClick = {
                                        onSelectLanguage(lang)
                                        showLanguageMenu = false
                                    }
                                )
                            }
                        }
                    }

                    VoiceGenderPreference.entries.forEach { pref ->
                        FilterChip(
                            selected = state.genderPreference == pref,
                            onClick = { onSelectGender(pref) },
                            label = { Text(pref.labelFr) }
                        )
                    }
                }
            }
        }

        // 2. Actions principales / Reprise / État vivant
        if (!isGenerating && !state.isPlanningBrief) {
            val canTrigger = state.briefDescription.isNotBlank() || state.briefRawText.isNotBlank()
            VoxaPrimaryActionButton(
                text = if (hasErroredSegments) "Reprendre la génération" else "Générer l'audio",
                isEnabled = canTrigger || hasErroredSegments,
                onDisabledTap = {
                    onShowMessage("Saisissez d'abord une description ou collez votre texte pour lancer la génération.")
                },
                onClick = {
                    if (hasErroredSegments) {
                        onResumeErroredGeneration()
                    } else {
                        onGenerateClick()
                    }
                    coroutineScope.launch {
                        runCatching { generationsRequester.bringIntoView() }
                    }
                },
                testTag = "brief_generate_button"
            )

            OutlinedButton(
                onClick = {
                    if (canTrigger) {
                        onPreviewPlanClick()
                        coroutineScope.launch {
                            runCatching { generationsRequester.bringIntoView() }
                        }
                    } else {
                        onShowMessage("Ajoutez une description ou un texte pour préparer le plan de réalisation.")
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("brief_preview_plan_button")
            ) {
                Text("Voir le plan avant de générer")
            }
        }

        // Erreur visible localement près des actions/générations
        AnimatedVisibility(visible = state.errorMessage != null) {
            state.errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioError.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, StudioError.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("brief_inline_error_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = StudioError)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearError) {
                            Text("OK")
                        }
                    }
                }
            }
        }

        // 3. Section dédiée "Générations" (toujours visible : progression, plan, lecteur, segments, historique)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(generationsRequester)
                .testTag("brief_generations_section"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Générations (${state.generations.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (state.parseResult.segments.isNotEmpty()) {
                    val readyCount = state.parseResult.segments.count { seg ->
                        state.segmentStates[seg.id]?.status == SegmentStatus.READY
                    }
                    Text(
                        text = "$readyCount / ${state.parseResult.segments.size} seg. prêts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (isGenerating || state.isPlanningBrief) {
                LiveGenerationStatusCard(
                    phase = state.generationPhase,
                    currentSegment = state.currentSegmentIndex,
                    totalSegments = state.totalSegmentsCount,
                    onCancel = onCancelGeneration
                )
                if (state.isPlanningBrief) {
                    SkeletonBriefPlanCard()
                }
            }

            // Plan de réalisation + aperçu du script annoté généré
            AnimatedVisibility(visible = state.briefPlan != null && !state.isPlanningBrief) {
                state.briefPlan?.let { plan ->
                    BriefPlanCard(
                        plan = plan,
                        isBusy = isGenerating,
                        onGenerateFromPlan = onGenerateFromPlan,
                        onEditInExpert = onEditPlanInExpert,
                        onRedoPlan = onPreviewPlanClick
                    )
                }
            }

            // Lecteur final + Affinage après génération
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

                // Affinage en langage naturel
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Qu'est-ce que tu veux changer ?",
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (state.scriptVersions.isNotEmpty()) {
                                AssistChip(
                                    onClick = { showVersions = !showVersions },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    label = { Text("Versions (${state.scriptVersions.size})") }
                                )
                            }
                        }

                        state.lastRefinementNote?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        OutlinedTextField(
                            value = state.refinePrompt,
                            onValueChange = onUpdateRefinePrompt,
                            placeholder = {
                                Text("Ex. plus lent, ambiance moins forte, voix plus grave…")
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("brief_refine_input")
                        )

                        Button(
                            onClick = onApplyRefinement,
                            enabled = !state.isRefining && !isGenerating && state.refinePrompt.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("brief_apply_refine_button")
                        ) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.isRefining) "Retouche en cours…" else "Appliquer la retouche (cache conservé)")
                        }

                        AnimatedVisibility(visible = showVersions) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.scriptVersions.take(6).forEach { ver ->
                                    Surface(
                                        onClick = { onRestoreVersion(ver) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Text(ver.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                            Text("Restaurer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Segments générés en direct / en cours / en erreur
            if (state.parseResult.segments.isNotEmpty() &&
                (isGenerating || hasErroredSegments || state.segmentStates.values.any { it.status == SegmentStatus.READY })
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Segments de voix (${state.parseResult.segments.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(onClick = { showSegmentsList = !showSegmentsList }) {
                                Text(if (showSegmentsList) "Masquer" else "Afficher")
                            }
                        }

                        AnimatedVisibility(visible = showSegmentsList) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        }
                    }
                }
            }

            // Historique persistant des générations de ce projet
            if (state.generations.isNotEmpty()) {
                Text(
                    text = "Historique des prises générées",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                state.generations.forEachIndexed { index, record ->
                    GenerationHistoryCard(
                        record = record,
                        indexNumber = state.generations.size - index,
                        isActive = record.id == state.activeGenerationId && state.hasFinalAudio,
                        isPlaying = record.id == state.activeGenerationId && playback.activeTag == "final" && playback.isPlaying,
                        onPlayOrPause = { onSelectAndPlayGeneration(record) },
                        onOpenInExpert = { onOpenGenerationInExpert(record) },
                        onDelete = { onDeleteGeneration(record) }
                    )
                }
            } else if (!isGenerating && !state.isPlanningBrief && !state.hasFinalAudio && state.briefPlan == null && state.parseResult.segments.isEmpty()) {
                EmptyStudioBadge(
                    title = "Aucune génération pour l'instant",
                    subtitle = "Saisissez votre demande ci-dessus puis touchez « Générer l'audio » pour voir vos générations, segments et exports ici.",
                    modifier = Modifier.testTag("brief_empty_generations")
                )
            }
        }

        // 4. Fiche d'aide syntaxe fermée par défaut
        SyntaxHelpCollapsibleCard()
    }
}

@Composable
private fun GenerationHistoryCard(
    record: GenerationRecord,
    indexNumber: Int,
    isActive: Boolean,
    isPlaying: Boolean,
    onPlayOrPause: () -> Unit,
    onOpenInExpert: () -> Unit,
    onDelete: () -> Unit
) {
    var showScript by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("generation_history_card_${record.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Génération #$indexNumber",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (isActive) {
                            Text(
                                text = "• Active dans le lecteur",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioSuccess
                            )
                        }
                    }
                    Text(
                        text = "${dateFormatter.format(Date(record.createdAt))} • ${record.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Supprimer cette génération",
                        tint = StudioError
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(record.selectedVoice, color = TagVoiceViolet) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(formatTime(record.durationSec)) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("${record.segmentCount} seg.") }
                )
                if (record.ambiencesSummary.isNotBlank()) {
                    AssistChip(
                        onClick = {},
                        label = { Text(record.ambiencesSummary, color = TagAmbienceTurquoise) }
                    )
                }
            }

            if (record.directorExplanation.isNotBlank()) {
                Text(
                    text = record.directorExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlayOrPause,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Écouter",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "Pause" else if (isActive) "Écouter" else "Charger & écouter")
                }

                OutlinedButton(
                    onClick = onOpenInExpert,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Expert")
                }

                TextButton(
                    onClick = { showScript = !showScript },
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(if (showScript) "Masquer script" else "Script")
                }
            }

            AnimatedVisibility(visible = showScript) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = record.annotatedScript,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BriefPlanCard(
    plan: BriefPlan,
    isBusy: Boolean,
    onGenerateFromPlan: () -> Unit,
    onEditInExpert: () -> Unit,
    onRedoPlan: () -> Unit
) {
    var showAnnotatedScript by remember { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("brief_plan_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Plan de réalisation",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Text(
                text = plan.directorExplanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("Voix : ${plan.selectedVoice}") })
                AssistChip(onClick = {}, label = { Text("~${plan.estimatedDurationSeconds} s") })
            }

            if (plan.ambiences.isNotEmpty()) {
                Text(
                    text = "Ambiances : " + plan.ambiences.joinToString(", ") { "${it.name} (${it.volumePercent} %)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TagAmbienceTurquoise
                )
            }

            if (plan.toneChanges.isNotEmpty()) {
                Text(
                    text = "Variations de jeu prévues :",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                plan.toneChanges.forEach { tc ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "[ton: ${tc.cue}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = TagToneOrange
                            )
                            if (tc.excerpt.isNotBlank()) {
                                Text(
                                    text = "« ${tc.excerpt} »",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (tc.reason.isNotBlank()) {
                                Text(
                                    text = tc.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Aperçu dépliable du script généré avec ses consignes
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Script annoté généré",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { showAnnotatedScript = !showAnnotatedScript },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (showAnnotatedScript) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (showAnnotatedScript) "Réduire le script" else "Afficher le script"
                            )
                        }
                    }
                    AnimatedVisibility(visible = showAnnotatedScript) {
                        Text(
                            text = plan.annotatedScript,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onGenerateFromPlan,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("plan_generate_button")
                ) {
                    Text("Générer l'audio", fontWeight = FontWeight.Bold)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onEditInExpert,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("plan_edit_expert_button")
                    ) {
                        Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Modifier")
                    }
                    OutlinedButton(
                        onClick = onRedoPlan,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("plan_redo_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refaire")
                    }
                }
            }
        }
    }
}

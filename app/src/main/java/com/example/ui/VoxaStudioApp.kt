package com.example.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.AudioExportFormat
import com.example.domain.model.GenerationRecord
import com.example.domain.model.StudioMode
import com.example.ui.components.StudioGradientBackdrop
import androidx.compose.foundation.layout.heightIn
import com.example.ui.screens.BriefModeScreen
import com.example.ui.screens.DialogueModeScreen
import com.example.ui.screens.ExpertModeScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.SettingsAndDictionaryScreen
import com.example.ui.screens.VoicesAndAmbiencesScreen
import com.example.ui.theme.StudioError
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.StudioWarning
import com.example.ui.theme.VoxaStudioTheme
import com.example.viewmodel.GenerationPhase
import com.example.viewmodel.StudioToastEvent
import com.example.viewmodel.VoxaStudioViewModel

private enum class StudioOverlay {
    NONE,
    HISTORY,
    VOICES_AMBIENCES,
    PROJECTS,
    SETTINGS
}

@Composable
fun VoxaStudioApp(
    viewModel: VoxaStudioViewModel,
    onExportAudioRequest: (AudioExportFormat, Boolean, GenerationRecord?) -> Unit,
    onExportSrtRequest: () -> Unit,
    onBatchExportZipRequest: (List<GenerationRecord>, AudioExportFormat, Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var activeOverlay by remember { mutableStateOf(StudioOverlay.NONE) }

    // Maintien de l'écran allumé pendant une génération active
    val isBusy = state.generationPhase != GenerationPhase.IDLE || state.isPlanningBrief || state.isRefining
    DisposableEffect(isBusy) {
        val window = (context as? Activity)?.window
        if (isBusy) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Collecte des notifications toast/snackbar avec action Annuler (5 s)
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { event ->
            when (event) {
                is StudioToastEvent.Info -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }
                is StudioToastEvent.UndoDeleteProject -> {
                    val res = snackbarHostState.showSnackbar(
                        message = "Projet « ${event.project.title} » supprimé.",
                        actionLabel = "Annuler",
                        duration = SnackbarDuration.Long
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        viewModel.restoreDeletedProject(event.project)
                    }
                }
                is StudioToastEvent.UndoClearScript -> {
                    val res = snackbarHostState.showSnackbar(
                        message = "Script effacé.",
                        actionLabel = "Annuler",
                        duration = SnackbarDuration.Long
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        viewModel.updateExpertScript(event.previousScript)
                    }
                }
            }
        }
    }

    VoxaStudioTheme(themeMode = state.settings.themeMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.isCtrlPressed) {
                        when (keyEvent.key) {
                            Key.Enter -> {
                                if (state.mode == StudioMode.BRIEF) viewModel.planOrGenerateBrief(false)
                                else viewModel.generateAudioFromExpertScript(false)
                                true
                            }
                            Key.Z -> {
                                if (state.mode == StudioMode.EXPERT) viewModel.undoScript()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            StudioGradientBackdrop(modifier = Modifier.matchParentSize())

            when (activeOverlay) {
                StudioOverlay.HISTORY -> {
                    HistoryScreen(
                        state = state,
                        playback = playback,
                        onPlayOrPause = viewModel::selectAndPlayGeneration,
                        onSeekTo = viewModel.playerEngine::seekTo,
                        onDownload = { record, fmt, voiceOnly ->
                            onExportAudioRequest(fmt, voiceOnly, record)
                        },
                        onShare = { record, fmt, voiceOnly ->
                            viewModel.shareAudio(fmt, voiceOnly, record) { intent ->
                                context.startActivity(android.content.Intent.createChooser(intent, "Partager l'audio"))
                            }
                        },
                        onOpenInStudio = { record ->
                            viewModel.openHistoryGenerationInStudio(record)
                            activeOverlay = StudioOverlay.NONE
                        },
                        onToggleFavorite = viewModel::toggleGenerationFavorite,
                        onRename = viewModel::renameGeneration,
                        onUpdateTags = viewModel::updateGenerationTags,
                        onBatchExportZip = onBatchExportZipRequest,
                        onDelete = viewModel::deleteGeneration,
                        onClose = { activeOverlay = StudioOverlay.NONE }
                    )
                }

                StudioOverlay.VOICES_AMBIENCES -> {
                    VoicesAndAmbiencesScreen(
                        state = state,
                        playback = playback,
                        playerEngine = viewModel.playerEngine,
                        onSelectVoice = { v ->
                            viewModel.setSelectedVoice(v)
                            activeOverlay = StudioOverlay.NONE
                        },
                        onAuditionVoice = viewModel::auditionVoiceSample,
                        onInsertAmbienceTag = { tag ->
                            viewModel.setMode(StudioMode.EXPERT)
                            viewModel.insertTagAtCursor(tag)
                            activeOverlay = StudioOverlay.NONE
                        },
                        onImportCustomAmbience = viewModel::importCustomAmbience,
                        onDeleteCustomAmbience = viewModel::deleteCustomAmbience,
                        onUpdateAbPhrase = viewModel::updateAbTestPhrase,
                        onUpdateAbSlot = viewModel::updateAbSlot,
                        onRunAbTest = viewModel::runAbComparison,
                        onPlayAbSlot = viewModel::playAbSlot,
                        onClose = { activeOverlay = StudioOverlay.NONE }
                    )
                }

                StudioOverlay.PROJECTS -> {
                    ProjectsScreen(
                        state = state,
                        onCreateNew = viewModel::createNewBlankProject,
                        onOpenProject = viewModel::openProject,
                        onDuplicateProject = viewModel::duplicateProject,
                        onDeleteProject = viewModel::deleteProject,
                        onExportProjectJson = { viewModel.exportCurrentProjectJsonString() },
                        onImportUri = viewModel::importFileFromUri,
                        onClose = { activeOverlay = StudioOverlay.NONE }
                    )
                }

                StudioOverlay.SETTINGS -> {
                    SettingsAndDictionaryScreen(
                        state = state,
                        onUpdateSettings = viewModel::updateSettings,
                        onTestPersonalApiKey = viewModel::testPersonalGeminiApiKey,
                        onAddPoolApiKey = viewModel::addApiKeyToPool,
                        onImportBatchApiKeys = viewModel::importApiKeysBatch,
                        onTogglePoolKeyEnabled = viewModel::toggleApiKeyEnabled,
                        onRemovePoolKey = viewModel::removeApiKeyFromPool,
                        onTestPoolKey = viewModel::testSinglePoolKey,
                        onTestAllPoolKeys = viewModel::testAllPoolKeys,
                        onSelectPoolStrategy = viewModel::setApiPoolStrategy,
                        onToggleModelFallbackOnQuota = viewModel::setModelFallbackOnQuota,
                        onClearApiKeyTestState = viewModel::clearApiKeyTestState,
                        onPickAutoExportFolder = viewModel::setAutoExportFolder,
                        onClearAutoExportFolder = viewModel::clearAutoExportFolder,
                        onAddDictionaryEntry = viewModel::addPronunciationEntry,
                        onDeleteDictionaryEntry = viewModel::deletePronunciationEntry,
                        onClose = { activeOverlay = StudioOverlay.NONE }
                    )
                }

                StudioOverlay.NONE -> {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        contentWindowInsets = WindowInsets.safeDrawing,
                        snackbarHost = { SnackbarHost(snackbarHostState) }
                    ) { innerPadding ->
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .imePadding()
                        ) {
                            val isWide = maxWidth >= 840.dp

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = if (isWide) 1040.dp else 600.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // En-tête Studio + indicateur Enregistré / Pool API + Boutons Outils
                                    val activeApiCount = remember(state.settings) { state.settings.activeApiKeys().size }
                                    val poolConcurrency = remember(state.settings) { state.settings.effectiveConcurrency() }
                                    StudioTopBar(
                                        saveStatus = state.saveStatusLabel,
                                        isOffline = state.isOffline,
                                        activeApiCount = activeApiCount,
                                        poolConcurrency = poolConcurrency,
                                        historyCount = state.allGenerations.size,
                                        onOpenHistory = { activeOverlay = StudioOverlay.HISTORY },
                                        onOpenProjects = { activeOverlay = StudioOverlay.PROJECTS },
                                        onOpenVoices = { activeOverlay = StudioOverlay.VOICES_AMBIENCES },
                                        onOpenSettings = { activeOverlay = StudioOverlay.SETTINGS }
                                    )

                                    // Sélecteur Mode Brief / Mode Expert en haut de l'écran
                                    StudioModeSelector(
                                        currentMode = state.mode,
                                        onSelectMode = viewModel::setMode
                                    )

                                    // Bannière hors-ligne ou erreur explicite
                                    AnimatedVisibility(visible = state.isOffline) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = StudioWarning.copy(alpha = 0.16f),
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            border = BorderStroke(1.dp, StudioWarning.copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(10.dp)
                                            ) {
                                                Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = StudioWarning, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = "Mode hors ligne : vos projets, l'éditeur et les audios déjà en cache restent accessibles.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }

                                    AnimatedVisibility(visible = state.errorMessage != null) {
                                        state.errorMessage?.let { err ->
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = StudioError.copy(alpha = 0.16f),
                                                contentColor = MaterialTheme.colorScheme.onSurface,
                                                border = BorderStroke(1.dp, StudioError.copy(alpha = 0.55f)),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("error_banner")
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
                                                    TextButton(onClick = viewModel::clearError) {
                                                        Text("OK")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Contenu principal selon le mode sélectionné
                                    when (state.mode) {
                                        StudioMode.BRIEF -> {
                                            BriefModeScreen(
                                                state = state,
                                                playback = playback,
                                                onUpdateDescription = viewModel::updateBriefDescription,
                                                onUpdateRawText = viewModel::updateBriefRawText,
                                                onSelectDuration = viewModel::setTargetDuration,
                                                onSelectLanguage = viewModel::setSelectedLanguage,
                                                onSelectGender = viewModel::setGenderPreference,
                                                onApplyPreset = viewModel::applyStudioPreset,
                                                onGenerateClick = { viewModel.planOrGenerateBrief(previewPlanOnly = false) },
                                                onPreviewPlanClick = { viewModel.planOrGenerateBrief(previewPlanOnly = true) },
                                                onCancelGeneration = viewModel::cancelGeneration,
                                                onResumeErroredGeneration = { viewModel.generateAudioFromExpertScript(resumeOnly = true) },
                                                onGenerateFromPlan = viewModel::generateFromCurrentPlan,
                                                onEditPlanInExpert = viewModel::applyPlanToExpertEditor,
                                                onUpdateRefinePrompt = viewModel::updateRefinePrompt,
                                                onApplyRefinement = viewModel::refineExistingScript,
                                                onRestoreVersion = viewModel::restoreVersion,
                                                onPlaySegmentSolo = viewModel::playSegmentSolo,
                                                onRetrySegment = viewModel::retrySingleSegment,
                                                onSelectTake = viewModel::selectSegmentTake,
                                                onSelectAndPlayGeneration = viewModel::selectAndPlayGeneration,
                                                onOpenGenerationInExpert = viewModel::openGenerationInExpert,
                                                onDeleteGeneration = viewModel::deleteGeneration,
                                                onDownloadGeneration = { record, fmt, voiceOnly ->
                                                    onExportAudioRequest(fmt, voiceOnly, record)
                                                },
                                                onShareGeneration = { record, fmt, voiceOnly ->
                                                    viewModel.shareAudio(fmt, voiceOnly, record) { intent ->
                                                        context.startActivity(android.content.Intent.createChooser(intent, "Partager l'audio"))
                                                    }
                                                },
                                                onOpenGlobalHistory = { activeOverlay = StudioOverlay.HISTORY },
                                                onClearError = viewModel::clearError,
                                                onTogglePlayFinal = viewModel::switchToFinalPlayback,
                                                onSeekTo = viewModel.playerEngine::seekTo,
                                                onSeekDelta = viewModel.playerEngine::seekByDelta,
                                                onToggleVoiceOnly = viewModel::toggleVoiceOnlyPreview,
                                                onUpdateMixConfig = viewModel::updateMixConfig,
                                                onExport = { fmt, voiceOnly -> onExportAudioRequest(fmt, voiceOnly, null) },
                                                onExportSrt = onExportSrtRequest,
                                                onShare = { fmt, voiceOnly ->
                                                    viewModel.shareAudio(fmt, voiceOnly, null) { intent ->
                                                        context.startActivity(android.content.Intent.createChooser(intent, "Partager l'audio"))
                                                    }
                                                },
                                                onShowMessage = { msg ->
                                                    viewModel.triggerHaptic(isError = true)
                                                    viewModel.showInfoMessage(msg)
                                                }
                                            )
                                        }

                                        StudioMode.DIALOGUE -> {
                                            DialogueModeScreen(
                                                state = state,
                                                playback = playback,
                                                onAddLine = viewModel::addDialogueLine,
                                                onUpdateLine = viewModel::updateDialogueLine,
                                                onRemoveLine = viewModel::removeDialogueLine,
                                                onMoveLine = viewModel::moveDialogueLine,
                                                onSetAmbience = viewModel::setDialogueAmbience,
                                                onGenerateDialogue = viewModel::generateFromDialogue,
                                                onOpenInExpert = viewModel::openDialogueInExpert,
                                                onCancelGeneration = viewModel::cancelGeneration,
                                                onPlaySegmentSolo = viewModel::playSegmentSolo,
                                                onRetrySegment = viewModel::retrySingleSegment,
                                                onSelectTake = viewModel::selectSegmentTake,
                                                onTogglePlayFinal = viewModel::switchToFinalPlayback,
                                                onSeekTo = viewModel.playerEngine::seekTo,
                                                onSeekDelta = viewModel.playerEngine::seekByDelta,
                                                onToggleVoiceOnly = viewModel::toggleVoiceOnlyPreview,
                                                onUpdateMixConfig = viewModel::updateMixConfig,
                                                onExport = { fmt, voiceOnly -> onExportAudioRequest(fmt, voiceOnly, null) },
                                                onExportSrt = onExportSrtRequest,
                                                onShare = { fmt, voiceOnly ->
                                                    viewModel.shareAudio(fmt, voiceOnly, null) { intent ->
                                                        context.startActivity(android.content.Intent.createChooser(intent, "Partager l'audio"))
                                                    }
                                                },
                                                onShowMessage = { msg ->
                                                    viewModel.triggerHaptic(isError = true)
                                                    viewModel.showInfoMessage(msg)
                                                }
                                            )
                                        }

                                        StudioMode.EXPERT -> {
                                            ExpertModeScreen(
                                                state = state,
                                                playback = playback,
                                                onScriptChange = { txt, cursor -> viewModel.updateExpertScript(txt, cursor) },
                                                onCursorChange = viewModel::updateCursorPosition,
                                                onInsertTag = viewModel::insertTagAtCursor,
                                                onUndo = viewModel::undoScript,
                                                onRedo = viewModel::redoScript,
                                                onSelectVoice = viewModel::setSelectedVoice,
                                                onAcceptSuggestion = viewModel::acceptContextualToneSuggestion,
                                                onDismissSuggestion = viewModel::dismissContextualToneSuggestion,
                                                onAuditionPassage = viewModel::auditionSelectedPassage,
                                                onGenerateClick = viewModel::generateAudioFromExpertScript,
                                                onCancelGeneration = viewModel::cancelGeneration,
                                                onPlaySegmentSolo = viewModel::playSegmentSolo,
                                                onRetrySegment = viewModel::retrySingleSegment,
                                                onSelectTake = viewModel::selectSegmentTake,
                                                onTogglePlayFinal = viewModel::switchToFinalPlayback,
                                                onSeekTo = viewModel.playerEngine::seekTo,
                                                onSeekDelta = viewModel.playerEngine::seekByDelta,
                                                onToggleVoiceOnly = viewModel::toggleVoiceOnlyPreview,
                                                onUpdateMixConfig = viewModel::updateMixConfig,
                                                onExport = { fmt, voiceOnly -> onExportAudioRequest(fmt, voiceOnly, null) },
                                                onExportSrt = onExportSrtRequest,
                                                onShare = { fmt, voiceOnly ->
                                                    viewModel.shareAudio(fmt, voiceOnly, null) { intent ->
                                                        context.startActivity(android.content.Intent.createChooser(intent, "Partager l'audio"))
                                                    }
                                                },
                                                onShowMessage = { msg ->
                                                    viewModel.showInfoMessage(msg)
                                                }
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioTopBar(
    saveStatus: String,
    isOffline: Boolean,
    activeApiCount: Int,
    poolConcurrency: Int,
    historyCount: Int,
    onOpenHistory: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenVoices: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "Voxa Studio",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val poolLabel = when {
                    poolConcurrency >= 2 -> "$activeApiCount API • ×$poolConcurrency Turbo"
                    activeApiCount > 1 -> "$activeApiCount API actives"
                    else -> ""
                }
                val subtitle = listOfNotNull(
                    if (isOffline) "Hors ligne" else saveStatus.takeIf { it.isNotEmpty() },
                    poolLabel.takeIf { it.isNotEmpty() }
                ).joinToString(" • ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOffline) StudioWarning else StudioSuccess,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenHistory,
                modifier = Modifier.size(48.dp).testTag("nav_history_button")
            ) {
                BadgedBox(
                    badge = {
                        if (historyCount > 0) {
                            Badge {
                                Text(if (historyCount > 99) "99+" else historyCount.toString())
                            }
                        }
                    }
                ) {
                    Icon(Icons.Rounded.History, contentDescription = "Historique des générations")
                }
            }
            IconButton(
                onClick = onOpenProjects,
                modifier = Modifier.size(48.dp).testTag("nav_projects_button")
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = "Projets")
            }
            IconButton(
                onClick = onOpenVoices,
                modifier = Modifier.size(48.dp).testTag("nav_voices_button")
            ) {
                Icon(Icons.Rounded.RecordVoiceOver, contentDescription = "Voix, Test A/B et Ambiances")
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp).testTag("nav_settings_button")
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "Réglages")
            }
        }
    }
}

@Composable
private fun StudioModeSelector(
    currentMode: StudioMode,
    onSelectMode: (StudioMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeTabItem(
                label = "Brief",
                selected = currentMode == StudioMode.BRIEF,
                onClick = { onSelectMode(StudioMode.BRIEF) },
                modifier = Modifier.weight(1f).testTag("mode_brief_tab")
            )
            ModeTabItem(
                label = "Dialogue",
                selected = currentMode == StudioMode.DIALOGUE,
                onClick = { onSelectMode(StudioMode.DIALOGUE) },
                modifier = Modifier.weight(1f).testTag("mode_dialogue_tab")
            )
            ModeTabItem(
                label = "Expert",
                selected = currentMode == StudioMode.EXPERT,
                onClick = { onSelectMode(StudioMode.EXPERT) },
                modifier = Modifier.weight(1f).testTag("mode_expert_tab")
            )
        }
    }
}

@Composable
private fun ModeTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

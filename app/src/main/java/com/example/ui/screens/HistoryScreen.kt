package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.AudioExportFormat
import com.example.audio.PlaybackState
import com.example.domain.model.GenerationRecord
import com.example.domain.model.StudioMode
import com.example.ui.components.EmptyStudioBadge
import com.example.ui.components.formatTime
import com.example.ui.theme.StudioError
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.StudioWarning
import com.example.ui.theme.TagAmbienceTurquoise
import com.example.ui.theme.TagVoiceViolet
import com.example.viewmodel.StudioUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HistoryModeFilter(val labelFr: String) {
    ALL("Tout"),
    FAVORITES("Favoris"),
    BRIEF("Brief"),
    DIALOGUE("Dialogue"),
    EXPERT("Expert")
}

private val QuickHistoryTags = listOf("Pub", "Podcast", "TikTok", "Client", "Validé", "Brouillon")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    state: StudioUiState,
    playback: PlaybackState,
    onPlayOrPause: (GenerationRecord) -> Unit,
    onSeekTo: (Float) -> Unit,
    onDownload: (GenerationRecord, AudioExportFormat, Boolean) -> Unit,
    onShare: (GenerationRecord, AudioExportFormat, Boolean) -> Unit,
    onOpenInStudio: (GenerationRecord) -> Unit,
    onToggleFavorite: (GenerationRecord) -> Unit,
    onRename: (GenerationRecord, String) -> Unit,
    onUpdateTags: (GenerationRecord, List<String>) -> Unit,
    onBatchExportZip: (List<GenerationRecord>, AudioExportFormat, Boolean) -> Unit,
    onDelete: (GenerationRecord) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    var searchQuery by remember { mutableStateOf("") }
    var modeFilter by remember { mutableStateOf(HistoryModeFilter.ALL) }
    var selectedVoiceFilter by remember { mutableStateOf<String?>(null) }
    var pendingDeleteRecord by remember { mutableStateOf<GenerationRecord?>(null) }
    var pendingRenameRecord by remember { mutableStateOf<GenerationRecord?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var pendingTagsRecord by remember { mutableStateOf<GenerationRecord?>(null) }
    var tagsDraft by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showZipMenu by remember { mutableStateOf(false) }

    val availableVoices = remember(state.allGenerations) {
        state.allGenerations.map { it.selectedVoice }.distinct().sorted()
    }

    val filteredGenerations = remember(state.allGenerations, searchQuery, modeFilter, selectedVoiceFilter) {
        val q = searchQuery.trim().lowercase()
        state.allGenerations.filter { record ->
            val matchesMode = when (modeFilter) {
                HistoryModeFilter.ALL -> true
                HistoryModeFilter.FAVORITES -> record.isFavorite
                HistoryModeFilter.BRIEF -> record.sourceMode == StudioMode.BRIEF
                HistoryModeFilter.DIALOGUE -> record.sourceMode == StudioMode.DIALOGUE
                HistoryModeFilter.EXPERT -> record.sourceMode == StudioMode.EXPERT
            }
            val matchesVoice = selectedVoiceFilter == null ||
                record.selectedVoice.equals(selectedVoiceFilter, ignoreCase = true)
            val matchesQuery = q.isEmpty() ||
                record.title.lowercase().contains(q) ||
                record.selectedVoice.lowercase().contains(q) ||
                record.annotatedScript.lowercase().contains(q) ||
                record.ambiencesSummary.lowercase().contains(q) ||
                record.tags.any { it.lowercase().contains(q) }
            matchesMode && matchesVoice && matchesQuery
        }
    }

    val activeRecord = remember(state.allGenerations, state.activeGenerationId) {
        state.allGenerations.firstOrNull { it.id == state.activeGenerationId }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)
                ),
                title = {
                    Column {
                        Text(
                            text = if (selectionMode) "${selectedIds.size} sélectionnée(s)" else "Historique des générations",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.allGenerations.size} création(s) sauvegardée(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedIds = emptySet()
                            } else {
                                onClose()
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_back_button")
                    ) {
                        Icon(
                            imageVector = if (selectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (selectionMode) "Quitter la sélection" else "Retour au studio"
                        )
                    }
                },
                actions = {
                    if (state.allGenerations.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                if (selectionMode) {
                                    selectionMode = false
                                    selectedIds = emptySet()
                                } else {
                                    selectionMode = true
                                }
                            },
                            modifier = Modifier.testTag("history_toggle_multiselect")
                        ) {
                            Text(if (selectionMode) "Terminer" else "Sélectionner")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
                    .padding(horizontal = 16.dp)
            ) {
                // Mini-lecteur fixe si une génération de l'historique est chargée dans le lecteur
                AnimatedVisibility(visible = activeRecord != null && state.hasFinalAudio) {
                    activeRecord?.let { current ->
                        val isPlaying = playback.activeTag == "final" && playback.isPlaying
                        val totalSec = current.durationSec.coerceAtLeast(0.1f)
                        val currentSec = if (playback.activeTag == "final") playback.currentSec.coerceIn(0f, totalSec) else 0f

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .testTag("history_mini_player")
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        IconButton(onClick = { onPlayOrPause(current) }) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = if (isPlaying) "Mettre en pause" else "Lire",
                                                tint = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = current.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${formatTime(currentSec)} / ${formatTime(totalSec)} • ${current.selectedVoice}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    FilledDownloadMenuButton(
                                        label = "Télécharger",
                                        onDownload = { fmt, voiceOnly -> onDownload(current, fmt, voiceOnly) },
                                        testTag = "history_mini_download"
                                    )
                                }
                                Slider(
                                    value = currentSec,
                                    onValueChange = onSeekTo,
                                    valueRange = 0f..totalSec
                                )
                            }
                        }
                    }
                }

                // Barre de recherche & filtres
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Rechercher par titre, voix, tag ou script…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Effacer la recherche")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("history_search_input")
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        val favCount = remember(state.allGenerations) { state.allGenerations.count { it.isFavorite } }
                        val briefCount = remember(state.allGenerations) { state.allGenerations.count { it.sourceMode == StudioMode.BRIEF } }
                        val dialogueCount = remember(state.allGenerations) { state.allGenerations.count { it.sourceMode == StudioMode.DIALOGUE } }
                        val expertCount = remember(state.allGenerations) { state.allGenerations.count { it.sourceMode == StudioMode.EXPERT } }

                        HistoryModeFilter.entries.forEach { filter ->
                            val count = when (filter) {
                                HistoryModeFilter.ALL -> state.allGenerations.size
                                HistoryModeFilter.FAVORITES -> favCount
                                HistoryModeFilter.BRIEF -> briefCount
                                HistoryModeFilter.DIALOGUE -> dialogueCount
                                HistoryModeFilter.EXPERT -> expertCount
                            }
                            FilterChip(
                                selected = modeFilter == filter,
                                onClick = { modeFilter = filter },
                                label = { Text("${filter.labelFr} ($count)") },
                                modifier = Modifier.testTag("history_filter_${filter.name.lowercase()}")
                            )
                        }
                    }

                    if (availableVoices.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            FilterChip(
                                selected = selectedVoiceFilter == null,
                                onClick = { selectedVoiceFilter = null },
                                label = { Text("Toutes les voix") }
                            )
                            availableVoices.forEach { v ->
                                FilterChip(
                                    selected = selectedVoiceFilter == v,
                                    onClick = {
                                        selectedVoiceFilter = if (selectedVoiceFilter == v) null else v
                                    },
                                    label = { Text(v, color = TagVoiceViolet) }
                                )
                            }
                        }
                    }

                    // Barre d'export groupé .zip en mode multi-sélection
                    AnimatedVisibility(visible = selectionMode) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        selectedIds = if (selectedIds.size == filteredGenerations.size) {
                                            emptySet()
                                        } else {
                                            filteredGenerations.map { it.id }.toSet()
                                        }
                                    }
                                ) {
                                    Text(
                                        if (selectedIds.size == filteredGenerations.size && filteredGenerations.isNotEmpty()) {
                                            "Tout décocher"
                                        } else {
                                            "Tout cocher"
                                        }
                                    )
                                }

                                Box {
                                    Button(
                                        onClick = { showZipMenu = true },
                                        enabled = selectedIds.isNotEmpty() && !state.isExportingZip,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("history_export_zip_button")
                                    ) {
                                        Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (state.isExportingZip) "Compression…" else "Exporter ZIP (${selectedIds.size})")
                                    }
                                    DropdownMenu(
                                        expanded = showZipMenu,
                                        onDismissRequest = { showZipMenu = false }
                                    ) {
                                        val selectedRecords = filteredGenerations.filter { it.id in selectedIds }
                                        DropdownMenuItem(
                                            text = { Text("ZIP Audio mixé (.m4a + scripts)") },
                                            onClick = {
                                                showZipMenu = false
                                                onBatchExportZip(selectedRecords, AudioExportFormat.M4A, false)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("ZIP Audio mixé (.wav + scripts)") },
                                            onClick = {
                                                showZipMenu = false
                                                onBatchExportZip(selectedRecords, AudioExportFormat.WAV, false)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("ZIP Voix seule (.wav + scripts)") },
                                            onClick = {
                                                showZipMenu = false
                                                onBatchExportZip(selectedRecords, AudioExportFormat.WAV, true)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (filteredGenerations.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        EmptyStudioBadge(
                            title = if (state.allGenerations.isEmpty()) {
                                "Aucune génération enregistrée"
                            } else {
                                "Aucun résultat pour cette recherche"
                            },
                            subtitle = if (state.allGenerations.isEmpty()) {
                                "Toutes vos voix off générées sont sauvegardées ici pour être réécoutées et téléchargées à tout moment."
                            } else {
                                "Modifiez vos mots-clés ou sélectionnez le filtre « Tout »."
                            },
                            modifier = Modifier.testTag("history_empty_state")
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = filteredGenerations,
                            key = { _, rec -> rec.id }
                        ) { _, record ->
                            GlobalHistoryGenerationCard(
                                record = record,
                                isActive = record.id == state.activeGenerationId && state.hasFinalAudio,
                                isPlaying = record.id == state.activeGenerationId &&
                                    playback.activeTag == "final" &&
                                    playback.isPlaying,
                                selectionMode = selectionMode,
                                isSelected = record.id in selectedIds,
                                onToggleSelect = {
                                    selectedIds = if (record.id in selectedIds) {
                                        selectedIds - record.id
                                    } else {
                                        selectedIds + record.id
                                    }
                                },
                                onToggleFavorite = { onToggleFavorite(record) },
                                onRequestRename = {
                                    renameDraft = record.title
                                    pendingRenameRecord = record
                                },
                                onRequestEditTags = {
                                    tagsDraft = record.tags.joinToString(", ")
                                    pendingTagsRecord = record
                                },
                                onPlayOrPause = { onPlayOrPause(record) },
                                onDownload = { fmt, voiceOnly -> onDownload(record, fmt, voiceOnly) },
                                onShare = { fmt, voiceOnly -> onShare(record, fmt, voiceOnly) },
                                onOpenInStudio = { onOpenInStudio(record) },
                                onRequestDelete = { pendingDeleteRecord = record }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingRenameRecord?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRenameRecord = null },
            title = { Text("Renommer la génération") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("Nouveau titre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("history_rename_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(target, renameDraft)
                        pendingRenameRecord = null
                    },
                    enabled = renameDraft.isNotBlank()
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameRecord = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    pendingTagsRecord?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTagsRecord = null },
            title = { Text("Tags de la génération") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tagsDraft,
                        onValueChange = { tagsDraft = it },
                        label = { Text("Tags séparés par virgule") },
                        placeholder = { Text("Ex. Pub, Client, Validé") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("history_tags_input")
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val currentList = tagsDraft.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        QuickHistoryTags.forEach { presetTag ->
                            val has = currentList.any { it.equals(presetTag, ignoreCase = true) }
                            FilterChip(
                                selected = has,
                                onClick = {
                                    val updated = if (has) {
                                        currentList.filterNot { it.equals(presetTag, ignoreCase = true) }
                                    } else {
                                        currentList + presetTag
                                    }
                                    tagsDraft = updated.joinToString(", ")
                                },
                                label = { Text(presetTag) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val tags = tagsDraft.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        onUpdateTags(target, tags)
                        pendingTagsRecord = null
                    }
                ) {
                    Text("Valider")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTagsRecord = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    pendingDeleteRecord?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRecord = null },
            title = { Text("Supprimer cette génération ?") },
            text = {
                Text("« ${target.title} » et ses fichiers audio enregistrés sur l'appareil seront définitivement supprimés.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(target)
                        pendingDeleteRecord = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StudioError)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRecord = null }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalHistoryGenerationCard(
    record: GenerationRecord,
    isActive: Boolean,
    isPlaying: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRequestRename: () -> Unit,
    onRequestEditTags: () -> Unit,
    onPlayOrPause: () -> Unit,
    onDownload: (AudioExportFormat, Boolean) -> Unit,
    onShare: (AudioExportFormat, Boolean) -> Unit,
    onOpenInStudio: () -> Unit,
    onRequestDelete: () -> Unit
) {
    var showScript by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.FRANCE) }
    val hasAudioFile = remember(record.mixedPcmPath) {
        val f = File(record.mixedPcmPath)
        f.exists() && f.length() > 0L
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
            isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (isSelected || isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("global_history_card_${record.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (selectionMode) {
                    IconButton(
                        onClick = onToggleSelect,
                        modifier = Modifier.size(48.dp).testTag("history_select_${record.id}")
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = if (isSelected) "Désélectionner" else "Sélectionner",
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = record.title.ifBlank { "Génération sans titre" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(
                                text = when (record.sourceMode) {
                                    StudioMode.BRIEF -> "Brief"
                                    StudioMode.DIALOGUE -> "Dialogue"
                                    StudioMode.EXPERT -> "Expert"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = dateFormatter.format(Date(record.createdAt)) +
                            if (isActive) " • Active" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) StudioSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_favorite_${record.id}")
                    ) {
                        Icon(
                            imageVector = if (record.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = if (record.isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                            tint = if (record.isFavorite) StudioWarning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onRequestRename,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_rename_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DriveFileRenameOutline,
                            contentDescription = "Renommer cette génération"
                        )
                    }
                    IconButton(
                        onClick = onRequestDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_delete_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Supprimer cette génération",
                            tint = StudioError
                        )
                    }
                }
            }

            if (record.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    record.tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
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
                AssistChip(
                    onClick = {},
                    label = { Text("${"%.1f".format(Locale.US, record.integratedLufs)} LUFS") }
                )
                if (record.ambiencesSummary.isNotBlank()) {
                    AssistChip(
                        onClick = {},
                        label = { Text(record.ambiencesSummary, color = TagAmbienceTurquoise) }
                    )
                }
            }

            if (!hasAudioFile) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioWarning.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = StudioWarning,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Fichier audio absent sur l'appareil (le script reste récupérable ci-dessous).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlayOrPause,
                    enabled = hasAudioFile,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("history_play_${record.id}")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "Pause" else "Écouter")
                }

                FilledDownloadMenuButton(
                    label = "Télécharger",
                    enabled = hasAudioFile,
                    onDownload = onDownload,
                    testTag = "history_download_${record.id}"
                )

                Box {
                    OutlinedButton(
                        onClick = { showShareMenu = true },
                        enabled = hasAudioFile,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("history_share_${record.id}")
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Partager")
                    }
                    DropdownMenu(
                        expanded = showShareMenu,
                        onDismissRequest = { showShareMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Audio mixé (.m4a)") },
                            onClick = {
                                showShareMenu = false
                                onShare(AudioExportFormat.M4A, false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Audio mixé (.wav)") },
                            onClick = {
                                showShareMenu = false
                                onShare(AudioExportFormat.WAV, false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Voix seule (.m4a)") },
                            onClick = {
                                showShareMenu = false
                                onShare(AudioExportFormat.M4A, true)
                            }
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenInStudio,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("history_open_studio_${record.id}")
                ) {
                    Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Éditer")
                }

                OutlinedButton(
                    onClick = onRequestEditTags,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("history_tags_button_${record.id}")
                ) {
                    Icon(Icons.Rounded.Label, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tags")
                }

                TextButton(
                    onClick = { showScript = !showScript },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(if (showScript) "Masquer script" else "Script")
                }
            }

            AnimatedVisibility(visible = showScript) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (record.directorExplanation.isNotBlank()) {
                            Text(
                                text = record.directorExplanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = record.annotatedScript.ifBlank { "(Script vide)" },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FilledDownloadMenuButton(
    label: String,
    enabled: Boolean = true,
    onDownload: (AudioExportFormat, Boolean) -> Unit,
    testTag: String
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .height(48.dp)
                .testTag(testTag)
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Audio mixé (.wav)") },
                onClick = {
                    expanded = false
                    onDownload(AudioExportFormat.WAV, false)
                }
            )
            DropdownMenuItem(
                text = { Text("Audio mixé (.m4a)") },
                onClick = {
                    expanded = false
                    onDownload(AudioExportFormat.M4A, false)
                }
            )
            DropdownMenuItem(
                text = { Text("Voix seule (.wav)") },
                onClick = {
                    expanded = false
                    onDownload(AudioExportFormat.WAV, true)
                }
            )
            DropdownMenuItem(
                text = { Text("Voix seule (.m4a)") },
                onClick = {
                    expanded = false
                    onDownload(AudioExportFormat.M4A, true)
                }
            )
        }
    }
}

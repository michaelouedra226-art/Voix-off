package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ai.isFirebaseAppReady
import com.example.audio.AudioPlayerEngine
import com.example.audio.PlaybackState
import com.example.domain.model.AmbienceCatalog
import com.example.domain.model.CustomAmbience
import com.example.domain.model.LanguageOption
import com.example.domain.model.PresetTones
import com.example.domain.model.ProjectItem
import com.example.domain.model.StudioSettings
import com.example.domain.model.TtsModelOption
import com.example.domain.model.VoiceCatalog
import com.example.ui.components.EmptyStudioBadge
import com.example.ui.theme.StudioError
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.StudioWarning
import com.example.ui.theme.TagAmbienceTurquoise
import com.example.ui.theme.TagVoiceViolet
import com.example.ui.theme.ThemeMode
import com.example.viewmodel.AbTestSlot
import com.example.viewmodel.StudioUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesAndAmbiencesScreen(
    state: StudioUiState,
    playback: PlaybackState,
    playerEngine: AudioPlayerEngine,
    onSelectVoice: (String) -> Unit,
    onAuditionVoice: (String) -> Unit,
    onInsertAmbienceTag: (String) -> Unit,
    onImportCustomAmbience: (String, Uri) -> Unit,
    onDeleteCustomAmbience: (CustomAmbience) -> Unit,
    onUpdateAbPhrase: (String) -> Unit,
    onUpdateAbSlot: (Boolean, String?, String?) -> Unit,
    onRunAbTest: () -> Unit,
    onPlayAbSlot: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var customAmbName by remember { mutableStateOf("") }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fallbackName = customAmbName.ifBlank { "ambiance_${state.customAmbiences.size + 1}" }
            onImportCustomAmbience(fallbackName, uri)
            customAmbName = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voix, Test A/B & Ambiances") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour au studio")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("14 Voix") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Test A/B") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Ambiances") }
                )
            }

            when (selectedTab) {
                0 -> {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.voicePreviewError != null) {
                            item {
                                Text(
                                    text = state.voicePreviewError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioError
                                )
                            }
                        }
                        items(VoiceCatalog.voices, key = { it.name }) { voice ->
                            val isSelected = state.selectedVoice == voice.name
                            val isPreviewing = state.previewingVoiceName == voice.name
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
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
                                        Column {
                                            Text(
                                                text = voice.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = TagVoiceViolet
                                            )
                                            Text(
                                                text = "${voice.genderFr} • ${voice.timbreFr}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                Icons.Rounded.CheckCircle,
                                                contentDescription = "Voix active",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Text(
                                        text = voice.descriptionFr,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { onAuditionVoice(voice.name) },
                                            enabled = !isPreviewing,
                                            modifier = Modifier.height(44.dp)
                                        ) {
                                            if (isPreviewing) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (isPreviewing) "Synthèse…" else "Échantillon")
                                        }
                                        Button(
                                            onClick = { onSelectVoice(voice.name) },
                                            modifier = Modifier.height(44.dp)
                                        ) {
                                            Text(if (isSelected) "Sélectionnée" else "Choisir")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "Comparez une même phrase avec deux voix ou deux tons côte à côte avant de choisir.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = state.abTestPhrase,
                                onValueChange = onUpdateAbPhrase,
                                label = { Text("Phrase à tester") },
                                placeholder = { Text("Saisissez la phrase à comparer…") },
                                minLines = 2,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            AbSlotCard(
                                title = "Version A",
                                slot = state.abSlotA,
                                isPlaying = playback.activeTag == "ab:A" && playback.isPlaying,
                                onVoiceSelected = { onUpdateAbSlot(true, it, null) },
                                onToneSelected = { onUpdateAbSlot(true, null, it) },
                                onPlay = { onPlayAbSlot(true) },
                                onApply = { onSelectVoice(state.abSlotA.voiceName) }
                            )
                        }
                        item {
                            AbSlotCard(
                                title = "Version B",
                                slot = state.abSlotB,
                                isPlaying = playback.activeTag == "ab:B" && playback.isPlaying,
                                onVoiceSelected = { onUpdateAbSlot(false, it, null) },
                                onToneSelected = { onUpdateAbSlot(false, null, it) },
                                onPlay = { onPlayAbSlot(false) },
                                onApply = { onSelectVoice(state.abSlotB.voiceName) }
                            )
                        }
                        item {
                            Button(
                                onClick = onRunAbTest,
                                enabled = !state.abSlotA.isLoading && !state.abSlotB.isLoading && state.abTestPhrase.isNotBlank(),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Rounded.CompareArrows, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Générer les deux essais A/B")
                            }
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Importer votre propre ambiance (MP3 / WAV / M4A)", style = MaterialTheme.typography.titleSmall)
                                    OutlinedTextField(
                                        value = customAmbName,
                                        onValueChange = { customAmbName = it },
                                        label = { Text("Nom dans les crochets (ex. jingle)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                                        modifier = Modifier.fillMaxWidth().height(48.dp)
                                    ) {
                                        Icon(Icons.Rounded.FileUpload, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Choisir un fichier audio")
                                    }
                                }
                            }
                        }

                        if (state.customAmbiences.isNotEmpty()) {
                            item {
                                Text("Vos ambiances importées", style = MaterialTheme.typography.titleMedium)
                            }
                            items(state.customAmbiences, key = { it.id }) { custom ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(custom.name, style = MaterialTheme.typography.titleSmall, color = TagAmbienceTurquoise)
                                            Text("[ambiance: ${custom.name}, 25%] • ${"%.1f".format(Locale.US, custom.durationSec)} s", style = MaterialTheme.typography.bodySmall)
                                        }
                                        IconButton(onClick = { playerEngine.playAmbiencePreview(custom.name, custom.pcmFilePath) }) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Aperçu 5 secondes")
                                        }
                                        IconButton(onClick = { onInsertAmbienceTag("[ambiance: ${custom.name}, 25%]") }) {
                                            Icon(Icons.Rounded.Add, contentDescription = "Insérer dans l'éditeur")
                                        }
                                        IconButton(onClick = { onDeleteCustomAmbience(custom) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer", tint = StudioError)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text("14 préréglages synthétisés sans coupure", style = MaterialTheme.typography.titleMedium)
                        }
                        items(AmbienceCatalog.presets, key = { it.id }) { preset ->
                            val isPlayingPreview = playback.activeTag == "ambience:${preset.name}" && playback.isPlaying
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(preset.name, style = MaterialTheme.typography.titleSmall, color = TagAmbienceTurquoise)
                                        Text("${preset.categoryFr} • ${preset.sceneHintFr}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            if (isPlayingPreview) playerEngine.pause()
                                            else playerEngine.playAmbiencePreview(preset.name)
                                        },
                                        modifier = Modifier.height(44.dp)
                                    ) {
                                        Text(if (isPlayingPreview) "Stop" else "5 s")
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { onInsertAmbienceTag("[ambiance: ${preset.name}, ${(preset.defaultVolume * 100).toInt()}%]") },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = "Insérer cette ambiance")
                                    }
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
private fun AbSlotCard(
    title: String,
    slot: AbTestSlot,
    isPlaying: Boolean,
    onVoiceSelected: (String) -> Unit,
    onToneSelected: (String) -> Unit,
    onPlay: () -> Unit,
    onApply: () -> Unit
) {
    var showVoices by remember { mutableStateOf(false) }
    var showTones by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { showVoices = true },
                        label = { Text("Voix : ${slot.voiceName}") }
                    )
                    DropdownMenu(expanded = showVoices, onDismissRequest = { showVoices = false }) {
                        VoiceCatalog.voices.forEach { v ->
                            DropdownMenuItem(
                                text = { Text("${v.name} (${v.genderFr})") },
                                onClick = { onVoiceSelected(v.name); showVoices = false }
                            )
                        }
                    }
                }
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { showTones = true },
                        label = { Text("Ton : ${slot.tone}") }
                    )
                    DropdownMenu(expanded = showTones, onDismissRequest = { showTones = false }) {
                        PresetTones.tones.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = { onToneSelected(t); showTones = false }
                            )
                        }
                    }
                }
            }
            if (slot.error != null) {
                Text(slot.error, style = MaterialTheme.typography.bodySmall, color = StudioError)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPlay,
                    enabled = slot.pcmSamples != null && !slot.isLoading,
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(
                        when {
                            slot.isLoading -> "Génération…"
                            isPlaying -> "En lecture"
                            slot.pcmSamples != null -> "Écouter (${"%.1f".format(Locale.US, slot.durationSec)} s)"
                            else -> "En attente"
                        }
                    )
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier.height(44.dp)
                ) {
                    Text("Adopter ${slot.voiceName}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    state: StudioUiState,
    onCreateNew: () -> Unit,
    onOpenProject: (ProjectItem) -> Unit,
    onDuplicateProject: (ProjectItem) -> Unit,
    onDeleteProject: (ProjectItem) -> Unit,
    onExportProjectJson: (ProjectItem) -> String,
    onImportUri: (Uri, String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    val context = LocalContext.current
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportUri(uri, uri.lastPathSegment ?: "import.txt")
            onClose()
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        if (uri != null && json != null) {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
        }
        pendingExportJson = null
    }

    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes projets") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onCreateNew(); onClose() },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(48.dp).testTag("new_project_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Nouveau")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "text/markdown", "*/*")) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(48.dp).testTag("import_project_button")
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Importer")
                    }
                }
            }

            if (state.projects.isEmpty()) {
                item {
                    EmptyStudioBadge(
                        icon = {
                            Icon(
                                Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        },
                        title = "Aucun projet pour l'instant",
                        subtitle = "Décris ce que tu veux pour commencer. Chaque projet est sauvegardé automatiquement."
                    )
                }
            } else {
                items(state.projects, key = { it.id }) { proj ->
                    val isActive = proj.id == state.activeProjectId
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(proj.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${proj.mode.name} • Voix ${proj.defaultVoice} • ${dateFormatter.format(Date(proj.updatedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { onOpenProject(proj); onClose() },
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    Text(if (isActive) "Ouvert" else "Ouvrir")
                                }
                                IconButton(onClick = { onDuplicateProject(proj) }, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Dupliquer")
                                }
                                IconButton(
                                    onClick = {
                                        pendingExportJson = onExportProjectJson(proj)
                                        exportJsonLauncher.launch("${proj.title.take(24).ifBlank { "projet" }}.voxa.json")
                                    },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(Icons.Rounded.SaveAlt, contentDescription = "Exporter en JSON")
                                }
                                IconButton(onClick = { onDeleteProject(proj) }, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer", tint = StudioError)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAndDictionaryScreen(
    state: StudioUiState,
    onUpdateSettings: ((StudioSettings) -> StudioSettings) -> Unit,
    onSavePersonalApiKey: (String) -> Unit,
    onTestPersonalApiKey: (String) -> Unit,
    onClearApiKeyTestState: () -> Unit,
    onAddDictionaryEntry: (String, String) -> Unit,
    onDeleteDictionaryEntry: (String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    var wordInput by remember { mutableStateOf("") }
    var phoneticInput by remember { mutableStateOf("") }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    var apiKeyInput by remember(state.settings.personalGeminiApiKey) {
        mutableStateOf(state.settings.personalGeminiApiKey)
    }
    var showApiKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages & Prononciation") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Statut connexion Gemini (Clé personnelle directe ou Firebase AI Logic & App Check)
            item {
                val context = LocalContext.current
                val firebaseReady = remember(context) { isFirebaseAppReady(context) }
                val hasPersonalKey = state.settings.personalGeminiApiKey.isNotBlank()
                val statusColor = when {
                    hasPersonalKey -> StudioSuccess
                    firebaseReady -> StudioSuccess
                    else -> StudioWarning
                }
                val statusIcon = when {
                    hasPersonalKey -> Icons.Rounded.Key
                    firebaseReady -> Icons.Rounded.VerifiedUser
                    else -> Icons.Rounded.WarningAmber
                }
                val statusTitle = when {
                    hasPersonalKey -> "Clé API Gemini personnelle active"
                    firebaseReady -> "Connexion via Firebase AI Logic"
                    else -> "Configuration Firebase manquante"
                }
                val statusSubtitle = when {
                    hasPersonalKey ->
                        "Les requêtes passent directement par l'API Gemini avec votre clé (Firebase App Check contourné). Fonctionne sur tout APK Debug ou Release."
                    firebaseReady ->
                        "Authentification via App Check. Si vous rencontrez une erreur 403 sur un APK Debug installé hors d'AI Studio, renseignez votre clé Gemini ci-dessous."
                    else ->
                        "Ajoutez votre clé API Gemini personnelle ci-dessous pour utiliser l'application immédiatement, ou recompilez avec app/google-services.json."
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = statusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Clé API Gemini personnelle (optionnelle)
            item {
                val savedKey = state.settings.personalGeminiApiKey
                val trimmedDraft = apiKeyInput.trim()
                val hasUnsavedChanges = trimmedDraft != savedKey
                val testState = state.apiKeyTestState

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
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
                                text = "Clé API Gemini personnelle (optionnelle)",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (savedKey.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = StudioSuccess.copy(alpha = 0.16f),
                                    contentColor = StudioSuccess
                                ) {
                                    Text(
                                        text = "Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Utile si vous installez l'APK Debug sur téléphone (évite l'erreur App Check 403). Créez une clé gratuite sur Google AI Studio (aistudio.google.com). La clé reste enregistrée uniquement sur cet appareil.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = {
                                apiKeyInput = it
                                if (testState.message != null) onClearApiKeyTestState()
                            },
                            label = { Text("Clé API Gemini (AIza…)") },
                            placeholder = { Text("Collez votre clé Google AI Studio") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showApiKey = !showApiKey },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("settings_toggle_api_key_visibility")
                                ) {
                                    Icon(
                                        imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showApiKey) "Masquer la clé API" else "Afficher la clé API"
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_gemini_api_key_input")
                        )

                        if (testState.isTesting || testState.message != null) {
                            val msgColor = when (testState.isSuccess) {
                                true -> StudioSuccess
                                false -> StudioError
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (testState.isTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                }
                                Text(
                                    text = testState.message.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = msgColor
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { onSavePersonalApiKey(trimmedDraft) },
                                enabled = hasUnsavedChanges && !testState.isTesting,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("settings_save_api_key_button")
                            ) {
                                Text("Enregistrer")
                            }
                            OutlinedButton(
                                onClick = { onTestPersonalApiKey(trimmedDraft) },
                                enabled = trimmedDraft.isNotEmpty() && !testState.isTesting,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("settings_test_api_key_button")
                            ) {
                                Text(if (testState.isTesting) "Test…" else "Tester")
                            }
                            if (apiKeyInput.isNotEmpty() || savedKey.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        apiKeyInput = ""
                                        onSavePersonalApiKey("")
                                    },
                                    enabled = !testState.isTesting,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("settings_clear_api_key_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Effacer la clé API personnelle",
                                        tint = StudioError
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Choix du modèle TTS
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Modèle TTS par défaut", style = MaterialTheme.typography.titleMedium)
                        TtsModelOption.entries.forEach { opt ->
                            val selected = state.settings.defaultModelId == opt.modelId
                            Surface(
                                onClick = { onUpdateSettings { it.copy(defaultModelId = opt.modelId) } },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.background.copy(alpha = 0.4f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(opt.labelFr, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(opt.descriptionFr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // Préférences studio & accessibilité
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Préférences Studio", style = MaterialTheme.typography.titleMedium)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                FilterChip(
                                    selected = true,
                                    onClick = { showVoiceMenu = true },
                                    label = { Text("Voix : ${state.settings.defaultVoice}") }
                                )
                                DropdownMenu(expanded = showVoiceMenu, onDismissRequest = { showVoiceMenu = false }) {
                                    VoiceCatalog.voices.forEach { v ->
                                        DropdownMenuItem(
                                            text = { Text("${v.name} (${v.genderFr})") },
                                            onClick = {
                                                onUpdateSettings { it.copy(defaultVoice = v.name) }
                                                showVoiceMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                            Box {
                                val curLang = LanguageOption.fromCode(state.settings.defaultLanguageCode)
                                FilterChip(
                                    selected = false,
                                    onClick = { showLangMenu = true },
                                    label = { Text("Langue : ${curLang.labelFr}") }
                                )
                                DropdownMenu(expanded = showLangMenu, onDismissRequest = { showLangMenu = false }) {
                                    LanguageOption.entries.forEach { l ->
                                        DropdownMenuItem(
                                            text = { Text(l.labelFr) },
                                            onClick = {
                                                onUpdateSettings { it.copy(defaultLanguageCode = l.code) }
                                                showLangMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Text("Thème de l'interface", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ThemeMode.DARK to "Bleu nuit",
                                ThemeMode.LIGHT to "Clair",
                                ThemeMode.SYSTEM to "Système"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = state.settings.themeMode == mode,
                                    onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                    label = { Text(label) }
                                )
                            }
                        }

                        SettingSwitchRow(
                            title = "Normalisation de la prononciation",
                            subtitle = "Convertit nombres, dates, heures, €/% et abréviations en toutes lettres.",
                            checked = state.settings.pronunciationNormalizationEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(pronunciationNormalizationEnabled = v) } }
                        )
                        SettingSwitchRow(
                            title = "Vibrations (retour haptique)",
                            subtitle = "Retour tactile court sur les actions clés et erreurs.",
                            checked = state.settings.hapticsEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(hapticsEnabled = v) } }
                        )
                        SettingSwitchRow(
                            title = "Son de fin de génération",
                            subtitle = "Signal discret lorsque le mixage final est prêt.",
                            checked = state.settings.completionSoundEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(completionSoundEnabled = v) } }
                        )
                    }
                }
            }

            // Dictionnaire de prononciation personnel
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Dictionnaire de prononciation", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Ajoutez vos noms propres, marques ou sigles. Ils sont appliqués automatiquement avant chaque synthèse.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = wordInput,
                                onValueChange = { wordInput = it },
                                label = { Text("Mot / sigle") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = phoneticInput,
                                onValueChange = { phoneticInput = it },
                                label = { Text("Se prononce") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = {
                                onAddDictionaryEntry(wordInput, phoneticInput)
                                wordInput = ""
                                phoneticInput = ""
                            },
                            enabled = wordInput.isNotBlank() && phoneticInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Ajouter au dictionnaire")
                        }

                        state.dictionary.forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${entry.originalWord} → ${entry.phoneticReplacement}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onDeleteDictionaryEntry(entry.id) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer cette règle", tint = StudioError)
                                }
                            }
                        }
                    }
                }
            }

            // Compteur d'utilisation réel
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Utilisation réelle", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Session : ${state.sessionUsage.apiCallsCount} appels TTS • ${state.sessionUsage.generatedCharactersCount} car. • ${state.sessionUsage.cachedHitsCount} hits cache",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Total cumulé : ${state.cumulativeUsage.apiCallsCount} appels • ${state.cumulativeUsage.generatedCharactersCount} caractères",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

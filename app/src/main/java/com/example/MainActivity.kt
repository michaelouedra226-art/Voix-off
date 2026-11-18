package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.ai.configureAppCheck
import com.example.audio.AudioExportFormat
import com.example.ui.VoxaStudioApp
import com.example.viewmodel.VoxaStudioViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VoxaStudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Étape 1 obligatoire : App Check avant tout affichage UI ou appel Firebase
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            var pendingExport by remember {
                mutableStateOf<Triple<AudioExportFormat, Boolean, com.example.domain.model.GenerationRecord?>?>(null)
            }
            var pendingZipBatch by remember {
                mutableStateOf<Triple<List<com.example.domain.model.GenerationRecord>, AudioExportFormat, Boolean>?>(null)
            }

            val exportWavLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("audio/wav")
            ) { uri ->
                val spec = pendingExport
                if (uri != null && spec != null) {
                    viewModel.exportAudioToUri(uri, spec.first, spec.second, spec.third)
                }
                pendingExport = null
            }

            val exportM4aLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("audio/mp4")
            ) { uri ->
                val spec = pendingExport
                if (uri != null && spec != null) {
                    viewModel.exportAudioToUri(uri, spec.first, spec.second, spec.third)
                }
                pendingExport = null
            }

            val exportSrtLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/x-subrip")
            ) { uri ->
                if (uri != null) {
                    viewModel.exportSrtToUri(uri)
                }
            }

            val exportZipLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip")
            ) { uri ->
                val batch = pendingZipBatch
                if (uri != null && batch != null) {
                    viewModel.exportBatchZipToUri(batch.first, batch.second, batch.third, uri)
                }
                pendingZipBatch = null
            }

            VoxaStudioApp(
                viewModel = viewModel,
                onExportAudioRequest = { format, voiceOnly, record ->
                    pendingExport = Triple(format, voiceOnly, record)
                    val suffix = if (voiceOnly) "_voix_seule" else ""
                    val rawTitle = record?.title?.ifBlank { null } ?: viewModel.uiState.value.projectTitle
                    val base = rawTitle
                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        .ifBlank { "voxa_studio" }
                    val filename = "$base$suffix.${format.extension}"
                    if (format == AudioExportFormat.WAV) {
                        exportWavLauncher.launch(filename)
                    } else {
                        exportM4aLauncher.launch(filename)
                    }
                },
                onExportSrtRequest = {
                    val base = viewModel.uiState.value.projectTitle
                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        .ifBlank { "voxa_studio" }
                    exportSrtLauncher.launch("$base.srt")
                },
                onBatchExportZipRequest = { records, format, voiceOnly ->
                    pendingZipBatch = Triple(records, format, voiceOnly)
                    exportZipLauncher.launch("voxa_studio_pack_${records.size}.zip")
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppInBackground(false)
        viewModel.refreshNetworkStatus()
    }

    override fun onStop() {
        super.onStop()
        viewModel.setAppInBackground(true)
    }
}

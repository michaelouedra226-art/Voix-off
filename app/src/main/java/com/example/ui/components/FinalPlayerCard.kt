package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Subtitles
import com.example.audio.AudioExportFormat
import com.example.audio.PlaybackState
import com.example.domain.model.AudioMetrics
import com.example.domain.model.LoudnessTarget
import com.example.domain.model.MixConfig
import com.example.domain.model.ReverbPreset
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.StudioWarning
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lecteur final studio avec forme d'onde interactive (clic, glissement, double-tap ±5s),
 * vumètre temps réel, mesures LUFS/Peak, mixage en direct et export WAV / M4A / Voix seule.
 */
@Composable
fun FinalPlayerCard(
    metrics: AudioMetrics,
    playback: PlaybackState,
    mixConfig: MixConfig,
    isVoiceOnlyPreview: Boolean,
    onTogglePlay: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onSeekDelta: (Float) -> Unit,
    onToggleVoiceOnly: (Boolean) -> Unit,
    onUpdateMixConfig: ((MixConfig) -> MixConfig) -> Unit,
    onExport: (AudioExportFormat, Boolean) -> Unit,
    onShare: (AudioExportFormat, Boolean) -> Unit,
    onExportSrt: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMixSliders by remember { mutableStateOf(false) }
    val isPlayingFinal = playback.activeTag == "final" && playback.isPlaying
    val currentSec = if (playback.activeTag == "final") playback.currentSec else 0f
    val totalSec = metrics.durationSec.coerceAtLeast(0.1f)
    val progress = (currentSec / totalSec).coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("final_player_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    ) {
                        IconButton(
                            onClick = onTogglePlay,
                            modifier = Modifier.testTag("final_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlayingFinal) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlayingFinal) "Mettre en pause" else "Lire l'audio final",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Master final",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${formatTime(currentSec)} / ${formatTime(totalSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Bouton mixage en direct & badge niveau
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = isVoiceOnlyPreview,
                        onClick = { onToggleVoiceOnly(!isVoiceOnlyPreview) },
                        label = { Text("Voix seule", style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.testTag("voice_only_toggle")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { showMixSliders = !showMixSliders },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("toggle_mix_sliders")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Réglages de mixage en direct",
                            tint = if (showMixSliders) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Forme d'onde cliquable / glissable / double-tap ±5s
            val primaryColor = MaterialTheme.colorScheme.primary
            val inactiveColor = MaterialTheme.colorScheme.outline
            val peaks = metrics.waveformPeaks

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                    .pointerInput(totalSec) {
                        detectTapGestures(
                            onTap = { offset ->
                                val ratio = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                onSeekTo(ratio * totalSec)
                            },
                            onDoubleTap = { offset ->
                                val isRight = offset.x > size.width / 2f
                                onSeekDelta(if (isRight) 5f else -5f)
                            }
                        )
                    }
                    .pointerInput(totalSec) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val ratio = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            onSeekTo(ratio * totalSec)
                        }
                    }
                    .semantics {
                        contentDescription = "Forme d'onde interactive. Glissez pour naviguer ou double-touchez à gauche ou à droite pour sauter de 5 secondes."
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    if (peaks.isNotEmpty()) {
                        val count = peaks.size
                        val slotWidth = size.width / count
                        val barWidth = (slotWidth * 0.62f).coerceAtLeast(2.dp.toPx())
                        val centerY = size.height / 2f
                        for (i in peaks.indices) {
                            val barRatio = i.toFloat() / count
                            val barColor = if (barRatio <= progress) primaryColor else inactiveColor
                            val h = (peaks[i] * size.height * 0.9f).coerceAtLeast(4.dp.toPx())
                            val x = i * slotWidth + (slotWidth - barWidth) / 2f
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(x, centerY - h / 2f),
                                size = Size(barWidth, h),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Glisser pour parcourir • Double-tap gauche/droite : ±5 s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Vumètre en direct + niveau final LUFS / Peak
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "VU",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val vuRatio = if (isPlayingFinal) playback.liveVuNormalized else 0f
                    val vuColor = when {
                        vuRatio > 0.92f -> MaterialTheme.colorScheme.error
                        vuRatio > 0.78f -> StudioWarning
                        else -> StudioSuccess
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(vuRatio)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(vuColor)
                    )
                }
                Text(
                    text = "${"%.1f".format(Locale.US, metrics.integratedLufs)} LUFS • Pic ${"%.1f".format(Locale.US, metrics.peakDb)} dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (metrics.isClippingRisk || metrics.isTooQuiet) {
                Spacer(modifier = Modifier.height(8.dp))
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (metrics.isClippingRisk) {
                                "Niveau élevé proche de la saturation : le limiteur -1 dB protège la sortie, mais vous pouvez baisser le volume voix."
                            } else {
                                "Audio final un peu faible : augmentez le curseur Volume voix ci-dessous."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Curseurs de mixage en direct (sans régénérer les voix)
            if (showMixSliders) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Mixage en direct (aperçu instantané sans rappel API)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        MixSliderRow(
                            label = "Volume voix",
                            valueText = "${(mixConfig.voiceVolume * 100).roundToInt()} %",
                            value = mixConfig.voiceVolume,
                            range = 0.4f..1.5f,
                            onChange = { v -> onUpdateMixConfig { it.copy(voiceVolume = v) } }
                        )
                        MixSliderRow(
                            label = "Volume ambiance",
                            valueText = "${(mixConfig.ambienceVolume * 100).roundToInt()} %",
                            value = mixConfig.ambienceVolume,
                            range = 0f..1.6f,
                            onChange = { v -> onUpdateMixConfig { it.copy(ambienceVolume = v) } }
                        )
                        MixSliderRow(
                            label = "Intensité ducking",
                            valueText = "-${mixConfig.duckingAmountDb.roundToInt()} dB",
                            value = mixConfig.duckingAmountDb,
                            range = 0f..18f,
                            onChange = { v -> onUpdateMixConfig { it.copy(duckingAmountDb = v) } }
                        )
                        MixSliderRow(
                            label = "Durée des fondus",
                            valueText = "${"%.1f".format(Locale.US, mixConfig.fadeDurationSec)} s",
                            value = mixConfig.fadeDurationSec,
                            range = 0.3f..3.0f,
                            onChange = { v -> onUpdateMixConfig { it.copy(fadeDurationSec = v) } }
                        )

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Post-traitement & Montage rapide",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        MixSliderRow(
                            label = "Couper le début (Trim)",
                            valueText = "${"%.1f".format(Locale.US, mixConfig.trimStartSec)} s",
                            value = mixConfig.trimStartSec,
                            range = 0f..5.0f,
                            onChange = { v -> onUpdateMixConfig { it.copy(trimStartSec = v) } }
                        )
                        MixSliderRow(
                            label = "Couper la fin (Trim)",
                            valueText = "${"%.1f".format(Locale.US, mixConfig.trimEndSec)} s",
                            value = mixConfig.trimEndSec,
                            range = 0f..5.0f,
                            onChange = { v -> onUpdateMixConfig { it.copy(trimEndSec = v) } }
                        )
                        MixSliderRow(
                            label = "Vitesse globale (sans rappel API)",
                            valueText = "${"%.2f".format(Locale.US, mixConfig.playbackSpeed)}x",
                            value = mixConfig.playbackSpeed,
                            range = 0.85f..1.20f,
                            onChange = { v -> onUpdateMixConfig { it.copy(playbackSpeed = v) } }
                        )

                        Text("Réverbération studio", style = MaterialTheme.typography.bodySmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            ReverbPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = mixConfig.reverbPreset == preset,
                                    onClick = { onUpdateMixConfig { it.copy(reverbPreset = preset) } },
                                    label = { Text(preset.labelFr) },
                                    modifier = Modifier.testTag("reverb_chip_${preset.name.lowercase()}")
                                )
                            }
                        }

                        Text("Normalisation cible", style = MaterialTheme.typography.bodySmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            LoudnessTarget.entries.forEach { target ->
                                val selected = abs(mixConfig.targetLufs - target.targetLufs) < 0.8f
                                FilterChip(
                                    selected = selected,
                                    onClick = { onUpdateMixConfig { it.copy(targetLufs = target.targetLufs) } },
                                    label = { Text(target.labelFr) },
                                    modifier = Modifier.testTag("lufs_chip_${target.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Actions d'export et de partage
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = { onExport(AudioExportFormat.WAV, isVoiceOnlyPreview) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("export_wav_button")
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("WAV", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = { onExport(AudioExportFormat.M4A, isVoiceOnlyPreview) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("export_m4a_button")
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("M4A", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = { onShare(AudioExportFormat.M4A, isVoiceOnlyPreview) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("share_audio_button")
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Partager", fontWeight = FontWeight.SemiBold)
                }
            }

            if (onExportSrt != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onExportSrt,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("export_srt_button")
                ) {
                    Icon(Icons.Rounded.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Exporter les sous-titres (.srt)")
                }
            }
        }
    }
}

@Composable
private fun MixSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

fun formatTime(seconds: Float): String {
    val s = seconds.coerceAtLeast(0f).roundToInt()
    val min = s / 60
    val rem = s % 60
    return "$min:${rem.toString().padStart(2, '0')}"
}

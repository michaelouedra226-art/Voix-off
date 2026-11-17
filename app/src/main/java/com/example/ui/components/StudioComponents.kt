package com.example.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.alpha
import com.example.domain.model.SegmentRuntimeState
import com.example.domain.model.SegmentStatus
import com.example.domain.model.TimelineEvent
import com.example.ui.theme.StudioError
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.StudioWarning
import com.example.ui.theme.TagAmbienceTurquoise
import com.example.ui.theme.TagPauseSlate
import com.example.ui.theme.TagSpeedGold
import com.example.ui.theme.TagToneOrange
import com.example.ui.theme.TagVoiceViolet
import com.example.viewmodel.GenerationPhase
import java.util.Locale
import kotlin.math.sin

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
fun VoxaPrimaryActionButton(
    text: String,
    isEnabled: Boolean,
    onDisabledTap: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "generate_audio_button"
) {
    val reducedMotion = rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.97f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "btn_scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Button(
            onClick = {
                if (isEnabled) onClick() else onDisabledTap()
            },
            interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isEnabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag(testTag)
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Bouton animé à forme d'onde vivante qui remplace le bouton Générer pendant le traitement,
 * avec affichage de l'étape et bouton Annuler.
 */
@Composable
fun LiveGenerationStatusCard(
    phase: GenerationPhase,
    currentSegment: Int,
    totalSegments: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    val infiniteTransition = rememberInfiniteTransition(label = "wave_gen")
    val animPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val phaseText = when (phase) {
        GenerationPhase.GENERATING_VOICES -> if (totalSegments > 0) {
            "Génération des voix ($currentSegment sur $totalSegments)"
        } else {
            phase.labelFr
        }
        else -> phase.labelFr
    }

    val progress = when (phase) {
        GenerationPhase.ANALYZING -> 0.12f
        GenerationPhase.SCRIPTING -> 0.25f
        GenerationPhase.GENERATING_VOICES -> {
            if (totalSegments > 0) 0.28f + 0.57f * (currentSegment.toFloat() / totalSegments)
            else 0.5f
        }
        GenerationPhase.MIXING -> 0.90f
        GenerationPhase.DONE -> 1.0f
        GenerationPhase.IDLE -> 0f
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Forme d'onde vivante
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        for (bar in 0 until 7) {
                            val hFactor = if (reducedMotion) {
                                0.55f
                            } else {
                                (0.35f + 0.65f * kotlin.math.abs(sin(animPhase + bar * 0.75f)))
                            }
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height((22f * hFactor).dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = phaseText,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Écran maintenu actif pendant le rendu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                    }
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("cancel_generation_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Annuler la génération",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
fun SkeletonBriefPlanCard(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val shimmerAlpha = if (reducedMotion) {
        0.35f
    } else {
        val t by rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.25f,
            targetValue = 0.65f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "alpha"
        )
        t
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = shimmerAlpha))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = shimmerAlpha * 0.8f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = shimmerAlpha * 0.8f))
            )
        }
    }
}

/**
 * Petite fiche d'aide à la syntaxe fermée par défaut (sans contenu d'exemple narratif ni publicité).
 */
@Composable
fun SyntaxHelpCollapsibleCard(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("syntax_help_toggle")
            ) {
                Icon(
                    imageVector = Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Comment écrire mes consignes",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Réduire l'aide" else "Afficher l'aide"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyntaxRow("[ton: ...]", "Modifie l'émotion ou le jeu d'acteur jusqu'au prochain changement.", TagToneOrange)
                    SyntaxRow("[voix: Nom]", "Change de narrateur parmi les 14 voix Gemini.", TagVoiceViolet)
                    SyntaxRow("[vitesse: lent | normal | rapide]", "Ajuste le rythme de lecture.", TagSpeedGold)
                    SyntaxRow("[pause: 1.5s]", "Insère un silence réel de 0,2 s à 10 s.", TagPauseSlate)
                    SyntaxRow("[ambiance: nom, 25%]", "Lance un fond sonore bouclé (20 % par défaut) avec fondu.", TagAmbienceTurquoise)
                    SyntaxRow("[ambiance: stop]", "Coupe l'ambiance en cours avec fondu de sortie.", TagAmbienceTurquoise)
                    Text(
                        text = "Raccourci : tout autre texte entre crochets est interprété comme une consigne de ton libre. Les crochets ne sont jamais lus à voix haute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SyntaxRow(syntax: String, desc: String, badgeColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = syntax,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StudioGradientBackdrop(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.09f),
                    secondary.copy(alpha = 0.05f),
                    bg
                ),
                center = Offset(180f, 120f),
                radius = 900f
            )
        )
    )
}

@Composable
fun EmptyStudioBadge(
    icon: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
    },
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(22.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                icon()
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SegmentTimelineCard(
    segment: TimelineEvent.VoiceSegment,
    runtime: SegmentRuntimeState?,
    isPlayingSolo: Boolean,
    isCurrentlyActiveInFinal: Boolean,
    onPlaySolo: () -> Unit,
    onRegenerate: () -> Unit,
    onAnotherTake: () -> Unit,
    onSelectTake: (Int) -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val status = runtime?.status ?: SegmentStatus.PENDING

    val pulseAlpha = if (status == SegmentStatus.GENERATING && !reducedMotion) {
        val alpha by rememberInfiniteTransition(label = "seg_pulse").animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "alpha"
        )
        alpha
    } else 1f

    val borderColor = when {
        isCurrentlyActiveInFinal -> MaterialTheme.colorScheme.primary
        status == SegmentStatus.ERROR -> StudioError
        status == SegmentStatus.GENERATING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isCurrentlyActiveInFinal) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (isCurrentlyActiveInFinal) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(pulseAlpha)
            .testTag("segment_card_${segment.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "#${segment.index}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = segment.voice,
                        style = MaterialTheme.typography.labelMedium,
                        color = TagVoiceViolet
                    )
                    Text(
                        text = "• ${segment.tone}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TagToneOrange,
                        maxLines = 1
                    )
                }

                // Indicateur de statut + cache + durée
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val dur = runtime?.activeTake?.durationSec ?: segment.estimatedDurationSec
                    Text(
                        text = "${"%.1f".format(Locale.US, dur)} s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (status) {
                        SegmentStatus.PENDING -> Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = "En attente",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        SegmentStatus.GENERATING -> Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "En cours",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        SegmentStatus.READY -> {
                            if (runtime?.fromCache == true) {
                                Icon(
                                    Icons.Rounded.Cached,
                                    contentDescription = "Depuis le cache",
                                    tint = TagAmbienceTurquoise,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "Terminé",
                                    tint = StudioSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        SegmentStatus.ERROR -> Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = "Erreur",
                            tint = StudioError,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )

            // Alerte défaut détecté
            runtime?.activeTake?.defect?.let { defect ->
                Text(
                    text = "Défaut détecté : ${defect.labelFr}. Essayez de régénérer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioWarning
                )
            }

            // Message d'erreur du segment
            if (status == SegmentStatus.ERROR && runtime?.errorMessage != null) {
                Text(
                    text = runtime.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioError
                )
            }

            // Sélecteur de prises multiples si > 1 prise
            val takes = runtime?.takes.orEmpty()
            if (takes.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    takes.forEachIndexed { idx, _ ->
                        FilterChip(
                            selected = idx == runtime?.activeTakeIndex,
                            onClick = { onSelectTake(idx) },
                            label = { Text("Prise ${idx + 1}") }
                        )
                    }
                }
            }

            // Actions ▶ solo / ↻ régénérer / Autre prise
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPlaySolo,
                    enabled = runtime?.activeTake != null,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(
                        imageVector = if (isPlayingSolo) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Écouter ce segment",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Écouter")
                }

                OutlinedButton(
                    onClick = onRegenerate,
                    enabled = status != SegmentStatus.GENERATING,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Régénérer ce segment", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (status == SegmentStatus.ERROR) "Réessayer" else "Régénérer")
                }

                if (status == SegmentStatus.READY) {
                    TextButton(
                        onClick = onAnotherTake,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("Autre prise")
                    }
                }
            }
        }
    }
}

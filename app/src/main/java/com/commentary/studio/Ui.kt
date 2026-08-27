package com.commentary.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ---------------- colours ---------------- */

private val Accent = Color(0xFF00E5A0)
private val AccentDark = Color(0xFF008F63)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00120C),
    primaryContainer = Color(0xFF0E2A22),
    onPrimaryContainer = Accent,
    secondary = Color(0xFF9AA6B2),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFF2F4F7),
    surface = Color(0xFF14161A),
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1C1F24),
    onSurfaceVariant = Color(0xFFB6BDC7),
    outline = Color(0xFF2A2E35),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF1A0000)
)

private val LightScheme = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5E8),
    onPrimaryContainer = Color(0xFF00301F),
    secondary = Color(0xFF5B6672),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF0F1319),
    surface = Color.White,
    onSurface = Color(0xFF0F1319),
    surfaceVariant = Color(0xFFEBEEF2),
    onSurfaceVariant = Color(0xFF454C57),
    outline = Color(0xFFD8DCE3),
    error = Color(0xFFB3261E),
    onError = Color.White
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val Base = Typography()

private val AppTypography = Typography(
    displaySmall = Base.displaySmall.copy(
        fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp
    ),
    headlineMedium = Base.headlineMedium.copy(
        fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 31.sp
    ),
    titleMedium = Base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold, fontSize = 17.sp
    ),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
)

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}

/* ---------------- components ---------------- */

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
fun PrimaryAction(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .scale(if (pressed) 0.98f else 1f),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryAction(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.heightIn(min = 52.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun StepHeading(step: String, title: String, subtitle: String) {
    Column {
        Text(
            step,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatusRow(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
fun Skeleton(lines: Int = 3) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Column {
        repeat(lines) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f)
                    .height(14.dp)
                    .alpha(alpha)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(7.dp)
                    )
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * Animates only when the phase kind changes, so progress ticks do not flicker.
 */
@Composable
fun PhasePanel(
    phase: Phase,
    busyTitle: String,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    AnimatedContent(
        targetState = phase.kind,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "phase"
    ) { kind ->
        when (kind) {
            "idle" -> Spacer(Modifier.height(0.dp))

            "busy" -> {
                val busy = phase as? Phase.Busy
                Column {
                    StatusRow(
                        Icons.Outlined.Refresh,
                        busyTitle,
                        MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        busy?.label ?: "Working",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    val p = busy?.progress
                    if (p != null) {
                        LinearProgressIndicator(
                            progress = { p.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            strokeCap = StrokeCap.Round
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(p.coerceIn(0f, 1f) * 100).toInt()}% complete",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            strokeCap = StrokeCap.Round
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Skeleton(3)
                    SecondaryAction("Cancel", onClick = onCancel)
                }
            }

            "done" -> StatusRow(
                Icons.Filled.CheckCircle,
                "Complete",
                MaterialTheme.colorScheme.primary
            )

            else -> {
                val err = phase as? Phase.Error
                Column {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    err?.message ?: "Something went wrong.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryAction("Retry", Icons.Outlined.Refresh, onClick = onRetry)
                }
            }
        }
    }
}

@Composable
fun BigTextBox(
    value: String,
    placeholder: String,
    onChange: ((String) -> Unit)? = null,
    minHeight: Int = 260
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange?.invoke(it) },
        readOnly = onChange == null,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(18.dp).size(30.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

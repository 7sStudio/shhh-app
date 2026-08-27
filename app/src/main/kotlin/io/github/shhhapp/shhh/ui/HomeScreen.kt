package io.github.shhhapp.shhh.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.core.TimeFormat

@Composable
fun HomeScreen(
    quiet: Boolean,
    hasDndAccess: Boolean,
    canScheduleExact: Boolean,
    timerEndMillis: Long,
    settings: ShhhSettings,
    settingsRevision: Int,
    onToggle: () -> Unit,
    onHushFor: (Long) -> Unit,
    onEndTimer: () -> Unit,
    onQuietHoursToggled: (Boolean) -> Unit,
    onRequestExactAlarmAccess: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showExactAlarmDialog by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings_title)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            QuietToggle(quiet = quiet, enabled = hasDndAccess, onToggle = onToggle)

            Spacer(Modifier.height(18.dp))

            AnimatedContent(targetState = quiet, label = "status") { isQuiet ->
                Text(
                    text = stringResource(
                        if (isQuiet) R.string.status_quiet_on else R.string.status_quiet_off
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))

            if (!hasDndAccess) {
                PermissionCard()
                Spacer(Modifier.height(16.dp))
            }

            AnimatedVisibility(visible = timerEndMillis > 0) {
                Column {
                    CountdownCard(timerEndMillis, onEndTimer)
                    Spacer(Modifier.height(16.dp))
                }
            }

            TimerChips(
                enabled = hasDndAccess,
                onHushFor = { minutes ->
                    if (canScheduleExact) onHushFor(minutes) else showExactAlarmDialog = true
                }
            )

            Spacer(Modifier.height(16.dp))

            // Recompose the summary when persisted settings change outside Compose.
            androidx.compose.runtime.key(settingsRevision) {
                QuietHoursCard(
                    settings = settings,
                    onToggled = { enabled ->
                        if (enabled && !canScheduleExact) {
                            showExactAlarmDialog = true
                        } else {
                            onQuietHoursToggled(enabled)
                        }
                    },
                    onOpen = onOpenSettings
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showExactAlarmDialog) {
        ExactAlarmDialog(
            onGrant = {
                showExactAlarmDialog = false
                onRequestExactAlarmAccess()
            },
            onDismiss = { showExactAlarmDialog = false }
        )
    }
}

@Composable
internal fun ExactAlarmDialog(onGrant: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exact_alarm_title)) },
        text = { Text(stringResource(R.string.exact_alarm_body)) },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.exact_alarm_grant))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun QuietToggle(quiet: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Expressive shape morph: a circle when sound is on, a squircle when hushed.
    val corner by animateDpAsState(
        targetValue = if (quiet) 48.dp else 110.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "corner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "scale"
    )
    val container by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
            quiet -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        label = "container"
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            quiet -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        },
        label = "content"
    )

    Box(
        modifier = Modifier
            .size(220.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(corner))
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            },
        contentAlignment = Alignment.Center
    ) {
        // Springy scale+fade swap between the speaker and the vibrating phone.
        AnimatedContent(
            targetState = quiet,
            transitionSpec = {
                (scaleIn(
                    initialScale = 0.6f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn()) togetherWith (scaleOut(targetScale = 0.7f) + fadeOut())
            },
            label = "glyph"
        ) { isQuiet ->
            Icon(
                painter = painterResource(
                    if (isQuiet) R.drawable.ic_vibration else R.drawable.ic_volume_up
                ),
                contentDescription = stringResource(R.string.tile_content_description),
                modifier = Modifier.size(96.dp),
                tint = content
            )
        }
    }
}

@Composable
private fun CountdownCard(endMillis: Long, onEndTimer: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val endText = remember(endMillis, context, configuration) {
        TimeFormat.epochMillis(context, endMillis)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.timer_running_until, endText),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onEndTimer) {
                Text(stringResource(R.string.timer_end_now))
            }
        }
    }
}

@Composable
private fun TimerChips(enabled: Boolean, onHushFor: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.timer_section_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.timer_section_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DurationChip(R.string.timer_chip_15, 15, enabled, onHushFor, Modifier.weight(1f))
            DurationChip(R.string.timer_chip_30, 30, enabled, onHushFor, Modifier.weight(1f).testTag("chip_30"))
            DurationChip(R.string.timer_chip_60, 60, enabled, onHushFor, Modifier.weight(1f))
            DurationChip(R.string.timer_chip_120, 120, enabled, onHushFor, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DurationChip(
    labelRes: Int,
    minutes: Long,
    enabled: Boolean,
    onHushFor: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(enabled = enabled) { onHushFor(minutes) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(labelRes), maxLines = 1, modifier = Modifier.testTag("text_$minutes"))
    }
}

@Composable
private fun QuietHoursCard(
    settings: ShhhSettings,
    onToggled: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    val enabled = settings.quietHoursEnabled
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.quiet_hours_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${formatMinutes(settings.quietStartMinutes)} – " +
                        "${formatMinutes(settings.quietEndMinutes)} · " +
                        daysSummary(
                            LocalContext.current,
                            settings,
                            LocalConfiguration.current.locales[0]
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggled)
        }
    }
}

@Composable
private fun PermissionCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                )
            }) {
                Text(stringResource(R.string.permission_button))
            }
        }
    }
}

/**
 * Minutes-after-midnight as a clock face. Reading [LocalConfiguration] is what
 * makes every readout re-format when the language or the 24-hour setting
 * changes under the app.
 */
@Composable
internal fun formatMinutes(minutesOfDay: Int): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(minutesOfDay, context, configuration) {
        TimeFormat.minutesOfDay(context, minutesOfDay)
    }
}

internal fun daysSummary(
    context: Context,
    settings: ShhhSettings,
    locale: java.util.Locale
): String {
    val days = settings.quietDays
    if (days.size == 7) return context.getString(R.string.quiet_days_every_day)
    return java.time.DayOfWeek.entries
        .filter { it in days }
        .joinToString(", ") {
            it.getDisplayName(java.time.format.TextStyle.SHORT, locale)
        }
        .ifEmpty { context.getString(R.string.quiet_days_none) }
}

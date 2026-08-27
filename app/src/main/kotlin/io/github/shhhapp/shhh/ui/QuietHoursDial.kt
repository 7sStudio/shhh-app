package io.github.shhhapp.shhh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.shhhapp.shhh.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bedtime-style 24-hour schedule dial: the quiet window is an arc on a clock
 * ring, with a draggable handle at each end (vibrate icon = start, speaker
 * icon = end). Midnight is at the top, time flows clockwise. The times in the
 * center are clickable for precise entry via the clock dialog.
 */
@Composable
fun QuietHoursDial(
    startMinutes: Int,
    endMinutes: Int,
    onChange: (startMinutes: Int, endMinutes: Int) -> Unit,
    onChangeFinished: () -> Unit,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ringWidth = 26.dp
    val handleRadius = 20.dp
    val dialSize = 300.dp

    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val arcColor = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.primary
    val onHandleColor = MaterialTheme.colorScheme.onPrimary
    val tickColor = MaterialTheme.colorScheme.outlineVariant

    val density = LocalDensity.current
    val currentStart by rememberUpdatedState(startMinutes)
    val currentEnd by rememberUpdatedState(endMinutes)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinished by rememberUpdatedState(onChangeFinished)
    var dragging by remember { mutableStateOf<Handle?>(null) }

    Box(
        modifier = modifier.size(dialSize),
        contentAlignment = Alignment.Center
    ) {
        val radiusPx = with(density) { (dialSize / 2 - handleRadius).toPx() }
        val centerPx = with(density) { (dialSize / 2).toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { position ->
                            val touched = DialGeometry.pointToMinutes(
                                position.x - centerPx, position.y - centerPx
                            )
                            dragging = if (
                                DialGeometry.circularDistance(touched, currentStart) <=
                                DialGeometry.circularDistance(touched, currentEnd)
                            ) Handle.START else Handle.END
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val minutes = DialGeometry.pointToMinutes(
                                change.position.x - centerPx, change.position.y - centerPx
                            )
                            when (dragging) {
                                Handle.START -> currentOnChange(minutes, currentEnd)
                                Handle.END -> currentOnChange(currentStart, minutes)
                                null -> Unit
                            }
                        },
                        onDragEnd = {
                            dragging = null
                            currentOnFinished()
                        },
                        onDragCancel = {
                            dragging = null
                            currentOnFinished()
                        }
                    )
                }
        ) {
            val center = Offset(centerPx, centerPx)

            // Track ring.
            drawCircle(
                color = trackColor,
                radius = radiusPx,
                center = center,
                style = Stroke(width = ringWidth.toPx())
            )

            // Hour ticks (dots), skipping under the labels' quadrant markers.
            repeat(24) { hour ->
                val angle = Math.toRadians(
                    DialGeometry.minutesToAngle(hour * 60).toDouble()
                )
                val tickRadius = radiusPx
                drawCircle(
                    color = tickColor,
                    radius = if (hour % 6 == 0) 3.dp.toPx() else 1.5.dp.toPx(),
                    center = Offset(
                        center.x + tickRadius * cos(angle).toFloat(),
                        center.y + tickRadius * sin(angle).toFloat()
                    )
                )
            }

            // The quiet window arc.
            val arcTopLeft = Offset(center.x - radiusPx, center.y - radiusPx)
            drawArc(
                color = arcColor,
                startAngle = DialGeometry.minutesToAngle(currentStart),
                sweepAngle = DialGeometry.sweepAngle(currentStart, currentEnd),
                useCenter = false,
                topLeft = arcTopLeft,
                size = androidx.compose.ui.geometry.Size(radiusPx * 2, radiusPx * 2),
                style = Stroke(width = ringWidth.toPx(), cap = StrokeCap.Round)
            )
        }

        // Orientation labels just inside the ring.
        DialLabel(stringResource(R.string.dial_label_12am), Alignment.TopCenter, Modifier.padding(top = 44.dp))
        DialLabel(stringResource(R.string.dial_label_6am), Alignment.CenterEnd, Modifier.padding(end = 44.dp))
        DialLabel(stringResource(R.string.dial_label_12pm), Alignment.BottomCenter, Modifier.padding(bottom = 44.dp))
        DialLabel(stringResource(R.string.dial_label_6pm), Alignment.CenterStart, Modifier.padding(start = 44.dp))

        // Handles: positioned composables so we can use real vector icons.
        HandleIcon(
            minutes = startMinutes,
            radiusPx = radiusPx,
            handleRadius = handleRadius,
            containerColor = handleColor,
            contentColor = onHandleColor,
            iconRes = R.drawable.ic_vibration,
            contentDescription = stringResource(R.string.quiet_hours_starts)
        )
        HandleIcon(
            minutes = endMinutes,
            radiusPx = radiusPx,
            handleRadius = handleRadius,
            containerColor = handleColor,
            contentColor = onHandleColor,
            iconRes = R.drawable.ic_volume_up,
            contentDescription = stringResource(R.string.quiet_hours_ends)
        )

        // Center readout.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CenterTime(
                label = stringResource(R.string.quiet_hours_starts),
                minutes = startMinutes,
                onClick = onStartClick
            )
            Spacer(Modifier.height(10.dp))
            CenterTime(
                label = stringResource(R.string.quiet_hours_ends),
                minutes = endMinutes,
                onClick = onEndClick
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = formatDuration(startMinutes, endMinutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private enum class Handle { START, END }

@Composable
private fun formatDuration(start: Int, end: Int): String {
    val total = DialGeometry.windowMinutes(start, end)
    val hours = total / 60
    val minutes = total % 60
    return when {
        minutes == 0 -> stringResource(R.string.duration_hours, hours)
        hours == 0 -> stringResource(R.string.duration_minutes, minutes)
        else -> stringResource(R.string.duration_full, hours, minutes)
    }
}

@Composable
private fun BoxScope.DialLabel(
    text: String,
    alignment: Alignment,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .align(alignment)
            .then(modifier)
    )
}

@Composable
private fun HandleIcon(
    minutes: Int,
    radiusPx: Float,
    handleRadius: androidx.compose.ui.unit.Dp,
    containerColor: Color,
    contentColor: Color,
    iconRes: Int,
    contentDescription: String
) {
    val angle = Math.toRadians(DialGeometry.minutesToAngle(minutes).toDouble())
    val density = LocalDensity.current
    val offset = with(density) {
        IntOffset(
            (radiusPx * cos(angle)).roundToInt(),
            (radiusPx * sin(angle)).roundToInt()
        )
    }
    Box(
        modifier = Modifier
            .offset { offset }
            .size(handleRadius * 2)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color = containerColor)
        }
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CenterTime(label: String, minutes: Int, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = formatMinutes(minutes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

package io.github.shhhapp.shhh.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One entry in an [ExpressiveToggleGroup]: a label with its leading icon. */
data class ToggleOption(val label: String, val iconRes: Int)

/**
 * Material 3 Expressive connected button group (hand-built for API
 * stability): filled buttons with no outlines sitting almost flush; the
 * selected one is boldly filled in the primary color and morphs into a pill
 * with a springy corner animation — the pattern current Pixel apps use
 * instead of outlined segmented buttons. The fill/shape change alone marks
 * the selection; each option carries its own icon instead of a checkmark.
 */
@Composable
fun ExpressiveToggleGroup(
    options: List<ToggleOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val outerCorner = 26.dp
    val innerCorner = 8.dp
    val morph = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val isFirst = index == 0
            val isLast = index == options.lastIndex

            // Outer edges of the group are always fully rounded; inner edges
            // are square-ish until the button is selected and morphs to a pill.
            val startCorner by animateDpAsState(
                targetValue = if (selected || isFirst) outerCorner else innerCorner,
                animationSpec = morph,
                label = "startCorner"
            )
            val endCorner by animateDpAsState(
                targetValue = if (selected || isLast) outerCorner else innerCorner,
                animationSpec = morph,
                label = "endCorner"
            )
            // Unselected uses `surface`, not surfaceContainerHighest: the group
            // sits on a filled card whose color IS surfaceContainerHighest, so
            // that token would render the button invisible.
            val container by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                label = "container"
            )
            val content by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "content"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(
                        RoundedCornerShape(
                            topStart = startCorner,
                            bottomStart = startCorner,
                            topEnd = endCorner,
                            bottomEnd = endCorner
                        )
                    )
                    .background(container)
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(index) },
                        role = Role.RadioButton,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple()
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(
                        painter = painterResource(option.iconRes),
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = option.label,
                        color = content,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

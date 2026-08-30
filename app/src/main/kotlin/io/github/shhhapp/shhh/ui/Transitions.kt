package io.github.shhhapp.shhh.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/**
 * App-wide animation rule: nothing slides or expands in from an edge or
 * corner. Content appears with a quick in-place fade (or a centered scale,
 * like the toggle glyph) — or not at all. Use this pair for every
 * AnimatedVisibility / AnimatedContent instead of the library defaults,
 * whose expand-from-top-start reveal reads as flying in diagonally.
 */
val RevealEnter: EnterTransition = fadeIn(tween(durationMillis = 150))
val RevealExit: ExitTransition = fadeOut(tween(durationMillis = 150))

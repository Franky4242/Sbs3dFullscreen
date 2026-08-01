package fr.camera3d.camera.common.ui_components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Drop-in replacement for SnackbarHost with a slide-up entrance and slide-down exit.
 *
 * Why not AnimatedVisibility / AnimatedContent:
 *   Those composables derive the slide offset from the content's own measured size.
 *   When the content is null (no snackbar) the measured size is zero, so the
 *   animation has nothing to work with and produces no visible movement.
 *
 * This implementation instead drives an [Animatable] fraction (1 = off-screen below,
 * 0 = fully visible) entirely from a [LaunchedEffect] and applies the translation via
 * [Modifier.graphicsLayer].  graphicsLayer translates the *whole rendered layer*,
 * independently of layout, so the slide works regardless of container size.
 *
 * Auto-dismiss (normally handled by SnackbarHost's internal LaunchedEffect) is
 * replicated here so callers can use [SnackbarHostState] exactly as usual.
 */
@Composable
fun AnimatedSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val currentData = hostState.currentSnackbarData
    var snackbarData by remember { mutableStateOf<SnackbarData?>(null) }
    val fraction = remember { Animatable(1f) } // 1 = off-screen below, 0 = visible

    LaunchedEffect(currentData) {
        if (currentData != null) {
            snackbarData = currentData
            // Ensure we always start from off-screen, even if a previous animation was interrupted
            fraction.snapTo(1f)
            // Slide up
            fraction.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
            // Hold for the requested duration
            delay(
                when (currentData.visuals.duration) {
                    SnackbarDuration.Short -> 4_000L
                    SnackbarDuration.Long -> 10_000L
                    SnackbarDuration.Indefinite -> Long.MAX_VALUE
                }
            )
            // Slide down
            fraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutLinearInEasing)
            )
            currentData.dismiss()
        } else {
            snackbarData = null
        }
    }

    // Read fraction.value here in the composition phase so Compose tracks the state read
    // and triggers recomposition on every animation frame.  Reading inside graphicsLayer {}
    // happens in the draw phase where state observation is unreliable in the modifier node system.
    val translationFraction = fraction.value

    snackbarData?.let { data ->
        Snackbar(
            snackbarData = data,
            modifier = modifier.graphicsLayer {
                // size.height is the measured height of this layer in pixels.
                // translationFraction == 1  →  translationY == size.height  →  fully below screen
                // translationFraction == 0  →  translationY == 0            →  natural (visible) position
                translationY = translationFraction * size.height
            }
        )
    }
}

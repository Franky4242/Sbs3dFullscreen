import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Squeezes this composable's rendered width by 2 around [origin] when [active] - the actual
 * squeeze behind AppViewModel.shrinkControls, shared by every control it touches (the settings
 * menu, the raw/edited label, the info panel, and - via [Stereo3DAlertDialog] - every dialog box).
 * [origin] should match whichever corner/edge the control is visually pinned to (e.g. a
 * TopStart-anchored control wants `TransformOrigin(0f, 0f)`) so that edge stays fixed as the
 * control's own width changes (e.g. the settings menu expanding/collapsing) instead of the shrink
 * visibly sliding the control around. A no-op (not even a graphicsLayer allocated) when inactive.
 */
fun Modifier.shrinkHorizontally(active: Boolean, origin: TransformOrigin): Modifier =
    if (active) this.graphicsLayer { scaleX = 0.5f; scaleY = 1f; transformOrigin = origin } else this

/**
 * Renders [title]/[text]/[confirmButton]/[dismissButton] as a plain material3 [AlertDialog] when
 * [shrinkControls] is off (unchanged, existing behavior), or as [Stereo3DAlertDialog] when it's on.
 * A plain AlertDialog opens as its own native OS window - a single copy that only appears in one
 * eye on a Half-SBS 3D monitor, the same "single overlay reads as pinned to one eye" problem
 * CLAUDE.md describes for every other overlay in this app - so under "shrink controls" every
 * ImageScreen/InfoPanel confirmation dialog goes through this instead, keeping each dialog's own
 * title/text/buttons content shared between both renderings.
 */
@Composable
fun AdaptiveAlertDialog(
    shrinkControls: Boolean,
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    if (shrinkControls) {
        Stereo3DAlertDialog(onDismissRequest = onDismissRequest, title = title, text = text, confirmButton = confirmButton, dismissButton = dismissButton)
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton ?: {},
        )
    }
}

/**
 * In-scene stand-in for material3's AlertDialog, used by [AdaptiveAlertDialog] instead of it under
 * "shrink controls": duplicated per half (like every other overlay - see CLAUDE.md's "Stereo (SBS)
 * display and depth-shift overlays" note) and squeezed horizontally by 2 via [shrinkHorizontally],
 * same treatment as the rest of the shrunk controls. Centered rather than pinned to a corner, so
 * unlike those it doesn't need a depth shift or a non-center [TransformOrigin].
 *
 * Its buttons must go through [Modifier.cursor3DClickTarget] (like every other clickable overlay
 * control in this app) rather than a plain native onClick - Stereo3DCursorHost consumes every
 * pointer event before a real click/focus gesture would ever reach them (an earlier attempt at
 * pausing that consumption while a dialog is shown never actually worked in practice, so this
 * dialog now plays by the same rules as everything else). A [text] slot containing a TextField
 * still needs REAL keyboard focus to be typed into - see [StereoIssueCommentDialog] for how that's
 * done (auto-focus on appear, no click required) - but doesn't need real pointer routing itself.
 */
@Composable
fun Stereo3DAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f)) { Stereo3DAlertDialogHalf(onDismissRequest, title, text, confirmButton, dismissButton) }
        Box(Modifier.fillMaxSize().weight(1f)) { Stereo3DAlertDialogHalf(onDismissRequest, title, text, confirmButton, dismissButton) }
    }
}

@Composable
private fun Stereo3DAlertDialogHalf(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)?,
) {
    // Focused (and kept focused - see the doc above) purely so Escape below has an owner to
    // dispatch through; a text() slot's own TextField (see StereoIssueCommentDialog) steals this
    // right back via its own auto-focus LaunchedEffect, which runs after this one.
    val cardFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { cardFocusRequester.requestFocus() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .shrinkHorizontally(active = true, origin = TransformOrigin.Center)
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2B2B2B))
                .padding(20.dp)
                .focusRequester(cardFocusRequester)
                .focusable()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        onDismissRequest()
                        true
                    } else {
                        false
                    }
                },
        ) {
            CompositionLocalProvider(
                LocalContentColor provides Color.White,
                LocalTextStyle provides TextStyle(color = Color.White, fontSize = 14.sp),
            ) {
                Column {
                    CompositionLocalProvider(
                        LocalTextStyle provides TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    ) {
                        title()
                    }
                    Spacer(Modifier.height(8.dp))
                    text()
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

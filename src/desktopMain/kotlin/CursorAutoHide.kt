import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

private val hiddenCursor: PointerIcon by lazy {
    val blankImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(blankImage, Point(0, 0), "blank"))
}

private const val CURSOR_HIDE_DELAY_MS = 2000L

/**
 * Hides the mouse cursor after [CURSOR_HIDE_DELAY_MS] of no pointer movement, and reveals it
 * again as soon as the pointer moves. Used on the fullscreen image/video screens, where a static
 * cursor left on screen is a distraction.
 */
@Composable
fun Modifier.autoHideCursor(): Modifier {
    var cursorVisible by remember { mutableStateOf(true) }
    var lastMoveTick by remember { mutableStateOf(0) }

    LaunchedEffect(lastMoveTick) {
        cursorVisible = true
        delay(CURSOR_HIDE_DELAY_MS)
        cursorVisible = false
    }

    return this
        .pointerHoverIcon(if (cursorVisible) PointerIcon.Default else hiddenCursor)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Move) {
                        lastMoveTick++
                    }
                }
            }
        }
}

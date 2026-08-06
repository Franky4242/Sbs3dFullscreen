import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.hypot

// Same sign convention as Exif3dInfoPanel's InfoPanelShiftPercent/Playlist's titleZPercent
// (negative = toward the viewer): a small negative shift makes the cursor read as floating just
// in front of the screen, matching a real pointer resting above the photo rather than pinned to
// the glass.
private const val CursorShiftPercent = -0.01f

private const val CURSOR_IDLE_HIDE_MS = 2000L
private val CursorRadius = 8.dp
private val CursorClickSlop = 6.dp

/**
 * Registry of the real (left-half) screen-space rectangles of every clickable overlay control
 * currently in composition, keyed by an opaque id. [Modifier.cursor3DClickTarget] registers into
 * this; [Stereo3DCursorHost] consults it to resolve a click.
 *
 * Plain mutable list rather than Compose state: it's only ever read from the raw pointer-event
 * loop below (never drives recomposition on its own), same spirit as Main.kt's
 * manualAlignKeyPressStart/Remainder maps.
 */
class CursorHitRegistry {
    private data class Target(val id: Long, var rect: Rect, val onClick: () -> Unit)

    private val targets = mutableListOf<Target>()
    private var nextId = 0L

    fun register(rect: Rect, onClick: () -> Unit): Long {
        val id = nextId++
        targets.add(Target(id, rect, onClick))
        return id
    }

    fun updateRect(id: Long, rect: Rect) {
        targets.find { it.id == id }?.rect = rect
    }

    fun unregister(id: Long) {
        targets.removeAll { it.id == id }
    }

    /** Most-recently-registered (topmost) target whose rect contains [position], if any. */
    fun hitTest(position: Offset): (() -> Unit)? = targets.lastOrNull { it.rect.contains(position) }?.onClick
}

val LocalCursorHitRegistry = compositionLocalOf<CursorHitRegistry?> { null }

/**
 * Marks a clickable overlay element (gear icon, a switch, a menu row, an Exif3dInfoPanel button,
 * ...) as a target the 3D cursor can trigger. Only the LEFT half's copy of each duplicated overlay
 * needs this - see [Stereo3DCursorHost] for why: every click is resolved against the left half's
 * logical position regardless of where the real mouse physically is, so registering the right
 * half's copies too would just add unreachable (never-hit) entries. Applying it uniformly to both
 * halves' copies anyway is harmless and simpler than threading a "is this the left half" flag
 * through every call site.
 *
 * The real Modifier.clickable()/Switch/Button on these elements still exists and still renders
 * normal visuals, but never actually fires: Stereo3DCursorHost consumes every pointer event before
 * it reaches them (see its pointerInput's PointerEventPass.Initial), so this registry is the only
 * path a click actually takes. [onClick] is read through a remembered holder kept fresh via
 * SideEffect, so a stale closure (e.g. capturing the previous photo's `file`) is never invoked even
 * though the registry entry itself is only created once.
 */
@Composable
fun Modifier.cursor3DClickTarget(onClick: () -> Unit): Modifier {
    val registry = LocalCursorHitRegistry.current ?: return this
    val latestOnClick = remember { mutableStateOf(onClick) }
    SideEffect { latestOnClick.value = onClick }
    var id by remember { mutableStateOf(-1L) }
    DisposableEffect(Unit) {
        onDispose { if (id != -1L) registry.unregister(id) }
    }
    return this.onGloballyPositioned { coords ->
        val rect = coords.boundsInWindow()
        if (id == -1L) {
            id = registry.register(rect) { latestOnClick.value() }
        } else {
            registry.updateRect(id, rect)
        }
    }
}

/**
 * Wraps [content] (an ImageScreen/VideoScreen's usual full-bleed stereo content) with a 3D mouse
 * cursor: the real OS cursor is hidden completely (same hidden-custom-cursor trick as the old
 * CursorAutoHide.autoHideCursor) and replaced by two small dots, one per half, offset apart by
 * [CursorShiftPercent] like every other overlay in this codebase - a single copy would only appear
 * in one eye and read as pinned to the screen glass rather than floating.
 *
 * The window is double-wide (left half + right half), but on a Full-SBS 3D monitor a Full-SBS
 * source is fused by the viewer into a single perceived image the size of ONE half (see CLAUDE.md's
 * "Stereo (SBS) display and depth-shift overlays" note). So the real mouse's raw window-x is
 * clamped into [0, halfWidth] before doing anything else with it: past the midline, the cursor just
 * pins at the right edge of the perceived image rather than jumping across it. Every click is then
 * resolved with that same clamped, left-half-local position against [CursorHitRegistry] - never
 * against wherever the real OS pointer physically landed - so it always matches what's actually
 * rendered on screen, even when the real mouse has strayed into the right half.
 *
 * When [cropDragActive] is true (see Exif3dInfoPanel's Crop button / ImageScreen's crop-drawing
 * state), press-drag-release is routed to [onCropDragChange] (fired on every press/move, with the
 * live start/current clamped left-half-local position, and `null, null` on release/exit) and
 * [onCropDragEnd] (fired once on release with the final start/end position) instead of the normal
 * click-vs-drag-slop/[CursorHitRegistry] resolution - drawing a crop rectangle needs the raw drag,
 * not a click. Both callbacks are read through a SideEffect-refreshed holder, same rationale as
 * [Modifier.cursor3DClickTarget]'s latestOnClick.
 */
@Composable
fun Stereo3DCursorHost(
    cropDragActive: Boolean = false,
    onCropDragChange: (start: Offset?, current: Offset?) -> Unit = { _, _ -> },
    onCropDragEnd: (start: Offset, end: Offset) -> Unit = { _, _ -> },
    content: @Composable () -> Unit,
) {
    val registry = remember { CursorHitRegistry() }
    val density = LocalDensity.current
    val latestCropDragActive = remember { mutableStateOf(cropDragActive) }
    SideEffect { latestCropDragActive.value = cropDragActive }
    val latestOnCropDragChange = remember { mutableStateOf(onCropDragChange) }
    SideEffect { latestOnCropDragChange.value = onCropDragChange }
    val latestOnCropDragEnd = remember { mutableStateOf(onCropDragEnd) }
    SideEffect { latestOnCropDragEnd.value = onCropDragEnd }
    CompositionLocalProvider(LocalCursorHitRegistry provides registry) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val halfWidthDp = maxWidth / 2
            var halfWidthPx by remember { mutableStateOf(0f) }
            SideEffect { halfWidthPx = with(density) { halfWidthDp.toPx() } }

            // Left-half-local logical position (already clamped, see the doc above), or null
            // while the pointer hasn't been seen yet / has left the window.
            var cursorPos by remember { mutableStateOf<Offset?>(null) }
            var cursorVisible by remember { mutableStateOf(false) }
            var lastMoveTick by remember { mutableStateOf(0) }

            LaunchedEffect(lastMoveTick) {
                if (cursorPos == null) return@LaunchedEffect
                cursorVisible = true
                delay(CURSOR_IDLE_HIDE_MS)
                cursorVisible = false
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerHoverIcon(hiddenCursor)
                    .pointerInput(Unit) {
                        val clickSlopPx = CursorClickSlop.toPx()
                        var pressStart: Offset? = null
                        var cropDragStart: Offset? = null
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    val logical = Offset(change.position.x.coerceIn(0f, halfWidthPx), change.position.y)
                                    val cropActive = latestCropDragActive.value
                                    when (event.type) {
                                        PointerEventType.Move -> {
                                            cursorPos = logical
                                            lastMoveTick++
                                            if (cropActive && cropDragStart != null) {
                                                latestOnCropDragChange.value(cropDragStart, logical)
                                            }
                                        }
                                        PointerEventType.Press -> {
                                            cursorPos = logical
                                            lastMoveTick++
                                            if (cropActive) {
                                                cropDragStart = logical
                                                latestOnCropDragChange.value(logical, logical)
                                            } else {
                                                pressStart = logical
                                            }
                                        }
                                        PointerEventType.Release -> {
                                            if (cropActive) {
                                                val start = cropDragStart
                                                cropDragStart = null
                                                latestOnCropDragChange.value(null, null)
                                                if (start != null) latestOnCropDragEnd.value(start, logical)
                                            } else {
                                                val start = pressStart
                                                pressStart = null
                                                if (start != null && hypot((logical.x - start.x).toDouble(), (logical.y - start.y).toDouble()) <= clickSlopPx) {
                                                    registry.hitTest(logical)?.invoke()
                                                }
                                            }
                                        }
                                        PointerEventType.Exit -> {
                                            cursorPos = null
                                            pressStart = null
                                            if (cropDragStart != null) {
                                                cropDragStart = null
                                                latestOnCropDragChange.value(null, null)
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                                // Consumed here (before any descendant - gear icon, switches,
                                // Exif3dInfoPanel buttons, ... - sees it via the default Main
                                // pass) so the real per-half duplicated controls never fire
                                // natively: every click is instead resolved above via
                                // registry.hitTest() at the clamped left-half-local position. See
                                // Modifier.cursor3DClickTarget's doc for why that's necessary.
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            ) {
                content()
                val pos = cursorPos
                if (cursorVisible && pos != null) {
                    StereoCursorOverlay(pos, halfWidthDp)
                }
            }
        }
    }
}

@Composable
private fun StereoCursorOverlay(localPos: Offset, halfWidthDp: Dp) {
    val shift = halfWidthDp * CursorShiftPercent
    val density = LocalDensity.current
    val xDp = with(density) { localPos.x.toDp() }
    val yDp = with(density) { localPos.y.toDp() }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f)) { CursorDot(xDp - shift / 2, yDp) }
        Box(Modifier.fillMaxSize().weight(1f)) { CursorDot(xDp + shift / 2, yDp) }
    }
}

@Composable
private fun CursorDot(xDp: Dp, yDp: Dp) {
    Box(
        Modifier
            .offset(x = xDp - CursorRadius, y = yDp - CursorRadius)
            .size(CursorRadius * 2)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.9f))
            .border(1.5.dp, Color.Black.copy(alpha = 0.8f), CircleShape),
    )
}

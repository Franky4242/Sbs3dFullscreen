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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
 * Registry of scrub-bar-like overlay targets (currently just VideoScreen's progress bar) that
 * resolve a press+drag to a `[0,1]` fraction across the target's own rect, rather than a plain
 * click. [Modifier.cursor3DScrubTarget] registers into this; [Stereo3DCursorHost] consults it
 * before falling back to [CursorHitRegistry]/[rectDragActive] resolution, same left-half-only
 * registration convention as [CursorHitRegistry] (see [Modifier.cursor3DClickTarget]'s doc).
 */
class CursorScrubRegistry {
    data class Hit(val rect: Rect, val onScrub: (Float) -> Unit, val onScrubEnd: (Float) -> Unit)
    private data class Target(val id: Long, var rect: Rect, val onScrub: (Float) -> Unit, val onScrubEnd: (Float) -> Unit)

    private val targets = mutableListOf<Target>()
    private var nextId = 0L

    fun register(rect: Rect, onScrub: (Float) -> Unit, onScrubEnd: (Float) -> Unit): Long {
        val id = nextId++
        targets.add(Target(id, rect, onScrub, onScrubEnd))
        return id
    }

    fun updateRect(id: Long, rect: Rect) {
        targets.find { it.id == id }?.rect = rect
    }

    fun unregister(id: Long) {
        targets.removeAll { it.id == id }
    }

    fun hitTest(position: Offset): Hit? =
        targets.lastOrNull { it.rect.contains(position) }?.let { Hit(it.rect, it.onScrub, it.onScrubEnd) }
}

val LocalCursorScrubRegistry = compositionLocalOf<CursorScrubRegistry?> { null }

/**
 * Registry of text-field-like overlay targets (currently just [StereoBlinkingCaretTextField]) that
 * resolve a press to a caret position rather than a plain click. [Modifier.cursor3DTextClickTarget]
 * registers into this with a callback that receives the press position local to the registered
 * rect's own top-left; [Stereo3DCursorHost] consults it on press, same left-half-only registration
 * convention as [CursorHitRegistry]/[CursorScrubRegistry].
 */
class CursorTextClickRegistry {
    data class Hit(val rect: Rect, val onClick: (Offset) -> Unit)
    private data class Target(val id: Long, var rect: Rect, val onClick: (Offset) -> Unit)

    private val targets = mutableListOf<Target>()
    private var nextId = 0L

    fun register(rect: Rect, onClick: (Offset) -> Unit): Long {
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

    fun hitTest(position: Offset): Hit? =
        targets.lastOrNull { it.rect.contains(position) }?.let { Hit(it.rect, it.onClick) }
}

val LocalCursorTextClickRegistry = compositionLocalOf<CursorTextClickRegistry?> { null }

/** Whether the 3D cursor dots are currently shown (see [Stereo3DCursorHost]'s idle-hide timer). Lets
 * content (e.g. VideoScreen's progress bar) show/hide overlays in sync with the cursor instead of
 * running its own separate idle timer. */
val LocalCursorVisible = compositionLocalOf { false }

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
 * Marks an overlay element (currently just VideoScreen's progress bar track) as a scrub target:
 * a press anywhere on it starts dragging a handle along it, reporting the `[0,1]` fraction of the
 * press/drag position across the target's own width. [onScrub] fires on press and every subsequent
 * move (live preview, e.g. moving a handle and/or updating a "currently displayed" position);
 * [onScrubEnd] fires once on release (commit the seek). Both are read through SideEffect-refreshed
 * holders, same rationale as [Modifier.cursor3DClickTarget]'s latestOnClick.
 *
 * Like [Modifier.cursor3DClickTarget], only the LEFT half's copy needs this in a stereo-duplicated
 * overlay - applying it to both halves' copies anyway is harmless, see that function's doc.
 */
@Composable
fun Modifier.cursor3DScrubTarget(onScrub: (Float) -> Unit, onScrubEnd: (Float) -> Unit): Modifier {
    val registry = LocalCursorScrubRegistry.current ?: return this
    val latestOnScrub = remember { mutableStateOf(onScrub) }
    SideEffect { latestOnScrub.value = onScrub }
    val latestOnScrubEnd = remember { mutableStateOf(onScrubEnd) }
    SideEffect { latestOnScrubEnd.value = onScrubEnd }
    var id by remember { mutableStateOf(-1L) }
    DisposableEffect(Unit) {
        onDispose { if (id != -1L) registry.unregister(id) }
    }
    return this.onGloballyPositioned { coords ->
        val rect = coords.boundsInWindow()
        if (id == -1L) {
            id = registry.register(rect, { latestOnScrub.value(it) }, { latestOnScrubEnd.value(it) })
        } else {
            registry.updateRect(id, rect)
        }
    }
}

/**
 * Marks an overlay text field (currently just [StereoBlinkingCaretTextField]'s decoration box) as a
 * target whose press position should drive caret placement instead of a plain click - a real
 * TextField would place the caret under the pointer itself, but under "shrink controls" every press
 * is consumed by [Stereo3DCursorHost] before it ever reaches the field (see
 * [Modifier.cursor3DClickTarget]'s doc), so without this the field's own caret-from-tap logic never
 * runs at all and the caret silently stays wherever the field's [TextFieldValue.selection] happens
 * to already be. [onClick] receives the press position local to this element's own top-left (not
 * window space), read through a SideEffect-refreshed holder, same rationale as
 * [Modifier.cursor3DClickTarget]'s latestOnClick.
 */
@Composable
fun Modifier.cursor3DTextClickTarget(onClick: (Offset) -> Unit): Modifier {
    val registry = LocalCursorTextClickRegistry.current ?: return this
    val latestOnClick = remember { mutableStateOf(onClick) }
    SideEffect { latestOnClick.value = onClick }
    var id by remember { mutableStateOf(-1L) }
    DisposableEffect(Unit) {
        onDispose { if (id != -1L) registry.unregister(id) }
    }
    return this.onGloballyPositioned { coords ->
        val rect = coords.boundsInWindow()
        if (id == -1L) {
            id = registry.register(rect) { latestOnClick.value(it) }
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
 * When [rectDragActive] is true (see Exif3dInfoPanel's Crop/"Spot stereo issues" buttons and
 * ImageScreen's cropMode/spotIssuesMode drawing state - both draw a rectangle by drag, so they
 * share this one mechanism), a press that does NOT land on a registered [CursorHitRegistry] target
 * starts a drag routed to [onRectDragChange] (fired on every press/move, with the live
 * start/current clamped left-half-local position, and `null, null` on release/exit) and
 * [onRectDragEnd] (fired once on release with the final start/end position) instead of the normal
 * click-vs-drag-slop resolution - drawing a rectangle needs the raw drag, not a click. A press that
 * DOES land on a registered target (Exif3dInfoPanel's Cancel/Save buttons, most commonly) is always
 * resolved as an ordinary click instead, regardless of [rectDragActive] - otherwise those buttons
 * would be permanently unreachable while a drag tool stays active for multiple rectangles (see
 * spotIssuesMode, which - unlike cropMode - doesn't turn [rectDragActive] off after the first
 * rectangle). Both drag callbacks are read through a SideEffect-refreshed holder, same rationale as
 * [Modifier.cursor3DClickTarget]'s latestOnClick. Unlike the click registry, which tool is active
 * (if any) is entirely the caller's responsibility - ImageScreen.kt dispatches
 * [onRectDragChange]/[onRectDragEnd] to whichever of its two tools is currently active, since only
 * one can be at a time.
 */
@Composable
fun Stereo3DCursorHost(
    rectDragActive: Boolean = false,
    onRectDragChange: (start: Offset?, current: Offset?) -> Unit = { _, _ -> },
    onRectDragEnd: (start: Offset, end: Offset) -> Unit = { _, _ -> },
    // Fired with the raw vertical scroll delta of a mouse wheel event (positive = wheel scrolled
    // down/backward, negative = up/forward, same sign PointerInputChange.scrollDelta.y reports) -
    // see ImageScreen's use to alias the wheel to the Next/Previous arrow keys. Consumed the same
    // way every other pointer event here is (see the consume() call below), so it never also
    // triggers native Compose scroll behavior on whatever's underneath.
    onScroll: (delta: Float) -> Unit = {},
    // Mirrors AppViewModel.shrinkControls (see ShrinkControls.kt's shrinkHorizontally): when on,
    // every other overlay control is squeezed horizontally by 2, so the cursor dots get the same
    // treatment for visual consistency - squeezed around their own center, which just turns the
    // circle into a narrower ellipse without moving where it's pointing.
    shrinkControls: Boolean = false,
    content: @Composable () -> Unit,
) {
    val registry = remember { CursorHitRegistry() }
    val scrubRegistry = remember { CursorScrubRegistry() }
    val textClickRegistry = remember { CursorTextClickRegistry() }
    val density = LocalDensity.current
    val latestRectDragActive = remember { mutableStateOf(rectDragActive) }
    SideEffect { latestRectDragActive.value = rectDragActive }
    val latestOnRectDragChange = remember { mutableStateOf(onRectDragChange) }
    SideEffect { latestOnRectDragChange.value = onRectDragChange }
    val latestOnRectDragEnd = remember { mutableStateOf(onRectDragEnd) }
    SideEffect { latestOnRectDragEnd.value = onRectDragEnd }
    val latestOnScroll = remember { mutableStateOf(onScroll) }
    SideEffect { latestOnScroll.value = onScroll }
    CompositionLocalProvider(
        LocalCursorHitRegistry provides registry,
        LocalCursorScrubRegistry provides scrubRegistry,
        LocalCursorTextClickRegistry provides textClickRegistry,
    ) {
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
                        var rectDragStart: Offset? = null
                        var activeScrub: CursorScrubRegistry.Hit? = null
                        fun scrubFraction(hit: CursorScrubRegistry.Hit, logical: Offset) =
                            ((logical.x - hit.rect.left) / hit.rect.width).coerceIn(0f, 1f)
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    val logical = Offset(change.position.x.coerceIn(0f, halfWidthPx), change.position.y)
                                    when (event.type) {
                                        PointerEventType.Move -> {
                                            cursorPos = logical
                                            lastMoveTick++
                                            val scrub = activeScrub
                                            if (scrub != null) {
                                                scrub.onScrub(scrubFraction(scrub, logical))
                                            } else if (rectDragStart != null) {
                                                latestOnRectDragChange.value(rectDragStart, logical)
                                            }
                                        }
                                        PointerEventType.Press -> {
                                            cursorPos = logical
                                            lastMoveTick++
                                            // A press that lands on a registered scrub target (the
                                            // video progress bar) starts scrubbing; a press on a
                                            // registered text-click target (StereoBlinkingCaretTextField)
                                            // places the caret under the press instead; else a press
                                            // on a registered click target (a Cancel/Save button, a
                                            // switch, ...) is always resolved as a click, even while
                                            // a drag tool is active - otherwise Exif3dInfoPanel's
                                            // Cancel/Save buttons would be permanently unreachable
                                            // while cropMode/spotIssuesMode is on, since every press
                                            // would be swallowed into starting a rectangle drag
                                            // instead. Only a press that misses every registered
                                            // target starts a rectangle drag.
                                            val scrubHit = scrubRegistry.hitTest(logical)
                                            val textClickHit = textClickRegistry.hitTest(logical)
                                            if (scrubHit != null) {
                                                activeScrub = scrubHit
                                                scrubHit.onScrub(scrubFraction(scrubHit, logical))
                                            } else if (textClickHit != null) {
                                                textClickHit.onClick(logical - textClickHit.rect.topLeft)
                                            } else if (latestRectDragActive.value && registry.hitTest(logical) == null) {
                                                rectDragStart = logical
                                                latestOnRectDragChange.value(logical, logical)
                                            } else {
                                                pressStart = logical
                                            }
                                        }
                                        PointerEventType.Release -> {
                                            val scrub = activeScrub
                                            if (scrub != null) {
                                                activeScrub = null
                                                scrub.onScrubEnd(scrubFraction(scrub, logical))
                                            } else if (rectDragStart != null) {
                                                val start = rectDragStart
                                                rectDragStart = null
                                                latestOnRectDragChange.value(null, null)
                                                if (start != null) latestOnRectDragEnd.value(start, logical)
                                            } else {
                                                val start = pressStart
                                                pressStart = null
                                                if (start != null && hypot((logical.x - start.x).toDouble(), (logical.y - start.y).toDouble()) <= clickSlopPx) {
                                                    registry.hitTest(logical)?.invoke()
                                                }
                                            }
                                        }
                                        PointerEventType.Scroll -> {
                                            latestOnScroll.value(change.scrollDelta.y)
                                        }
                                        PointerEventType.Exit -> {
                                            cursorPos = null
                                            pressStart = null
                                            activeScrub = null
                                            if (rectDragStart != null) {
                                                rectDragStart = null
                                                latestOnRectDragChange.value(null, null)
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
                CompositionLocalProvider(LocalCursorVisible provides cursorVisible) {
                    content()
                }
                val pos = cursorPos
                if (cursorVisible && pos != null) {
                    StereoCursorOverlay(pos, halfWidthDp, shrinkControls)
                }
            }
        }
    }
}

@Composable
private fun StereoCursorOverlay(localPos: Offset, halfWidthDp: Dp, shrinkControls: Boolean) {
    val shift = halfWidthDp * CursorShiftPercent
    val density = LocalDensity.current
    val xDp = with(density) { localPos.x.toDp() }
    val yDp = with(density) { localPos.y.toDp() }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f).clipToBounds()) { CursorDot(xDp - shift / 2, yDp, shrinkControls) }
        Box(Modifier.fillMaxSize().weight(1f).clipToBounds()) { CursorDot(xDp + shift / 2, yDp, shrinkControls) }
    }
}

@Composable
private fun CursorDot(xDp: Dp, yDp: Dp, shrinkControls: Boolean) {
    Box(
        Modifier
            .offset(x = xDp - CursorRadius, y = yDp - CursorRadius)
            .size(CursorRadius * 2)
            .shrinkHorizontally(shrinkControls, TransformOrigin.Center)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.9f))
            .border(1.5.dp, Color.Black.copy(alpha = 0.8f), CircleShape),
    )
}

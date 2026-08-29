import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The three mutually-exclusive per-photo edit tools (manual align, crop, "spot stereo issues")
 * plus the auto-align/correct-zoom preview - all state that's local to whichever photo is
 * currently on screen in ImageView, owned by AppViewModel as `photoTools`. Pulled out of
 * AppViewModel because every navigation method (onFilesChosen, showNextImage,
 * showPreviousImage, ...) had to remember to reset all four pieces of state separately;
 * [resetAll] is the one call each of them needs now, and the mutual-exclusion guard the three
 * start*() functions used to repeat individually is centralized in [anyToolActive].
 */
class PhotoToolsState {
    // Ephemeral auto-align/correct-zoom result for the current image only (not persisted to
    // disk) - see AppViewModel.applyAlignedPreview.
    var alignedPreview by mutableStateOf<ImageBitmap?>(null)
        private set

    // Which algorithm produced alignedPreview - needed so Save knows what to redo against the
    // original file (see AppViewModel.performSaveAligned).
    var pendingAlignKind by mutableStateOf<AlignKind?>(null)
        private set

    // True while the user is nudging the right eye-half with arrow keys (see Main.kt's
    // onPreviewKeyEvent and startManualAlign below) - drives AlignButtonsRow's Cancel/Save pair and
    // locks out the auto-align buttons/other keyboard shortcuts so the two pipelines can't mix.
    var manualAlignMode by mutableStateOf(false)
        private set

    // Accumulated offset applied to the right half only - a fraction of the eye-half's
    // width(X)/height(Y), resolution independent, same idiom as CropRectFraction (see
    // ImageScreen.kt's StereoImage, which multiplies this by the actual bitmap size for the live
    // preview crop, and ManualAlign.saveManualAlign, which does the same against the full-res file).
    var manualAlignOffsetX by mutableStateOf(0f)
        private set
    var manualAlignOffsetY by mutableStateOf(0f)
        private set

    // True while the crop tool is active for the current photo (see Exif3dInfoPanel's Crop
    // button) - from the click on "Crop" until Cancel/Save, covering both the drag-to-draw phase
    // (cropRect still null) and the review phase once a rectangle has been released. Mirrors
    // manualAlignMode's role of locking out the other tools/navigation while active - see Main.kt's
    // onPreviewKeyEvent.
    var cropMode by mutableStateOf(false)
        private set

    // The finalized crop rectangle (fraction of one eye-half's width/height, resolution
    // independent - see CropRectFraction), set once the user releases the drag in ImageScreen's
    // onCropDragEnd. Null while still drawing - drives both ImageScreen's "only show the crop area"
    // preview and AlignButtonsRow's Save button (only enabled once non-null).
    var cropRect by mutableStateOf<CropRectFraction?>(null)
        private set

    // True while the "Spot stereo issues" tool is active for the current photo (see
    // Exif3dInfoPanel's "Spot stereo issues" button) - from the click until Cancel/Save. Unlike
    // cropMode, multiple rectangles can be drawn while this stays true (see spotIssueRects) rather
    // than the tool locking into a review-only phase after the first one - see Main.kt's
    // onPreviewKeyEvent for the same lock-out-other-tools treatment as manualAlignMode/cropMode.
    var spotIssuesMode by mutableStateOf(false)
        private set

    // Rectangles drawn so far (fraction of one eye-half's width/height, resolution independent -
    // see IssueRectFraction), appended to in ImageScreen's onSpotIssueDragEnd. Drives both
    // ImageScreen's preview overlay and AlignButtonsRow's Save button (only enabled once non-empty).
    var spotIssueRects by mutableStateOf<List<IssueRectFraction>>(emptyList())
        private set

    /** The mutual-exclusion guard [startManualAlign]/[startCrop]/[startSpotIssues] all share. */
    val anyToolActive: Boolean get() = manualAlignMode || cropMode || spotIssuesMode

    /** Clears every tool's pending state - called on every navigation so nothing carries over onto a different photo. */
    fun resetAll() {
        alignedPreview = null
        pendingAlignKind = null
        manualAlignMode = false
        manualAlignOffsetX = 0f
        manualAlignOffsetY = 0f
        cropMode = false
        cropRect = null
        spotIssuesMode = false
        spotIssueRects = emptyList()
    }

    /** Applies a finished auto-align/correct-zoom attempt's result - see AppViewModel.performAutoAlign. */
    fun applyAlignedPreview(result: AutoAlign.AutoAlignResult?, kind: AlignKind?) {
        alignedPreview = result?.bitmap
        pendingAlignKind = if (result != null) kind else null
    }

    /** Enters manual-align mode for the currently shown photo - see Main.kt's arrow-key handling. */
    fun startManualAlign() {
        if (anyToolActive) return
        alignedPreview = null
        pendingAlignKind = null
        manualAlignMode = true
        manualAlignOffsetX = 0f
        manualAlignOffsetY = 0f
    }

    /** Adds a fractional delta to the pending manual-align offset - see Main.kt's tick loop. */
    fun nudgeManualAlign(dx: Float, dy: Float) {
        if (!manualAlignMode) return
        manualAlignOffsetX += dx
        manualAlignOffsetY += dy
    }

    /** Discards the pending manual-align offset without touching disk. */
    fun cancelManualAlign() = resetAll()

    /** Enters crop mode for the currently shown photo - see Exif3dInfoPanel's Crop button. */
    fun startCrop() {
        if (anyToolActive) return
        alignedPreview = null
        pendingAlignKind = null
        cropMode = true
        cropRect = null
    }

    /** Records the rectangle drawn in ImageScreen's onCropDragEnd, switching to the review phase. */
    fun finalizeCropRect(rect: CropRectFraction) {
        if (!cropMode) return
        cropRect = rect
    }

    /** Discards the crop tool (drawn rectangle or not) without touching disk. */
    fun cancelCrop() = resetAll()

    /** Enters "spot stereo issues" mode for the currently shown photo - see Exif3dInfoPanel's button. */
    fun startSpotIssues() {
        if (anyToolActive) return
        alignedPreview = null
        pendingAlignKind = null
        spotIssuesMode = true
        spotIssueRects = emptyList()
    }

    /** Appends one rectangle drawn in ImageScreen's onSpotIssueDragEnd - the tool stays active so
     *  further rectangles can be drawn, unlike [finalizeCropRect]'s single-rectangle review phase. */
    fun addSpotIssueRect(rect: IssueRectFraction) {
        if (!spotIssuesMode) return
        spotIssueRects = spotIssueRects + rect
    }

    /** Discards the "spot stereo issues" tool (drawn rectangles or not) without touching disk. */
    fun cancelSpotIssues() = resetAll()
}

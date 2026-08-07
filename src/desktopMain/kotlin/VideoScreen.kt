import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.io.File
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

// Same sign convention as InfoPanelShiftPercent/CursorShiftPercent (negative = toward the
// viewer): -1% makes the controls read as floating just in front of the screen.
private const val ProgressBarShiftPercent = -0.01f
private val ProgressBarHeight = 4.dp
// Taller than the visual track so the handle is easy to grab - same "bigger hit area than visual
// mark" idea as CursorClickSlop.
private val ProgressBarTouchHeight = 20.dp
private val ProgressHandleRadius = 6.dp
private val ProgressBarBottomPadding = 40.dp
private val ProgressBarHorizontalPadding = 60.dp
private val PlayPauseButtonSize = 32.dp

/**
 * Video playback: decodes frames on a background thread with FFmpegFrameGrabber, loops at EOF.
 * No scrub/seek controls - reuses the same fullscreen shell and Esc handling as ImageScreen (see
 * Main.kt). When the source has an audio track, samples are pushed to a SourceDataLine whose
 * blocking writes also pace video frame display; sources without audio fall back to sleeping
 * off the frame rate.
 */
@Composable
fun VideoScreen(file: File) {
    var frameBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    // Fraction [0,1] of playback elapsed, or null while duration is unknown (some streams don't
    // report one) - in which case the progress bar just stays hidden.
    var progress by remember(file) { mutableStateOf<Float?>(null) }
    // Live scrub position while the handle is being dragged, overriding `progress` for display
    // only - the actual seek is requested once on release (onScrubEnd), not on every drag frame.
    var dragProgress by remember(file) { mutableStateOf<Float?>(null) }
    var paused by remember(file) { mutableStateOf(false) }
    // Written once by the decode thread after grabber.start(); read from onScrubEnd to convert a
    // [0,1] fraction into a microsecond timestamp. Same "plain State written off the main thread"
    // pattern already used for progress/frameBitmap below.
    var totalUs by remember(file) { mutableStateOf(0L) }
    // Read by the decode thread; written from the composable's scrub handler. Plain State isn't
    // safely readable off the main thread, so a seek request is mirrored into this atomic instead
    // (-1 = no pending request), same rationale as pausedFlag.
    val seekRequestUs = remember(file) { AtomicLong(-1L) }
    // Read by the decode thread; written from the composable's click handler. Plain State isn't
    // safely readable off the main thread, so pause/resume is mirrored into this atomic instead.
    val pausedFlag = remember(file) { AtomicBoolean(false) }
    val onTogglePause = {
        val newPaused = !paused
        paused = newPaused
        pausedFlag.set(newPaused)
    }
    val onScrub = { fraction: Float -> dragProgress = fraction }
    val onScrubEnd = { fraction: Float ->
        dragProgress = null
        if (totalUs > 0) seekRequestUs.set((fraction * totalUs).toLong())
    }

    DisposableEffect(file) {
        val grabber = FFmpegFrameGrabber(file)
        val converter = Java2DFrameConverter()
        val running = AtomicBoolean(true)
        var soundLine: SourceDataLine? = null

        val thread = Thread {
            try {
                grabber.start()
                val lengthUs = grabber.lengthInTime
                totalUs = lengthUs
                fun updateProgress() {
                    if (lengthUs > 0) progress = (grabber.timestamp.toFloat() / lengthUs.toFloat()).coerceIn(0f, 1f)
                }
                if (grabber.audioChannels > 0) {
                    val audioFormat = AudioFormat(grabber.sampleRate.toFloat(), 16, grabber.audioChannels, true, false)
                    val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, audioFormat)) as SourceDataLine
                    line.open(audioFormat)
                    line.start()
                    soundLine = line

                    var wasPaused = false
                    while (running.get()) {
                        val requestedUs = seekRequestUs.getAndSet(-1L)
                        if (requestedUs >= 0) {
                            // Discard buffered audio so it doesn't keep playing from the old
                            // position, then seek and pull frames until an image turns up (frames
                            // right after a seek are often audio-only) so the displayed frame
                            // refreshes immediately even while paused.
                            line.flush()
                            grabber.setTimestamp(requestedUs)
                            updateProgress()
                            var attempts = 0
                            while (attempts < 30) {
                                val seekFrame = grabber.grab() ?: break
                                attempts++
                                val seekImage = seekFrame.image?.let { converter.convert(seekFrame) }
                                if (seekImage != null) {
                                    frameBitmap = seekImage.toComposeImageBitmap()
                                    break
                                }
                            }
                            continue
                        }
                        val isPausedNow = pausedFlag.get()
                        if (isPausedNow != wasPaused) {
                            if (isPausedNow) line.stop() else line.start()
                            wasPaused = isPausedNow
                        }
                        if (isPausedNow) {
                            Thread.sleep(50)
                            continue
                        }
                        val frame = grabber.grab()
                        if (frame == null) {
                            // End of stream: loop back to the start.
                            line.drain()
                            grabber.restart()
                            continue
                        }
                        updateProgress()
                        val bufferedImage = frame.image?.let { converter.convert(frame) }
                        if (bufferedImage != null) {
                            frameBitmap = bufferedImage.toComposeImageBitmap()
                        }
                        val channelSamples = frame.samples?.getOrNull(0) as? ShortBuffer
                        if (channelSamples != null) {
                            channelSamples.rewind()
                            val audioBytes = ByteArray(channelSamples.remaining() * 2)
                            var i = 0
                            while (channelSamples.hasRemaining()) {
                                val sample = channelSamples.get().toInt()
                                audioBytes[i++] = (sample and 0xff).toByte()
                                audioBytes[i++] = ((sample shr 8) and 0xff).toByte()
                            }
                            line.write(audioBytes, 0, audioBytes.size)
                        }
                    }
                } else {
                    val frameDelayMs = if (grabber.frameRate > 0) (1000.0 / grabber.frameRate).toLong() else 33L
                    while (running.get()) {
                        val requestedUs = seekRequestUs.getAndSet(-1L)
                        if (requestedUs >= 0) {
                            grabber.setTimestamp(requestedUs)
                            updateProgress()
                            grabber.grabImage()?.let { seekFrame ->
                                converter.convert(seekFrame)?.let { frameBitmap = it.toComposeImageBitmap() }
                            }
                            continue
                        }
                        if (pausedFlag.get()) {
                            Thread.sleep(50)
                            continue
                        }
                        val frame = grabber.grabImage()
                        if (frame == null) {
                            // End of stream: loop back to the start.
                            grabber.restart()
                            continue
                        }
                        updateProgress()
                        val bufferedImage = converter.convert(frame)
                        if (bufferedImage != null) {
                            frameBitmap = bufferedImage.toComposeImageBitmap()
                        }
                        Thread.sleep(frameDelayMs)
                    }
                }
            } catch (_: InterruptedException) {
                // expected on dispose
            } finally {
                soundLine?.let {
                    it.stop()
                    it.close()
                }
                grabber.stop()
                grabber.release()
            }
        }
        thread.isDaemon = true
        thread.start()

        onDispose {
            running.set(false)
            // Unblock a pending SourceDataLine.write() so the thread can notice `running` and exit promptly.
            soundLine?.let { runCatching { it.stop(); it.flush() } }
            thread.interrupt()
            thread.join(1000)
        }
    }

    Stereo3DCursorHost {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val halfWidthDp = maxWidth / 2
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                frameBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            if (LocalCursorVisible.current) {
                StereoVideoControls(paused, onTogglePause, progress, dragProgress, onScrub, onScrubEnd, halfWidthDp)
            }
        }
    }
}

/**
 * Play/pause button plus playback-position bar, duplicated on both halves and offset by
 * [ProgressBarShiftPercent] so they read correctly in 3D - same left/right-duplication technique
 * as InfoPanel/Stereo3DCursorHost. Only shown while [LocalCursorVisible] is true, i.e. in sync
 * with the auto-hiding 3D cursor.
 */
@Composable
private fun StereoVideoControls(
    paused: Boolean,
    onTogglePause: () -> Unit,
    progress: Float?,
    dragProgress: Float?,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    halfWidthDp: Dp,
) {
    val shift = halfWidthDp * ProgressBarShiftPercent
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f)) {
            VideoControlsHalf(paused, onTogglePause, progress, dragProgress, onScrub, onScrubEnd, offsetX = -shift / 2)
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            VideoControlsHalf(paused, onTogglePause, progress, dragProgress, onScrub, onScrubEnd, offsetX = shift / 2)
        }
    }
}

@Composable
private fun VideoControlsHalf(
    paused: Boolean,
    onTogglePause: () -> Unit,
    progress: Float?,
    dragProgress: Float?,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    offsetX: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset(x = offsetX)
            .padding(horizontal = ProgressBarHorizontalPadding)
            .padding(bottom = ProgressBarBottomPadding),
        contentAlignment = Alignment.BottomStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // canFocus = false for the same reason as Exif3dInfoPanel's icons: a click stealing
            // keyboard focus would break Escape/arrow key handling on Main.kt's root Box.
            Box(
                modifier = Modifier
                    .size(PlayPauseButtonSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .focusProperties { canFocus = false }
                    .clickable(onClick = onTogglePause)
                    .cursor3DClickTarget(onTogglePause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (progress != null) {
                Spacer(Modifier.width(12.dp))
                // dragProgress overrides progress while the handle is being dragged, so the bar
                // follows the pointer even though the actual seek only fires on release
                // (onScrubEnd) - see VideoScreen's onScrub/onScrubEnd.
                val displayedProgress = (dragProgress ?: progress).coerceIn(0f, 1f)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .height(ProgressBarTouchHeight)
                        .cursor3DScrubTarget(onScrub = onScrub, onScrubEnd = onScrubEnd),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ProgressBarHeight)
                            .clip(RoundedCornerShape(ProgressBarHeight / 2))
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(displayedProgress)
                                .clip(RoundedCornerShape(ProgressBarHeight / 2))
                                .background(Color.White.copy(alpha = 0.9f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * displayedProgress - ProgressHandleRadius)
                            .size(ProgressHandleRadius * 2)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

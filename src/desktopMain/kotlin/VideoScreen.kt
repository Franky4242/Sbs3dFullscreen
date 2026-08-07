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
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File

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
 * Video playback: decodes via real libVLC (through the vlcj bindings), the same engine the
 * standalone VLC app uses - hardware-decoded and frame-paced by libVLC itself, unlike the earlier
 * FFmpegFrameGrabber-based pipeline (software decode with a hand-rolled, drift-prone frame clock)
 * which still looked choppy even after forcing hardware decoder names. Requires VLC to be
 * installed on the machine - MediaPlayerFactory() locates it via vlcj's NativeDiscovery.
 * Rendering stays headless (no AWT/Swing video surface): a CallbackVideoSurface has libVLC write
 * each decoded frame directly into a BufferedImage's backing int array, which is then handed to
 * Compose the same way the FFmpegFrameGrabber pipeline did. Reuses the same fullscreen shell and
 * Esc handling as ImageScreen (see Main.kt). No manual audio pipeline either - libVLC plays the
 * audio track itself through its own output.
 */
@Composable
fun VideoScreen(file: File) {
    var frameBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    // Fraction [0,1] of playback elapsed, or null before the first positionChanged event -
    // in which case the progress bar just stays hidden.
    var progress by remember(file) { mutableStateOf<Float?>(null) }
    // Live scrub position while the handle is being dragged, overriding `progress` for display
    // only - the actual seek is requested once on release (onScrubEnd), not on every drag frame.
    var dragProgress by remember(file) { mutableStateOf<Float?>(null) }
    var paused by remember(file) { mutableStateOf(false) }
    var mediaPlayer by remember(file) { mutableStateOf<EmbeddedMediaPlayer?>(null) }

    val onTogglePause = {
        val newPaused = !paused
        paused = newPaused
        mediaPlayer?.controls()?.setPause(newPaused)
        Unit
    }
    val onScrub = { fraction: Float -> dragProgress = fraction }
    val onScrubEnd = { fraction: Float ->
        dragProgress = null
        mediaPlayer?.controls()?.setPosition(fraction)
        Unit
    }

    DisposableEffect(file) {
        val factory = MediaPlayerFactory()
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()

        // Filled in by bufferFormatCallback once the video's real dimensions are known; its
        // backing int array is handed to libVLC as the render target, so onDisplay below needs no
        // extra copy beyond the toComposeImageBitmap() conversion.
        var bufferedImage: BufferedImage? = null
        val renderCallback = object : RenderCallbackAdapter() {
            override fun onDisplay(mediaPlayer: MediaPlayer, buffer: IntArray) {
                bufferedImage?.let { frameBitmap = it.toComposeImageBitmap() }
            }
        }
        val bufferFormatCallback = object : BufferFormatCallbackAdapter() {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                val image = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB)
                bufferedImage = image
                renderCallback.setBuffer((image.raster.dataBuffer as DataBufferInt).data)
                return RV32BufferFormat(sourceWidth, sourceHeight)
            }
        }
        player.videoSurface().set(
            CallbackVideoSurface(bufferFormatCallback, renderCallback, true, VideoSurfaceAdapters.getVideoSurfaceAdapter())
        )
        player.controls().setRepeat(true)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun positionChanged(mediaPlayer: MediaPlayer, newPosition: Float) {
                progress = newPosition.coerceIn(0f, 1f)
            }
        })

        mediaPlayer = player
        player.media().play(file.absolutePath)

        onDispose {
            mediaPlayer = null
            player.release()
            factory.release()
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

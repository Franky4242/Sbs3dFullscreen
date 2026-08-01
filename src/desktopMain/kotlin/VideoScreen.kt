import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal silent video playback: decodes frames on a background thread with FFmpegFrameGrabber,
 * loops at EOF. No audio, no scrub bar/controls - reuses the same fullscreen shell and Esc
 * handling as ImageScreen (see Main.kt).
 */
@Composable
fun VideoScreen(file: File) {
    var frameBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }

    DisposableEffect(file) {
        val grabber = FFmpegFrameGrabber(file)
        val converter = Java2DFrameConverter()
        val running = AtomicBoolean(true)

        val thread = Thread {
            try {
                grabber.start()
                val frameDelayMs = if (grabber.frameRate > 0) (1000.0 / grabber.frameRate).toLong() else 33L
                while (running.get()) {
                    val frame = grabber.grabImage()
                    if (frame == null) {
                        // End of stream: loop back to the start.
                        grabber.restart()
                        continue
                    }
                    val bufferedImage = converter.convert(frame)
                    if (bufferedImage != null) {
                        frameBitmap = bufferedImage.toComposeImageBitmap()
                    }
                    Thread.sleep(frameDelayMs)
                }
            } catch (_: InterruptedException) {
                // expected on dispose
            } finally {
                grabber.stop()
                grabber.release()
            }
        }
        thread.isDaemon = true
        thread.start()

        onDispose {
            running.set(false)
            thread.interrupt()
            thread.join(1000)
        }
    }

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
}

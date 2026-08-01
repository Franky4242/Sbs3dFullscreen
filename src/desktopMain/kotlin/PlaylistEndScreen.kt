import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.camera3d.camera.feature_playlists.ui.ComposablePortableEndSlide
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.playlist_end_label
import sbs3dfullscreen.resources.playlist_end_slide_hint

/**
 * The slideshow's end slide: the shared stereo visual (ComposablePortableEndSlide, also used by
 * CameraSync3D's beamer secondary display and SIDE_BY_SIDE mode) plus a blinking keyboard hint,
 * since this app has no touch/tap-for-buttons - Space/Right replays from the title slide, Escape
 * exits (see Main.kt).
 */
@Composable
fun PlaylistEndScreen() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ComposablePortableEndSlide(endLabel = stringResource(Res.string.playlist_end_label))
    }
}

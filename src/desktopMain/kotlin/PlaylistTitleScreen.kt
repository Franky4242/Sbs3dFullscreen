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
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.ui.ComposablePortableTitleSlide
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.playlist_title_slide_hint

/**
 * The slideshow's title slide: the shared stereo visual (ComposablePortableTitleSlide, also used
 * by CameraSync3D's beamer secondary display and SIDE_BY_SIDE mode) plus a blinking keyboard hint,
 * since this app has no touch/Play button - Space/Right starts the slideshow (see Main.kt).
 */
@Composable
fun PlaylistTitleScreen(playlist: Playlist) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ComposablePortableTitleSlide(
            title = playlist.name,
            subtitle = playlist.subtitle,
            titleShiftPercent = playlist.titleZPercent / 100f,
            titleSize = 24f,
            subtitleShiftPercent = playlist.subtitleZPercent / 100f,
            subtitleSize = 16f,
        )
    }
}

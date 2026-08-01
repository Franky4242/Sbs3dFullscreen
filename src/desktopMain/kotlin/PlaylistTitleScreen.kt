
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.ui.ComposablePortableTitleSlide


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

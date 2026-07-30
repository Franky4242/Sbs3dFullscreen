import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.painterResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.icon
import java.io.File

fun main(args: Array<String>) = application {
    // Windows launches the app with the file path as an argument when it's opened
    // via a file association (double-click, "Open with sbs3Dfullscreen", etc.).
    val initialFile = args.firstOrNull()?.let(::File)?.takeIf { it.isFile }
    val windowState = rememberWindowState(
        placement = if (initialFile != null) WindowPlacement.Maximized else WindowPlacement.Floating
    )
    val viewModel = remember { AppViewModel(initialFile) }
    val focusRequester = remember { FocusRequester() }
    val undecorated = viewModel.screen == Screen.ImageView

    // undecorated can only be set before the window's peer is created, so the
    // whole Window is disposed and recreated (via key()) whenever it changes.
    key(undecorated) {
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "sbs3Dfullscreen",
            undecorated = undecorated,
            icon = painterResource(Res.drawable.icon),
        ) {
            LaunchedEffect(viewModel.screen) {
                focusRequester.requestFocus()
            }

            CompositionLocalProvider(LocalAppLocale provides viewModel.language) {
                // stringResource() re-reads Locale.getDefault() when it's newly composed,
                // so the subtree must be recreated (via key()) whenever the language changes.
                key(viewModel.language) {
                    MaterialTheme {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    if (viewModel.screen != Screen.ImageView || event.type != KeyEventType.KeyDown) {
                                        false
                                    } else when (event.key) {
                                        Key.Escape -> {
                                            windowState.placement = WindowPlacement.Floating
                                            viewModel.closeImageView()
                                            true
                                        }
                                        Key.Spacebar, Key.DirectionRight -> {
                                            viewModel.showNextImage()
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            viewModel.showPreviousImage()
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        ) {
                            when (viewModel.screen) {
                                Screen.Welcome -> WelcomeScreen(
                                    window = window,
                                    language = viewModel.language,
                                    onLanguageChosen = viewModel::onLanguageChosen,
                                    onFilesChosen = { files ->
                                        viewModel.onFilesChosen(files)
                                        // Undecorated + Maximized ("borderless fullscreen") rather than
                                        // WindowPlacement.Fullscreen: on Windows, Fullscreen puts the window
                                        // into real OS exclusive full-screen mode (GraphicsDevice.fullScreenWindow),
                                        // which Windows auto-minimizes if the window loses focus - e.g. when the
                                        // 3D monitor's own "activate 3D" popup steals focus. Maximized is a normal
                                        // window, so it just deactivates instead of getting iconified.
                                        windowState.placement = WindowPlacement.Maximized
                                    }
                                )

                                Screen.ImageView -> viewModel.currentImage?.let { file ->
                                    ImageScreen(file)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

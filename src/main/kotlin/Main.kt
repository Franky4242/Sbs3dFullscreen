import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.io.File
import java.io.FilenameFilter

private enum class Screen { Welcome, ImageView }

fun main() = application {
    val windowState = rememberWindowState()
    var screen by remember { mutableStateOf(Screen.Welcome) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    val focusRequester = remember { FocusRequester() }
    val undecorated = screen == Screen.ImageView

    // undecorated can only be set before the window's peer is created, so the
    // whole Window is disposed and recreated (via key()) whenever it changes.
    key(undecorated) {
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "sbs3Dfullscreen",
            undecorated = undecorated,
            icon = painterResource("icon.png"),
        ) {
            LaunchedEffect(screen) {
                focusRequester.requestFocus()
            }

            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (screen == Screen.ImageView &&
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Escape
                            ) {
                                windowState.placement = WindowPlacement.Floating
                                screen = Screen.Welcome
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    when (screen) {
                        Screen.Welcome -> WelcomeScreen(
                            window = window,
                            onFileChosen = { file ->
                                selectedFile = file
                                screen = Screen.ImageView
                                // Undecorated + Maximized ("borderless fullscreen") rather than
                                // WindowPlacement.Fullscreen: on Windows, Fullscreen puts the window
                                // into real OS exclusive full-screen mode (GraphicsDevice.fullScreenWindow),
                                // which Windows auto-minimizes if the window loses focus - e.g. when the
                                // 3D monitor's own "activate 3D" popup steals focus. Maximized is a normal
                                // window, so it just deactivates instead of getting iconified.
                                windowState.placement = WindowPlacement.Maximized
                            }
                        )

                        Screen.ImageView -> selectedFile?.let { file ->
                            ImageScreen(file)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(window: java.awt.Window, onFileChosen: (File) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Welcome!")
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                val dialog = FileDialog(window as? java.awt.Frame, "Select a JPEG image", FileDialog.LOAD)
                dialog.filenameFilter = FilenameFilter { _, name ->
                    val lower = name.lowercase()
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                }
                dialog.isVisible = true

                val directory = dialog.directory
                val file = dialog.file
                if (directory != null && file != null) {
                    onFileChosen(File(directory, file))
                }
            }) {
                Text("Choose JPEG image")
            }
        }
    }
}

@Composable
private fun ImageScreen(file: File) {
    val imageBitmap = remember(file) {
        file.inputStream().use { loadImageBitmap(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

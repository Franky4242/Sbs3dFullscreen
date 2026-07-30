import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.choose_jpeg_button
import sbs3dfullscreen.resources.file_dialog_title
import sbs3dfullscreen.resources.icon
import sbs3dfullscreen.resources.welcome
import java.awt.FileDialog
import java.io.File
import java.io.FilenameFilter
import java.util.Locale

private enum class Screen { Welcome, ImageView }

// Compose Multiplatform's resource system (stringResource) resolves the active language
// from Locale.getDefault() and has no public API yet to override it per-composition, so
// we track an explicit override here and mutate the JVM default Locale when it changes.
// See: https://kotlinlang.org/docs/multiplatform/compose-resource-environment.html
private object LocalAppLocale {
    private var systemDefault: Locale? = null
    private val local = staticCompositionLocalOf { Locale.getDefault().toString() }

    val current: String
        @Composable get() = local.current

    @Composable
    infix fun provides(languageTag: String?): ProvidedValue<*> {
        if (systemDefault == null) {
            systemDefault = Locale.getDefault()
        }
        val resolved = languageTag?.let(Locale::of) ?: systemDefault!!
        Locale.setDefault(resolved)
        return local.provides(resolved.toString())
    }
}

fun main(args: Array<String>) = application {
    // Windows launches the app with the file path as an argument when it's opened
    // via a file association (double-click, "Open with sbs3Dfullscreen", etc.).
    val initialFile = args.firstOrNull()?.let(::File)?.takeIf { it.isFile }
    val windowState = rememberWindowState(
        placement = if (initialFile != null) WindowPlacement.Maximized else WindowPlacement.Floating
    )
    var screen by remember { mutableStateOf(if (initialFile != null) Screen.ImageView else Screen.Welcome) }
    var imageFiles by remember { mutableStateOf(initialFile?.let { listOf(it) } ?: emptyList()) }
    var currentImageIndex by remember { mutableStateOf(0) }
    var language by remember { mutableStateOf<String?>(null) }
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
            icon = painterResource(Res.drawable.icon),
        ) {
            LaunchedEffect(screen) {
                focusRequester.requestFocus()
            }

            CompositionLocalProvider(LocalAppLocale provides language) {
                // stringResource() re-reads Locale.getDefault() when it's newly composed,
                // so the subtree must be recreated (via key()) whenever the language changes.
                key(language) {
                    MaterialTheme {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    if (screen != Screen.ImageView || event.type != KeyEventType.KeyDown) {
                                        false
                                    } else when (event.key) {
                                        Key.Escape -> {
                                            windowState.placement = WindowPlacement.Floating
                                            screen = Screen.Welcome
                                            true
                                        }
                                        Key.Spacebar, Key.DirectionRight -> {
                                            if (currentImageIndex < imageFiles.lastIndex) currentImageIndex++
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            if (currentImageIndex > 0) currentImageIndex--
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        ) {
                            when (screen) {
                                Screen.Welcome -> WelcomeScreen(
                                    window = window,
                                    language = language,
                                    onLanguageChosen = { language = it },
                                    onFilesChosen = { files ->
                                        imageFiles = files
                                        currentImageIndex = 0
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

                                Screen.ImageView -> imageFiles.getOrNull(currentImageIndex)?.let { file ->
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

@Composable
private fun WelcomeScreen(
    window: java.awt.Window,
    language: String?,
    onLanguageChosen: (String?) -> Unit,
    onFilesChosen: (List<File>) -> Unit,
) {
    val dialogTitle = stringResource(Res.string.file_dialog_title)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val currentLanguage = language ?: LocalAppLocale.current.substring(0, 2)
            LanguageButton(label = "EN", selected = currentLanguage == "en", onClick = { onLanguageChosen("en") })
            LanguageButton(label = "FR", selected = currentLanguage == "fr", onClick = { onLanguageChosen("fr") })
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.welcome))
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                val dialog = FileDialog(window as? java.awt.Frame, dialogTitle, FileDialog.LOAD)
                dialog.filenameFilter = FilenameFilter { _, name ->
                    val lower = name.lowercase()
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                }
                dialog.isMultipleMode = true
                dialog.isVisible = true

                val files = dialog.files
                if (files.isNotEmpty()) {
                    onFilesChosen(files.toList())
                }
            }) {
                Text(stringResource(Res.string.choose_jpeg_button))
            }
        }
    }
}

@Composable
private fun LanguageButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonBorder(enabled = true)
        },
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(label)
    }
}

@Composable
private fun ImageScreen(file: File) {
    val imageBitmap = remember(file) {
        file.readBytes().decodeToImageBitmap()
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

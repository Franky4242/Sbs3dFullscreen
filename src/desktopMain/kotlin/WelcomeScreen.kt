import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.cancel_button
import sbs3dfullscreen.resources.choose_jpeg_button
import sbs3dfullscreen.resources.choose_playlist_button
import sbs3dfullscreen.resources.file_dialog_title
import sbs3dfullscreen.resources.import_playlist_already_exists
import sbs3dfullscreen.resources.ok_button
import sbs3dfullscreen.resources.playlist_dialog_title
import sbs3dfullscreen.resources.playlist_list_button
import sbs3dfullscreen.resources.welcome
import java.io.File
import javax.swing.JFileChooser

@Composable
fun WelcomeScreen(
    window: java.awt.Window,
    language: String?,
    onLanguageChosen: (String?) -> Unit,
    onFilesChosen: (List<File>) -> Unit,
    onImportPlaylist: (File) -> Boolean,
    onOpenPlaylistList: () -> Unit,
) {
    val dialogTitle = stringResource(Res.string.file_dialog_title)
    val playlistDialogTitle = stringResource(Res.string.playlist_dialog_title)
    var importErrorFolderName by remember { mutableStateOf<String?>(null) }

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
                val files = chooseFiles(
                    window = window,
                    title = dialogTitle,
                    extensions = arrayOf("jpg", "jpeg", "mp4", "mov", "mkv", "avi"),
                )
                if (files.isNotEmpty()) {
                    onFilesChosen(files)
                }
            }) {
                Text(stringResource(Res.string.choose_jpeg_button))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val chooser = JFileChooser()
                chooser.dialogTitle = playlistDialogTitle
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.let { folder ->
                        if (!onImportPlaylist(folder)) {
                            importErrorFolderName = folder.name
                        }
                    }
                }
            }) {
                Text(stringResource(Res.string.choose_playlist_button))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenPlaylistList) {
                Text(stringResource(Res.string.playlist_list_button))
            }
        }

        importErrorFolderName?.let { folderName ->
            AlertDialog(
                onDismissRequest = { importErrorFolderName = null },
                title = { Text(stringResource(Res.string.import_playlist_already_exists, folderName)) },
                text = {},
                confirmButton = {
                    TextButton(onClick = { importErrorFolderName = null }) {
                        Text(stringResource(Res.string.ok_button))
                    }
                },
            )
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

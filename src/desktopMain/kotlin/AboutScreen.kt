import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import org.jetbrains.compose.resources.stringResource
import sbs3dfullscreen.resources.Res
import sbs3dfullscreen.resources.about_analytics_revoke_button
import sbs3dfullscreen.resources.about_analytics_status_allowed
import sbs3dfullscreen.resources.about_analytics_status_label
import sbs3dfullscreen.resources.about_analytics_status_revoked
import sbs3dfullscreen.resources.about_author_label
import sbs3dfullscreen.resources.about_license_label
import sbs3dfullscreen.resources.about_open_source_button
import sbs3dfullscreen.resources.about_open_source_dialog_title
import sbs3dfullscreen.resources.about_purpose_text
import sbs3dfullscreen.resources.about_screen_title
import sbs3dfullscreen.resources.about_version_label
import sbs3dfullscreen.resources.ok_button
import sbs3dfullscreen.resources.playlist_back_button

/** One open source dependency credited in the "Open source licenses" dialog below. */
private data class OpenSourceLibrary(val name: String, val license: String)

/**
 * Third-party libraries this app's desktop code (and the shared fr.camera3d.camera.* sources it
 * bundles from CameraSync3D) is built on, credited here as required by their respective licenses.
 * Kept as a plain hardcoded list rather than a Gradle-generated one - this project has too few
 * dependencies to justify a license-report plugin.
 */
private val openSourceLibraries = listOf(
    OpenSourceLibrary("Kotlin", "Apache License 2.0"),
    OpenSourceLibrary("kotlinx.coroutines", "Apache License 2.0"),
    OpenSourceLibrary("JetBrains Compose Multiplatform", "Apache License 2.0"),
    OpenSourceLibrary("Material Icons Extended", "Apache License 2.0"),
    OpenSourceLibrary("Jackson (jackson-module-kotlin, jackson-dataformat-yaml)", "Apache License 2.0"),
    OpenSourceLibrary("Coil", "Apache License 2.0"),
    OpenSourceLibrary("Reorderable (sh.calvin.reorderable)", "Apache License 2.0"),
    OpenSourceLibrary("Apache Commons Imaging", "Apache License 2.0"),
    OpenSourceLibrary("OpenCV", "Apache License 2.0"),
    OpenSourceLibrary("vlcj", "GNU General Public License v3.0"),
)

/**
 * The app's version, read from the "app.version" system property set in build.gradle.kts'
 * jvmArgs (mirroring gradle.properties' appVersion) - no BuildConfig-generation step needed.
 */
private val appVersion: String get() = System.getProperty("app.version") ?: "?"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showOpenSourceDialog by remember { mutableStateOf(false) }
    var analyticsConsentGranted by remember { mutableStateOf(Analytics.consentGranted) }

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = stringResource(Res.string.about_screen_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.playlist_back_button),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        actionsContent = {},
        bottomBar = {},
        snackbarHostState = snackbarHostState,
        screenContent = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(Res.string.about_purpose_text))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.about_author_label))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.about_license_label))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.about_version_label, appVersion))
                Spacer(Modifier.height(24.dp))
                Button(onClick = { showOpenSourceDialog = true }) {
                    Text(stringResource(Res.string.about_open_source_button))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(
                        Res.string.about_analytics_status_label,
                        stringResource(
                            if (analyticsConsentGranted) Res.string.about_analytics_status_allowed
                            else Res.string.about_analytics_status_revoked
                        ),
                    )
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = analyticsConsentGranted,
                    onClick = {
                        Analytics.setConsent(false)
                        analyticsConsentGranted = false
                    },
                ) {
                    Text(stringResource(Res.string.about_analytics_revoke_button))
                }
            }
        },
    )

    if (showOpenSourceDialog) {
        AlertDialog(
            onDismissRequest = { showOpenSourceDialog = false },
            title = { Text(stringResource(Res.string.about_open_source_dialog_title)) },
            text = {
                LazyColumn {
                    items(openSourceLibraries) { library ->
                        Column {
                            Text(library.name, style = MaterialTheme.typography.bodyLarge)
                            Text(library.license, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenSourceDialog = false }) {
                    Text(stringResource(Res.string.ok_button))
                }
            },
        )
    }
}

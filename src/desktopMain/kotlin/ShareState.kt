import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One "Share" attempt's outcome - see ShareState.toast. Only fired on [Share.EmailResult.FAILED]
 *  (a cancelled compose window - [Share.EmailResult.CANCELLED] - is a deliberate user action, not
 *  worth a toast). */
data class ShareToast(val token: Int)

private val shareTypePref = EnumPref("shareType", Share.ShareType.SBS, Share.ShareType::valueOf)
private val shareDestinationPref = EnumPref("shareDestination", Share.Destination.EMAIL, Share.Destination::valueOf)

/**
 * Holds the settings-menu "Share" dialog's state (in-flight guard, failure toast, remembered last
 * choice) and the perform-share flow itself - pulled out of AppViewModel for the same reason
 * GalleryState/PhotoToolsState were: a self-contained cluster of fields/methods used from exactly
 * one place (ImageScreen's Share dialog). Owned by AppViewModel as `share`.
 *
 * A successful "save to Downloads" share still needs to flash the app-wide SaveResultToast
 * (shared with every other save path - align, crop, spot-issues, delete) rather than a
 * Share-specific one, so [perform] reports that outcome back to the caller via [onSaveResult]
 * instead of owning it here.
 */
class ShareState {
    var isSharing by mutableStateOf(false)
        private set
    var toast by mutableStateOf<ShareToast?>(null)
        private set
    private var toastCounter = 0
    var lastType by mutableStateOf(shareTypePref.load())
        private set
    var lastDestination by mutableStateOf(shareDestinationPref.load())
        private set

    /** Clears [toast] - see AppViewModel.closeImageView's doc on why a stale trigger must be reset. */
    fun clearToast() {
        toast = null
    }

    /**
     * Prepares [file] per [type] (see Share.prepareShareFile, both off the UI thread) then, per
     * [destination], either hands it to the default email program (Share.shareViaEmail - its
     * MAPI_DIALOG blocks until the compose window is sent or dismissed) or copies it into the
     * Downloads folder (Share.saveToDownloads). [isSharing] guards against a second Share attempt
     * overlapping this one. [type]/[destination] are remembered as [lastType]/[lastDestination]
     * regardless of the outcome, since the dialog was already OK'd with these choices.
     */
    suspend fun perform(file: File, type: Share.ShareType, destination: Share.Destination, onSaveResult: (Boolean) -> Unit) {
        if (isSharing) return
        lastType = type
        lastDestination = destination
        shareTypePref.save(type)
        shareDestinationPref.save(destination)
        isSharing = true
        try {
            val prepared = withContext(Dispatchers.IO) { Share.prepareShareFile(file, type) }
            when (destination) {
                Share.Destination.EMAIL -> {
                    val result = if (prepared != null) {
                        withContext(Dispatchers.IO) { Share.shareViaEmail(prepared) }
                    } else {
                        Share.EmailResult.FAILED
                    }
                    if (result == Share.EmailResult.SENT) {
                        Analytics.logEvent("share", mapOf("type" to type.name.lowercase(), "destination" to "email"))
                    }
                    if (result == Share.EmailResult.FAILED) {
                        toastCounter++
                        toast = ShareToast(toastCounter)
                    }
                }
                Share.Destination.DOWNLOADS_FOLDER -> {
                    val saved = prepared?.let { withContext(Dispatchers.IO) { Share.saveToDownloads(it) } }
                    if (saved != null) {
                        Analytics.logEvent("share", mapOf("type" to type.name.lowercase(), "destination" to "downloads"))
                    }
                    onSaveResult(saved != null)
                }
            }
        } finally {
            isSharing = false
        }
    }
}

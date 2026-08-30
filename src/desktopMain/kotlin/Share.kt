import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.file.Files

/**
 * Backs ImageScreen's settings-menu "Share / Save As" item: prepares a single-image copy of the
 * current side-by-side stereo photo (one eye, the original pair, or a red/cyan anaglyph combining
 * both eyes) and, per the dialog's destination choice, either hands it to the user's default email
 * program with the file already attached (mirroring Windows Explorer's own "Send to > Mail
 * recipient") or copies it into the user's Downloads folder.
 */
object Share {
    enum class ShareType { LEFT, RIGHT, SBS, ANAGLYPH }

    /** Where a prepared share file (see [prepareShareFile]) ends up - picked via the radio buttons
     *  in ImageScreen's ShareTypeDialog. */
    enum class Destination { EMAIL, DOWNLOADS_FOLDER }

    enum class EmailResult { SENT, CANCELLED, FAILED }

    /**
     * Builds the file to attach for [type] - the original [file] as-is for [ShareType.SBS] (no
     * re-encoding needed, it's already the file on disk), otherwise a freshly written JPEG in a
     * throwaway temp directory (never next to the source photo - unlike AutoAlign.writeAlignedResult's
     * saves, this is a disposable copy for the outgoing email, not a new edit). Returns null if the
     * source can't be decoded.
     */
    fun prepareShareFile(file: File, type: ShareType): File? = when (type) {
        ShareType.SBS -> file
        ShareType.LEFT -> extractHalf(file, keepLeft = true)
        ShareType.RIGHT -> extractHalf(file, keepLeft = false)
        ShareType.ANAGLYPH -> buildAnaglyph(file)
    }

    /**
     * Copies [file] into the current user's Downloads folder, the destination for the ShareTypeDialog's
     * "Save to Downloads folder" radio option. Appends a numeric suffix rather than overwriting when a
     * same-named file already exists there. Returns the saved File, or null if the copy fails.
     */
    fun saveToDownloads(file: File): File? = try {
        val downloadsDir = File(File(System.getProperty("user.home")), "Downloads")
        downloadsDir.mkdirs()
        var dest = File(downloadsDir, file.name)
        var suffix = 1
        while (dest.exists()) {
            dest = File(downloadsDir, "${file.nameWithoutExtension}_$suffix.${file.extension}")
            suffix++
        }
        file.copyTo(dest)
        dest
    } catch (e: Exception) {
        null
    }

    private fun tempShareFile(source: File, suffix: String): File {
        val tempDir = Files.createTempDirectory("sbs3dfullscreen_share").toFile()
        tempDir.deleteOnExit()
        val dest = File(tempDir, "${source.nameWithoutExtension}_$suffix.jpg")
        dest.deleteOnExit()
        return dest
    }

    private fun writeJpeg(mat: Mat, dest: File) {
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", mat, buf)
        dest.writeBytes(buf.toArray())
        buf.release()
    }

    private fun extractHalf(file: File, keepLeft: Boolean): File? = try {
        val fullMat = AutoAlign.fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        val half = if (keepLeft) {
            Mat(fullMat, Rect(0, 0, halfW, h)).clone()
        } else {
            Mat(fullMat, Rect(halfW, 0, w - halfW, h)).clone()
        }
        fullMat.release()
        val bgr = Mat()
        Imgproc.cvtColor(half, bgr, Imgproc.COLOR_BGRA2BGR)
        half.release()
        val dest = tempShareFile(file, if (keepLeft) "left" else "right")
        writeJpeg(bgr, dest)
        bgr.release()
        dest
    } catch (e: Exception) {
        null
    }

    /**
     * Classic full-color anaglyph: the left eye's red channel combined with the right eye's green
     * and blue channels (OpenCV Mats are channel-ordered B,G,R throughout this codebase - see
     * AutoAlign.kt).
     */
    private fun buildAnaglyph(file: File): File? = try {
        val fullMat = AutoAlign.fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        val leftMat = Mat(fullMat, Rect(0, 0, halfW, h)).clone()
        val rightMat = Mat(fullMat, Rect(halfW, 0, w - halfW, h)).clone()
        fullMat.release()
        val leftBgr = Mat()
        Imgproc.cvtColor(leftMat, leftBgr, Imgproc.COLOR_BGRA2BGR)
        leftMat.release()
        val rightBgr = Mat()
        Imgproc.cvtColor(rightMat, rightBgr, Imgproc.COLOR_BGRA2BGR)
        rightMat.release()
        val leftChannels = ArrayList<Mat>(3)
        Core.split(leftBgr, leftChannels)
        val rightChannels = ArrayList<Mat>(3)
        Core.split(rightBgr, rightChannels)
        val anaglyph = Mat()
        Core.merge(listOf(rightChannels[0], rightChannels[1], leftChannels[2]), anaglyph)
        (leftChannels + rightChannels).forEach { it.release() }
        leftBgr.release()
        rightBgr.release()
        val dest = tempShareFile(file, "anaglyph")
        writeJpeg(anaglyph, dest)
        anaglyph.release()
        dest
    } catch (e: Exception) {
        null
    }

    /**
     * Hands [file] to the default email program via Windows Simple MAPI (MAPISendMail) - the same
     * mechanism Explorer's "Send to > Mail recipient" uses, so the file shows up already attached
     * in a new compose window rather than requiring the user to attach it by hand. MAPI_DIALOG
     * blocks until that window is sent or dismissed, so this should be called off the UI thread.
     * MAPI32.dll ships with Windows itself, but MAPISendMail fails with [EmailResult.FAILED] if no
     * Simple MAPI provider is registered - e.g. when only the new Outlook/Windows Mail UWP app is
     * installed, since neither registers as one.
     */
    fun shareViaEmail(file: File): EmailResult = try {
        val fileDesc = MapiFileDesc()
        fileDesc.nPosition = -1
        fileDesc.lpszPathName = file.absolutePath
        fileDesc.lpszFileName = file.name
        fileDesc.write()

        val message = MapiMessage()
        message.nFileCount = 1
        message.lpFiles = fileDesc.pointer

        val code = Mapi32Lib.INSTANCE.MAPISendMail(null, null, message, MapiLogonUi or MapiDialog, 0)
        when (code) {
            0 -> EmailResult.SENT
            1 -> EmailResult.CANCELLED
            else -> {
                // Surfaced only on stderr (not the UI toast) - see the MapiError* constants below
                // for what the common codes mean. Windows' MAPI32.DLL is really mapistub.dll, which
                // redirects to whichever client Settings > Apps > Default apps > Mail points at -
                // a mismatch there (e.g. Thunderbird installed but not set as the default Mail app)
                // is the most common cause of a nonzero code even when a real MAPI-capable client
                // is installed and used daily.
                System.err.println("Share.shareViaEmail: MAPISendMail failed with code $code")
                EmailResult.FAILED
            }
        }
    } catch (e: Throwable) {
        // JNA wraps its own internal reflection failures (can't read/write a Structure's Java
        // field) and native-memory read failures under the same "Exception reading/writing field"
        // message, so the top-level message alone doesn't say which - the real cause is always in
        // the "Caused by:" chain, hence the full stack trace rather than e.toString().
        System.err.println("Share.shareViaEmail: MAPISendMail threw:")
        e.printStackTrace()
        EmailResult.FAILED
    }

    private const val MapiLogonUi = 0x1
    private const val MapiDialog = 0x8

    // JNA's Structure.write()/read() access these fields via plain reflection (no setAccessible
    // step to bypass class-level privacy), so these two classes can't be Kotlin `private` - that
    // compiles to a genuinely JVM-private nested class and reflection throws IllegalAccessException
    // even though the fields themselves are public (@JvmField). Default (public) visibility here
    // still doesn't leak them outside Share.kt in practice, since they're only referenced from
    // within this object.
    @Structure.FieldOrder("ulReserved", "flFlags", "nPosition", "lpszPathName", "lpszFileName", "lpFileType")
    open class MapiFileDesc : Structure() {
        @JvmField var ulReserved: Int = 0
        @JvmField var flFlags: Int = 0
        @JvmField var nPosition: Int = -1
        @JvmField var lpszPathName: String? = null
        @JvmField var lpszFileName: String? = null
        @JvmField var lpFileType: Pointer? = null
    }

    @Structure.FieldOrder(
        "ulReserved", "lpszSubject", "lpszNoteText", "lpszMessageType", "lpszDateReceived",
        "lpszConversationID", "flFlags", "lpOriginator", "nRecipCount", "lpRecips", "nFileCount", "lpFiles",
    )
    open class MapiMessage : Structure() {
        @JvmField var ulReserved: Int = 0
        @JvmField var lpszSubject: String? = null
        @JvmField var lpszNoteText: String? = null
        @JvmField var lpszMessageType: String? = null
        @JvmField var lpszDateReceived: String? = null
        @JvmField var lpszConversationID: String? = null
        @JvmField var flFlags: Int = 0
        @JvmField var lpOriginator: Pointer? = null
        @JvmField var nRecipCount: Int = 0
        @JvmField var lpRecips: Pointer? = null
        @JvmField var nFileCount: Int = 0
        @JvmField var lpFiles: Pointer? = null
    }

    // Same visibility reasoning as MapiFileDesc/MapiMessage above: JNA implements this interface
    // with a dynamic proxy (java.lang.reflect.Proxy) and invokes its method via reflection, both of
    // which need the interface itself to not be JVM-private.
    interface Mapi32Lib : StdCallLibrary {
        fun MAPISendMail(lhSession: Pointer?, ulUIParam: Pointer?, lpMessage: MapiMessage, flFlags: Int, ulReserved: Int): Int

        companion object {
            val INSTANCE: Mapi32Lib = Native.load("MAPI32", Mapi32Lib::class.java)
        }
    }
}

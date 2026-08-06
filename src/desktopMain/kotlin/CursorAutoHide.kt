import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/**
 * A fully transparent 1x1 cursor, used to hide the real OS pointer wherever a custom cursor is
 * drawn instead - see Cursor3D.kt's Stereo3DCursorHost, which hides the real cursor for good
 * (rather than toggling it after an idle delay, like this file used to) and replaces it with a
 * stereo-duplicated custom cursor so it reads correctly in 3D.
 */
val hiddenCursor: PointerIcon by lazy {
    val blankImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(blankImage, Point(0, 0), "blank"))
}

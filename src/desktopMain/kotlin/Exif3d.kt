import fr.camera3d.camera.shared.Desc3d
import java.io.File

/**
 * File-based counterpart to CameraSync3D's Exif3d.kt: reads/writes the same EXIF3D JSON scheme
 * (Desc3d, synced verbatim from Android) through the "ImageDescription" EXIF tag.
 */
object Exif3d {
    fun get3dCameraCharacteristics(file: File): Desc3d? {
        val desc = Exif.getExifTag(file, "ImageDescription") ?: return null
        return Desc3d.parse(desc)
    }

    fun getFavoriteFromExif(file: File): Boolean {
        val desc = Exif.getExifTag(file, "ImageDescription") ?: return false
        return Desc3d.parse(desc).favorite
    }

    fun setFavoriteInExif(file: File, favorite: Boolean): Boolean {
        val existingDesc = Exif.getExifTag(file, "ImageDescription")
        val current = if (existingDesc != null) Desc3d.parse(existingDesc) else Desc3d()
        val updated = current.copy(favorite = favorite)
        return Exif.modifyExif(file, "ImageDescription", updated.toString())
    }

    fun getWarningFromExif(file: File): Boolean {
        val desc = Exif.getExifTag(file, "ImageDescription") ?: return false
        return Desc3d.parse(desc).warning
    }

    fun setWarningInExif(file: File, warning: Boolean, comment: String? = null): Boolean {
        val existingDesc = Exif.getExifTag(file, "ImageDescription")
        val current = if (existingDesc != null) Desc3d.parse(existingDesc) else Desc3d()
        val updated = if (warning) current.copy(warning = true, warningComment = comment ?: current.warningComment)
            else current.copy(warning = false, warningComment = "")
        return Exif.modifyExif(file, "ImageDescription", updated.toString())
    }

    fun getWarningCommentFromExif(file: File): String {
        val desc = Exif.getExifTag(file, "ImageDescription") ?: return ""
        return Desc3d.parse(desc).warningComment
    }

    fun getLegendFromExif(file: File): Boolean {
        val desc = Exif.getExifTag(file, "ImageDescription") ?: return false
        return Desc3d.parse(desc).hasLegend
    }

    fun setLegendInExif(file: File, hasLegend: Boolean): Boolean {
        val existingDesc = Exif.getExifTag(file, "ImageDescription")
        val current = if (existingDesc != null) Desc3d.parse(existingDesc) else Desc3d()
        val updated = current.copy(hasLegend = hasLegend)
        return Exif.modifyExifList(file, listOf("UserComment" to "", "ImageDescription" to updated.toString()))
    }

    fun setTextLegendInExif(file: File, legend: String): Boolean {
        val existingDesc = Exif.getExifTag(file, "ImageDescription")
        val updated = (if (existingDesc != null) Desc3d.parse(existingDesc) else Desc3d()).copy(hasLegend = false)
        return Exif.modifyExifList(file, listOf("UserComment" to legend, "ImageDescription" to updated.toString()))
    }
}

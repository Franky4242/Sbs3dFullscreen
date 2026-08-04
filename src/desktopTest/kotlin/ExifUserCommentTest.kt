import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.fieldtypes.AbstractFieldType
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ExifUserCommentTest {
    @Test
    fun `round trip of our own write and read matches`() {
        val dir = createTempDirectory().toFile()
        val file = File(dir, "test.jpg")
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", file)

        val comment = "légende accentuée"
        Exif.setExifUserComment(file, comment)
        assertEquals(comment, Exif.getExifUserComment(file))
    }

    /**
     * Reproduces what com.github.ddyos:UnicodeExifInterface (used by the Android sibling app,
     * CameraSync3D) writes for a JPEG UserComment: the TIFF header declares little-endian byte
     * order, but the "UNICODE\0"-prefixed payload is hardcoded to UTF-16 *big-endian* regardless.
     * A reader that trusts the file's declared byte order to pick UTF-16 endianness (as we used to,
     * via Commons Imaging's default codec) decodes those bytes as UTF-16LE, byte-swapping every
     * code unit and turning Latin text into CJK/Hangul-looking mojibake - the bug this test guards
     * against.
     */
    @Test
    fun `reads an Android-written little-endian-header UNICODE UserComment correctly`() {
        val dir = createTempDirectory().toFile()
        val file = File(dir, "android.jpg")
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", file)
        // Commons Imaging's default byte order for a brand new Exif segment is little-endian.
        Exif.setExifSoftware(file)

        val comment = "légende accentuée"
        val unicodePrefix = byteArrayOf(0x55, 0x4E, 0x49, 0x43, 0x4F, 0x44, 0x45, 0)
        val bigEndianBytes = unicodePrefix + comment.toByteArray(Charsets.UTF_16BE)

        val outputSet = (Imaging.getMetadata(file) as JpegImageMetadata).exif!!.outputSet
        val directory = outputSet.getOrCreateExifDirectory()
        val tagInfo = ExifTagConstants.EXIF_TAG_USER_COMMENT
        directory.removeField(tagInfo)
        directory.add(TiffOutputField(tagInfo, AbstractFieldType.UNDEFINED, bigEndianBytes.size, bigEndianBytes))

        val tempOut = File(dir, "android_out.jpg")
        FileOutputStream(tempOut).use { out -> ExifRewriter().updateExifMetadataLossless(file, out, outputSet) }

        assertEquals(comment, Exif.getExifUserComment(tempOut))
    }
}

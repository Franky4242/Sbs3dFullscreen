import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream

/**
 * Reads Multi Picture Object (MPO) stereo photos - the format several 3D cameras (Fujifilm, Sony,
 * ...) write natively: one file containing two complete, independent JPEG frames (left eye then
 * right eye) concatenated back to back, rather than a single side-by-side bitmap. Neither of this
 * app's existing image libraries understands that container: Apache Commons Imaging (used for
 * EXIF - see Exif.kt) has no MPO/MPF support as of 1.0.0-alpha6, and metadata-extractor doesn't
 * either as of 2.19.0 (still an open feature request, drewnoakes/metadata-extractor#654) - so
 * frame boundaries are found by hand here, walking real JPEG segment markers rather than naively
 * scanning for the next 0xFFD8/0xFFD9 byte pair. Naive scanning would misfire: the first frame's
 * own EXIF almost always embeds a thumbnail, itself a complete JPEG with its own SOI/EOI, nested
 * inside an APP1 segment.
 *
 * The rest of this app (ImageScreen's decodeToImageBitmap, GalleryScreen's ImageIO-based
 * thumbnails, the crop/align/spot-issues edit tools, ...) only ever deals with plain full-width
 * SBS JPEGs, so [resolveToSbsFile] converts once (see its doc comment) rather than teaching every
 * one of those call sites to understand MPO too.
 */
object Mpo {
    fun isMpoFile(file: File): Boolean = file.extension.equals("mpo", ignoreCase = true)

    private const val Eoi = 0xD9
    private const val Sos = 0xDA

    /** JPEG markers with no length-prefixed segment to skip (stand alone, no payload). */
    private fun isStandaloneMarker(marker: Int): Boolean = marker == 0x01 || marker in 0xD0..0xD7

    private fun u8(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 0xFF

    private fun u16(bytes: ByteArray, i: Int): Int = (u8(bytes, i) shl 8) or u8(bytes, i + 1)

    /**
     * Splits [bytes] into each complete embedded JPEG frame (SOI..EOI, inclusive) it contains, by
     * walking real JPEG segment markers: header segments (APPn/DQT/DHT/SOF/...) are skipped
     * wholesale by their own declared length - so a marker-like byte pair inside, say, an embedded
     * EXIF thumbnail never gets misread as this frame's own boundary - and the compressed scan
     * data after SOS is walked respecting 0xFF 0x00 byte-stuffing and restart markers (0xD0-0xD7),
     * so the first EOI actually found is the real one. Stops (returning whatever frames were
     * already found complete) on any malformed/truncated segment rather than emitting a partial
     * frame.
     */
    fun splitFrames(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i + 1 < bytes.size) {
            if (u8(bytes, i) != 0xFF || u8(bytes, i + 1) != 0xD8) {
                i++
                continue
            }
            val start = i
            var j = i + 2
            var frameEnd = -1
            while (j + 1 < bytes.size) {
                if (u8(bytes, j) != 0xFF) {
                    j++
                    continue
                }
                val marker = u8(bytes, j + 1)
                j += 2
                if (isStandaloneMarker(marker)) continue
                if (marker != Sos) {
                    if (j + 1 >= bytes.size) break
                    j += u16(bytes, j)
                    continue
                }
                // SOS: skip its own header segment, then walk the compressed scan data for the real EOI.
                if (j + 1 >= bytes.size) break
                j += u16(bytes, j)
                while (j + 1 < bytes.size) {
                    if (u8(bytes, j) == 0xFF) {
                        val b2 = u8(bytes, j + 1)
                        when {
                            b2 == 0x00 || b2 in 0xD0..0xD7 -> j += 2 // stuffed byte / restart marker
                            b2 == Eoi -> {
                                j += 2
                                frameEnd = j
                            }
                            else -> j += 2 // unexpected marker mid-scan - keep looking for the real EOI
                        }
                        if (frameEnd != -1) break
                    } else {
                        j++
                    }
                }
                break
            }
            if (frameEnd == -1) break
            frames.add(bytes.copyOfRange(start, frameEnd))
            i = frameEnd
        }
        return frames
    }

    private fun sbsTargetFile(mpoFile: File): File =
        File(mpoFile.parentFile, mpoFile.nameWithoutExtension + ".sbs.jpg")

    /**
     * Resolves [file] to a full-width side-by-side JPEG ready for this app's normal photo
     * pipeline: returns [file] itself unless it's an .mpo, in which case its first two frames
     * (left eye, right eye) are decoded, composed side by side, and written to a ".sbs.jpg"
     * sibling - the same double-extension naming this app already recognizes everywhere else (see
     * GalleryScreen.kt's/AutoAlign.kt's sbsDoubleExtensions). Skips the actual conversion (just
     * returns the existing sibling) if it's already at least as new as [file] - the same
     * lastModified-guard idea as GalleryScreen's ThumbnailCache, so re-opening the same .mpo
     * doesn't re-decode/re-encode it every time. Falls back to returning [file] unchanged if
     * conversion fails for any reason (fewer than 2 frames found, a frame that doesn't decode,
     * ...) - the caller then just tries to open the original, which fails loudly rather than
     * silently substituting nothing.
     */
    fun resolveToSbsFile(file: File): File {
        if (!isMpoFile(file)) return file
        val target = sbsTargetFile(file)
        if (target.exists() && target.lastModified() >= file.lastModified()) return target
        return runCatching { convert(file, target) }.getOrDefault(file)
    }

    private fun convert(mpoFile: File, target: File): File {
        val frameBytes = splitFrames(mpoFile.readBytes())
        require(frameBytes.size >= 2) { "MPO file has fewer than 2 frames: ${mpoFile.name}" }
        val left = ImageIO.read(frameBytes[0].inputStream()) ?: error("Could not decode MPO left frame: ${mpoFile.name}")
        val right = ImageIO.read(frameBytes[1].inputStream()) ?: error("Could not decode MPO right frame: ${mpoFile.name}")
        val combined = BufferedImage(left.width + right.width, maxOf(left.height, right.height), BufferedImage.TYPE_INT_RGB)
        val g = combined.createGraphics()
        g.drawImage(left, 0, 0, null)
        g.drawImage(right, left.width, 0, null)
        g.dispose()
        writeJpeg(combined, target)
        Exif.copyExif(mpoFile, target)
        return target
    }

    /** Writes [image] as a JPEG at ImageIO's max-minus-a-hair quality - its own default (~0.75) is
     *  noticeably lossier than what a camera or this app's other save paths (OpenCV, quality 95) produce. */
    private fun writeJpeg(image: BufferedImage, target: File, quality: Float = 0.95f) {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        FileImageOutputStream(target).use { output ->
            writer.output = output
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
    }
}

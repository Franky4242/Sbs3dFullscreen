import fr.camera3d.camera.shared.nextAvailableSuffixedFilename
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class FilenameIncrementTest {
    @Test
    fun `prefix listing picks next free number ignoring gaps`() {
        val dir = createTempDirectory().toFile()
        File(dir, "photo_edited.jpg").createNewFile()
        File(dir, "photo_edited2.jpg").createNewFile()
        val (name, shift) = nextAvailableSuffixedFilename(
            File(dir, "photo.jpg"), "photo_", "edited", ".jpg", 0
        )
        assertEquals("photo_edited3.jpg", name)
        assertEquals(2, shift)
    }

    @Test
    fun `no existing files gives first suffix without number`() {
        val dir = createTempDirectory().toFile()
        val (name, shift) = nextAvailableSuffixedFilename(
            File(dir, "photo.jpg"), "photo_", "edited", ".jpg", 0
        )
        assertEquals("photo_edited.jpg", name)
        assertEquals(0, shift)
    }

    @Test
    fun `android style edited suffix with currentSuffixNumber`() {
        val dir = createTempDirectory().toFile()
        val base = "2026-01-01_120000_0001_"
        File(dir, "${base}edited2.sbs.jpg").createNewFile()
        // Re-editing the file currently named "..._edited2.sbs.jpg" (currentSuffixNumber=2)
        val (name, shift) = nextAvailableSuffixedFilename(
            File(dir, "${base}edited2.sbs.jpg"), base, "edited", ".sbs.jpg", 2
        )
        assertEquals("${base}edited3.sbs.jpg", name)
        assertEquals(0, shift)
    }

    @Test
    fun `fallback to sequential probing when directory cannot be listed`() {
        val dir = createTempDirectory().toFile()
        File(dir, "photo_edited.jpg").createNewFile()
        val missingParent = File(dir, "nonexistent-subdir")
        val (name, shift) = nextAvailableSuffixedFilename(
            File(missingParent, "photo.jpg"), "photo_", "edited", ".jpg", 0
        )
        // missingParent doesn't exist, so .list() returns null -> fallback probes via File.exists(),
        // which also can't find a colliding file under a nonexistent dir, so first candidate wins.
        assertEquals("photo_edited.jpg", name)
        assertEquals(0, shift)
    }

    // AutoAlign.nextAvailableFile is the desktop caller that decomposes a real file name into
    // baseName/suffix/ext before delegating to nextAvailableSuffixedFilename above - these cover
    // that decomposition, which is where the actual bugs were (suffix landing before ".jpg"
    // instead of the full ".sbs.jpg"/".jps.jpg", and not replacing an existing "_raw"/"editedN").

    @Test
    fun `AutoAlign suffix lands before the full sbs double extension, not before jpg`() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "2026-01-01_120000_0001__raw.sbs.jpg")
        val result = AutoAlign.nextAvailableFile(source)
        assertEquals("2026-01-01_120000_0001_edited.sbs.jpg", result.name)
    }

    @Test
    fun `AutoAlign replaces an existing edited-N marker instead of appending after it`() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "2026-01-01_120000_0001_edited2.jps.jpg")
        source.createNewFile()
        val result = AutoAlign.nextAvailableFile(source)
        assertEquals("2026-01-01_120000_0001_edited3.jps.jpg", result.name)
    }

    @Test
    fun `AutoAlign keeps original behavior for an arbitrary jpeg without a marker`() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "myphoto.jpg")
        val result = AutoAlign.nextAvailableFile(source)
        assertEquals("myphoto_edited.jpg", result.name)
    }

    @Test
    fun `AutoAlign re-running increments rather than colliding with the source file itself`() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "myphoto_edited.jpg")
        source.createNewFile()
        val result = AutoAlign.nextAvailableFile(source)
        assertEquals("myphoto_edited2.jpg", result.name)
    }

    @Test
    fun `AutoAlign leaves an unrelated filename merely ending in edited alone`() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "unedited.jpg")
        val result = AutoAlign.nextAvailableFile(source)
        assertEquals("unedited_edited.jpg", result.name)
    }
}

import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistType
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlaylistStorageTest {

    @Test
    fun `loadPlaylist falls back to a disk scan when no index file exists`() {
        val dir = createTempDirectory("playlist-test").toFile()
        try {
            File(dir, "b.jpg").writeText("not a real jpeg, just needs to exist")
            File(dir, "a.jpeg").writeText("not a real jpeg, just needs to exist")
            File(dir, "notes.txt").writeText("should be ignored")

            val playlist = Playlist.loadPlaylist(DesktopPlaylistStorage(dir.parentFile), dir.name)

            assertEquals(PlaylistType.EXTERNAL_STORAGE, playlist.type)
            assertEquals(listOf("a.jpeg", "b.jpg"), playlist.photos.map { it.filename })
            for (photo in playlist.photos) {
                assertTrue(playlistItemFile(photo.imageUriString).exists(), "resolved file for ${photo.filename} should exist")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `save then loadPlaylist round-trips the YAML index`() {
        val dir = createTempDirectory("playlist-roundtrip-test").toFile()
        try {
            File(dir, "photo1.jpg").writeText("stub")
            val storage = DesktopPlaylistStorage(dir.parentFile)
            val original = Playlist.loadPlaylist(storage, dir.name)
                .copy(name = "My Playlist", defaultDurationS = 7, isAutomated = false)
            original.save(storage)

            assertTrue(File(dir, "playlistIndex.yaml").exists())

            val reloaded = Playlist.loadPlaylist(storage, dir.name)
            assertEquals("My Playlist", reloaded.name)
            assertEquals(7, reloaded.defaultDurationS)
            assertEquals(false, reloaded.isAutomated)
            assertEquals(listOf("photo1.jpg"), reloaded.photos.map { it.filename })
        } finally {
            dir.deleteRecursively()
        }
    }
}

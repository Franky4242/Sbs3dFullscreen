package fr.camera3d.camera.feature_playlists.domain

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

/**
 * Storage primitives Playlist/PlaylistItem need, factored out so the domain classes stay
 * portable: SAF-vs-plain-storage dispatch (Android) vs plain java.io.File (Desktop) both hide
 * behind this interface. AndroidPlaylistStorage implements it by wrapping PlaylistsDirectories.kt;
 * the desktop app supplies its own plain-File implementation.
 */
interface PlaylistStorage {
    fun getPlaylistType(playlistDirName: String): PlaylistType
    fun getFullFolderPath(playlistDirName: String): String
    fun indexFileExists(playlistDirName: String): Boolean
    fun readIndexFile(playlistDirName: String): String
    fun writeIndexFile(playlistDirName: String, absoluteFolderPath: String, yamlContent: String)
    fun listJpegs(playlistDirName: String): List<Pair<String, String>>
    fun resolvePhotoUri(fullPlaylistFolder: String, playlistDirName: String, filename: String, withFileVerification: Boolean): String
    fun resolveAbsoluteFolder(fullPlaylistFolder: String, playlistDirName: String, yamlIndexName: String): String
}

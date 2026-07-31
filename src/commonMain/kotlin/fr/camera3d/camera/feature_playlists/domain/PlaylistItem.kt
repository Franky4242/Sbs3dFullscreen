package fr.camera3d.camera.feature_playlists.domain

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

/**
 * when modifying check YamlImporter and create
 * imageUriString holds a "content:..." or "file:..." URI as plain text (not android.net.Uri, so
 * this class stays portable) -- callers that need a Uri object convert via .toUri() at the edge.
 */
data class PlaylistItem(val filename : String,
                        val imageUriString : String,
                        val comment : String = "",
                        val commentAnchor : String = "",
                        val commentZPercent : Float =0f,
                        val commentColor: String="",
                        val transition : String="",
                        val durationS : Int = -1,
                        val isHalfWidth: Boolean = false)
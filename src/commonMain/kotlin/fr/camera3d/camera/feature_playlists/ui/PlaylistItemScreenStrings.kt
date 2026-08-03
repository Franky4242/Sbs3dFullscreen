package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Every string the shared playlist-item composables (PlaylistItemFieldsComposables.kt) need,
// pre-resolved by the caller (stringResource(R.string.x) on Android, stringResource(Res.string.x)
// on desktop) so the shared composables never reference either platform's resource system.
// Sync via tools/sync-from-android.ps1.

data class PlaylistItemScreenStrings(
    val ok: String,
    val saveButton: String,
    val cancel: String,
    val comment: String,
    val editComment: String,
    val pressHereToSetAComment: String,
    val zAltitudePercent: String,
    val commentZDocumentation: String,
    val editZAltitudePercent: String,
    val errorZMustBeAFloat: String,
    val duration: String,
    val editDuration: String,
    val usePlaylistDefaultValue: String,
    val durationMustBePositive: String,
    val durationManualModeWarning: String,
    val halfWidth: String,
    val halfWidthDocumentation: String,
    val deletePhotoOption: String,
)

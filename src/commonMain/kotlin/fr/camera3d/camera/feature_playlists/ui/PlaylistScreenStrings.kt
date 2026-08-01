package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Every string the shared playlist-editor composables (PlaylistFieldsComposables.kt) need,
// pre-resolved by the caller (stringResource(R.string.x) on Android, stringResource(Res.string.x)
// on desktop) so the shared composables never reference either platform's resource system.
// errorWhileSavingPlaylist/deletePlaylistConfirmMessage are format templates (String.format
// placeholders), applied by the composable that needs them. Sync via tools/sync-from-android.ps1.

data class PlaylistScreenStrings(
    val ok: String,
    val saveButton: String,
    val cancel: String,
    val name: String,
    val editPlaylistName: String,
    val playlistNameAlreadyExists: String,
    val noModificationMade: String,
    val nameCannotBeEmpty: String,
    val playlistRenamed: String,
    val cannotSavePlaylist: String,
    val errorWhileSavingPlaylist: String,
    val subtitle: String,
    val pressHereToSetASubtitle: String,
    val editSubtitle: String,
    val titleZAltitudePercent: String,
    val titleZ: String,
    val editTitleZAltitudePercent: String,
    val errorZMustBeAFloat: String,
    val subtitleZAltitudePercent: String,
    val subtitleZ: String,
    val editSubtitleZAltitudePercent: String,
    val automatedSlideshow: String,
    val defaultDuration: String,
    val modifySlideshowDefaultDurationBetweenImages: String,
    val defaultDurationShouldBe0And60000Ms: String,
    val deletePlaylistOption: String,
    val deletePlaylistConfirmTitle: String,
    val deletePlaylistConfirmOk: String,
    val deletePlaylistConfirmMessage: String,
)

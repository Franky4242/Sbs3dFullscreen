package fr.camera3d.camera.feature_playlists.service.yamlImporter

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

import fr.camera3d.camera.feature_playlists.domain.Playlist
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * DEFINITION OF THE YAML FORMAT TO STORE PLAYLISTS
 * serialize and deserialize functions from Playlist to YAML and vice versa
 *
 * to change the YAML index file format
 * 1) change YamlPlaylistPhotoV1 or YamlPlaylistV1
 * 2) change PlaylistItem or PlaylistState
 * 3) change createFromYamlPlaylist() in PlaylistViewModel.kt
 */

/**
 * YAML definition for playlist items
 */
data class YamlPlaylistItemV1(val filename : String,
                              val comment : String = "",
                              val commentAnchor : String = "",
                              val commentColor : String = "",
                              val commentZ : Float = 0f,
                              val transition : String = "",
                              val duration : Int = -1,
                              val isHalfWidth : Boolean = false)

/**
 * YAML definition for playlists
 */
data class YamlPlaylistV1(val formatVersion : Float,
                          val name : String ,
                          val defaultDuration : Long = 2000,
                          val defaultTransition : String = "",
                          val soundFilename : String = "",
                          val subtitle : String = "",
                          val titleZ : Float = 0f,
                          val subtitleZ : Float = 0f,
                          val isAutomated : Boolean = true,
                          val photos : List<YamlPlaylistItemV1> = listOf() // list of the playlist photos
)

fun getYamlPlaylistFromYaml(yamlDocument : String): YamlPlaylistV1{
    val mapper = YAMLMapper.builder().addModule(kotlinModule()).build() // Enable YAML parsing with Kotlin support
    val playlist = mapper.readValue(yamlDocument, YamlPlaylistV1::class.java)
    if (playlist.formatVersion != 1.0f){
        throw Exception("expected version 1.0")
    }
    else{
        return playlist
    }
}

/**
 * Returns a YAML string representing the Playlist
 */
fun PlaylistToYAML(p: Playlist) : String{
    val playlistCore = "# Playlist index for CameraSync3D\n" +
            "formatVersion : 1.0\n" +
            "name : \"${p.name}\"\n" +
            "defaultDuration : ${p.defaultDurationS}\n" +
            if (p.defaultTransition != "") {"defaultTransition : \"${p.defaultTransition}\"\n"} else {""} +
            if (p.soundFilename != "") {"soundFilename : ${p.soundFilename}\n"} else {""} +
            if (p.subtitle != "") {"subtitle : \"${p.subtitle}\"\n"} else {""} +
            if (p.titleZPercent != 0f) {"titleZ : ${p.titleZPercent}\n"} else {""} +
            if (p.subtitleZPercent != 0f) {"subtitleZ : ${p.subtitleZPercent}\n"} else {""} +
            if (!p.isAutomated) {"isAutomated : false\n"} else {""} +
            if (p.photos.isNotEmpty()) {"photos :\n"} else {""}
    val items = p.photos.map {"- filename : \"${it.filename}\"\n" + // WARNING 1+ space after ":" is important
            "  comment : \"${it.comment}\"\n" +
            if (it.commentColor != "") {"  commentAnchor : \"${it.commentAnchor}\"\n"} else {""} +
            if (it.commentColor != "") {"  commentColor : \"${it.commentColor}\"\n"} else {""} +
            if (it.commentZPercent != 0f) {"  commentZ : ${it.commentZPercent}\n"} else {""} +
            if (it.durationS != -1) {"  duration : ${it.durationS}\n"} else {""} + // WARNING { } are important. Otherwise do not go to next line
            if (it.transition != "") {"  transition : \"${it.transition}\"\n"} else {""} +
            if (it.isHalfWidth) {"  isHalfWidth : true\n"} else {""}
    }
    val itemsStr = items.joinToString("")
    return "$playlistCore$itemsStr"
}

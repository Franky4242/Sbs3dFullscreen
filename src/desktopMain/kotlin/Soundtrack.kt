import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import fr.camera3d.camera.feature_playlists.domain.Playlist
import java.io.File

/**
 * Plays a playlist soundtrack (its audio files one after the other, stopping after the last one) on
 * its own libVLC player, like CameraSync3D's SlideshowViewModel does with ExoPlayer. Needs VLC
 * installed, like video playback: without it every call is a silent no-op.
 */
class SoundtrackPlayer(private val tracks: List<File>, private var audioOutputDeviceId: String) {
    private var instance: VlcInstance? = null
    private var player: VlcPlayer? = null
    private var nextTrackIndex = 0
    private var muted = false
    @Volatile private var released = false

    /** (Re)starts the soundtrack from its first track. */
    fun start() = Vlc.worker.execute {
        if (released || tracks.isEmpty()) return@execute
        val player = player ?: createPlayer() ?: return@execute
        player.setMute(muted)
        nextTrackIndex = 0
        playNextTrack(player)
    }

    /** Stops the soundtrack; the next [start] begins again from the first track. */
    fun stop() = Vlc.worker.execute {
        nextTrackIndex = tracks.size
        player?.stop()
    }

    /** Mutes the soundtrack while a video with its own sound is shown, so the two don't overlap. */
    fun setMuted(muted: Boolean) = Vlc.worker.execute {
        this.muted = muted
        player?.setMute(muted)
    }

    fun setAudioOutputDevice(deviceId: String) = Vlc.worker.execute {
        audioOutputDeviceId = deviceId
        player?.setOutputDevice(null, deviceId)
    }

    fun release() {
        released = true
        Vlc.worker.execute {
            player?.release()
            instance?.release()
            player = null
            instance = null
        }
    }

    private fun createPlayer(): VlcPlayer? = runCatching {
        val factory = VlcInstance().also { instance = it }
        factory.newPlayer().also { newPlayer ->
            if (audioOutputDeviceId.isNotEmpty()) newPlayer.setOutputDevice("mmdevice", audioOutputDeviceId)
            // Called on a libVLC thread, which must not call back into the player: bounce to the worker.
            newPlayer.onFinished = { Vlc.worker.execute { if (!released) playNextTrack(newPlayer) } }
            player = newPlayer
        }
    }.getOrNull()

    private fun playNextTrack(player: VlcPlayer) {
        val track = tracks.getOrNull(nextTrackIndex++) ?: return
        if (track.isFile) player.play(track.absolutePath) else playNextTrack(player)
    }
}

/**
 * Runs the playlist's soundtrack while a slideshow plays: starts it from the first track when
 * [playing] becomes true (first photo reached), stops it when it turns false (title/end slide) and
 * when this leaves the composition. [mutedForVideo] silences it over a video that has its own sound.
 */
@Composable
fun PlaylistSoundtrack(playlist: Playlist, playing: Boolean, mutedForVideo: Boolean, audioOutputDeviceId: String) {
    val player = remember(playlist.absolutePath, playlist.soundtrack) {
        SoundtrackPlayer(playlist.soundtrack.map { File(playlist.absolutePath, it) }, audioOutputDeviceId)
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, playing) { if (playing) player.start() else player.stop() }
    LaunchedEffect(player, mutedForVideo) { player.setMuted(mutedForVideo) }
    LaunchedEffect(player, audioOutputDeviceId) { player.setAudioOutputDevice(audioOutputDeviceId) }
}

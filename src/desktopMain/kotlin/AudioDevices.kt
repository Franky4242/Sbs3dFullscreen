/**
 * The mmdevice (Windows) devices libVLC can send audio to, for pickers shown outside a video
 * (see WelcomeScreen's settings dialog) - VideoScreen enumerates the same list from its own
 * libVLC instance. Includes libVLC's own "default" entry (empty deviceId). Returns an empty list
 * if VLC isn't installed or can't be loaded.
 */
fun listAudioOutputDevices(): List<AudioDevice> = try {
    val factory = VlcInstance()
    try {
        factory.audioDevices()
    } finally {
        factory.release()
    }
} catch (e: Throwable) {
    emptyList()
}

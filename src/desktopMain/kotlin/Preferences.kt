import java.util.prefs.Preferences

// Shared by every *Pref class below - all of AppViewModel's persisted settings used to live under
// this same node (Preferences.userNodeForPackage(AppViewModel::class.java)), one per duplicated
// *Preference object; centralizing it here doesn't change where anything is stored.
private val appPrefs: Preferences = Preferences.userNodeForPackage(AppViewModel::class.java)

/**
 * Generic get/set wrapper around java.util.prefs.Preferences - replaces the seven near-identical
 * `private object FooPreference { ... }` blocks AppViewModel.kt used to carry (one per persisted
 * setting: halveLeftRightImages, keepBestOfEachOnly, shrinkControls, manualAlignStepPercent,
 * audioOutputDeviceId, shareType, shareDestination). Each call site keeps the exact same key
 * string/default it used before, so a settings file written by an older build still loads
 * correctly under this consolidated version.
 */
class BooleanPref(private val key: String, private val default: Boolean) {
    fun load(): Boolean = appPrefs.getBoolean(key, default)
    fun save(value: Boolean) = appPrefs.putBoolean(key, value)
}

/** Same shape as [BooleanPref], for a Float-valued setting (today: manualAlignStepPercent). */
class FloatPref(private val key: String, private val default: Float) {
    fun load(): Float = appPrefs.getFloat(key, default)
    fun save(value: Float) = appPrefs.putFloat(key, value)
}

/** Same shape as [BooleanPref], for a String-valued setting (today: audioOutputDeviceId). */
class StringPref(private val key: String, private val default: String) {
    fun load(): String = appPrefs.get(key, default)
    fun save(value: String) = appPrefs.put(key, value)
}

/**
 * Same shape as [BooleanPref], for a setting persisted by its [Enum.name] (today: shareType/
 * shareDestination). Falls back to [default] if the stored value doesn't parse - e.g. an enum
 * constant renamed since it was saved - mirroring the try/catch every old *Preference.load() for
 * an enum used to repeat individually.
 */
class EnumPref<T : Enum<T>>(private val key: String, private val default: T, private val valueOf: (String) -> T) {
    fun load(): T = try {
        valueOf(appPrefs.get(key, default.name))
    } catch (e: IllegalArgumentException) {
        default
    }

    fun save(value: T) = appPrefs.put(key, value.name)
}

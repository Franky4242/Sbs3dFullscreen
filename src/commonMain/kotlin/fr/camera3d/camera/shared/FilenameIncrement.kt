package fr.camera3d.camera.shared

import java.io.File

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

/**
 * Finds a filename of the form "<baseName><suffix><N><ext>" (N omitted the first time, i.e. just
 * "<baseName><suffix><ext>") that doesn't collide with any file in [existingFile]'s parent
 * directory. <baseName>/<suffix>/<ext> are supplied by the caller since each app slices its own
 * filenames differently (CameraSync3D's captures use a fixed "YYYY-MM-DD_HHmmss_frameId_"
 * baseName and a fixed 8-char ".jps.jpg"/".sbs.jpg" ext; sbs3Dfullscreen accepts arbitrary
 * baseName/ext since Windows opens arbitrarily-named JPEGs). [currentSuffixNumber] is the number
 * already encoded in [existingFile]'s own name, or 0 if it isn't itself already suffixed.
 *
 * Preferred strategy: lists [existingFile]'s parent directory once for names already matching
 * "<baseName><suffix>*<ext>" and picks one past the highest N found there - so the result doesn't
 * depend only on [currentSuffixNumber]; a differently-numbered copy already on disk (from a
 * previous session, a restored backup, ...) is still avoided.
 * Fallback (used if the parent directory can't be listed, e.g. denied on some Android storage
 * paths even though individual file existence checks are allowed): walks forward one candidate at
 * a time from [currentSuffixNumber], probing the file system directly via [File.exists] until a
 * free name is found.
 *
 * Returns the new filename (not a full path) and how many numbers were skipped past
 * [currentSuffixNumber] - callers use this to reposition the new file in a sorted list next to the
 * one it was edited from.
 */
fun nextAvailableSuffixedFilename(
    existingFile: File,
    baseName: String,
    suffix: String,
    ext: String,
    currentSuffixNumber: Int,
): Pair<String, Int> {
    fun nameForNumber(n: Int) = "$baseName$suffix${if (n <= 1) "" else "$n"}$ext"

    val parent = existingFile.absoluteFile.parentFile
    val siblings = parent?.list()

    if (siblings != null) {
        val pattern = Regex("^${Regex.escape(baseName)}${Regex.escape(suffix)}(\\d*)${Regex.escape(ext)}$")
        val highestExisting = siblings.maxOfOrNull { name ->
            pattern.matchEntire(name)?.groupValues?.get(1)?.let { if (it.isEmpty()) 1 else it.toIntOrNull() } ?: 0
        } ?: 0
        val next = maxOf(highestExisting, currentSuffixNumber) + 1
        return nameForNumber(next) to (next - currentSuffixNumber - 1)
    }

    var num = currentSuffixNumber
    var shift = 0
    var candidate = nameForNumber(num + 1)
    while (File(parent, candidate).exists()) {
        num += 1
        shift += 1
        candidate = nameForNumber(num + 1)
    }
    return candidate to shift
}

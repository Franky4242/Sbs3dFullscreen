package fr.camera3d.camera.shared

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

/**
 * Pure formatting/parsing helpers extracted from Exif.kt : the parts of EXIF handling that
 * don't need Context/Uri/ContentResolver access. The tag lists here mirror what the
 * Context/Uri-bound I/O layer (Exif.kt on Android, its desktop equivalent) reads and writes.
 */

private const val TAG = "ExifFormatting"

val exifAttributes = arrayOf(
    "ApertureValue",
    "Artist",
    "BitsPerSample",
    "BrightnessValue",
    "CFAPattern",
    "ColorSpace",
    "ComponentsConfiguration",
    "CompressedBitsPerPixel",
    "Compression",
    "Contrast",
    "Copyright",
    "CustomRendered",
    "DateTime",
    "DateTimeDigitized",
    "DateTimeOriginal",
    "DefaultCropSize",
    "DeviceSettingDescription",
    "DigitalZoomRatio",
    "DNGVersion",
    "ExifVersion",
    "ExposureBiasValue",
    "ExposureIndex",
    "ExposureMode",
    "ExposureProgram",
    "ExposureTime",
    "FileSource",
    "Flash",
    "FlashpixVersion",
    "FlashEnergy",
    "FocalLength",
    "FocalLengthIn35mmFilm",
    "FocalPlaneResolutionUnit",
    "FocalPlaneXResolution",
    "FocalPlaneYResolution",
    "FNumber",
    "GainControl",
    "GPSAltitude",
    "GPSAltitudeRef",
    "GPSAreaInformation",
    "GPSDateStamp",
    "GPSDestBearing",
    "GPSDestBearingRef",
    "GPSDestDistance",
    "GPSDestDistanceRef",
    "GPSDestLatitude",
    "GPSDestLatitudeRef",
    "GPSDestLongitude",
    "GPSDestLongitudeRef",
    "GPSDifferential",
    "GPSDOP",
    "GPSImgDirection",
    "GPSImgDirectionRef",
    "GPSLatitude",
    "GPSLatitudeRef",
    "GPSLongitude",
    "GPSLongitudeRef",
    "GPSMapDatum",
    "GPSMeasureMode",
    "GPSProcessingMethod",
    "GPSSatellites",
    "GPSSpeed",
    "GPSSpeedRef",
    "GPSStatus",
    "GPSTimeStamp",
    "GPSTrack",
    "GPSTrackRef",
    "GPSVersionID",
    "ImageDescription",
    "ImageLength",
    "ImageUniqueID",
    "ImageWidth",
    "InteroperabilityIndex",
    "ISOSpeedRatings",
    "JPEGInterchangeFormat",
    "JPEGInterchangeFormatLength",
    "LightSource",
    "Make",
    "MakerNote",
    "MaxApertureValue",
    "MeteringMode",
    "Model",
    "NewSubfileType",
    "OECF",
    "AspectFrame",
    "PreviewImageLength",
    "PreviewImageStart",
    "ThumbnailImage",
    "Orientation",
    "PhotometricInterpretation",
    "PixelXDimension",
    "PixelYDimension",
    "PlanarConfiguration",
    "PrimaryChromaticities",
    "ReferenceBlackWhite",
    "RelatedSoundFile",
    "ResolutionUnit",
    "RowsPerStrip",
    "ISO",
    "JpgFromRaw",
    "SensorBottomBorder",
    "SensorLeftBorder",
    "SensorRightBorder",
    "SensorTopBorder",
    "SamplesPerPixel",
    "Saturation",
    "SceneCaptureType",
    "SceneType",
    "SensingMethod",
    "Sharpness",
    "ShutterSpeedValue",
    "Software",
    "SpatialFrequencyResponse",
    "SpectralSensitivity",
    "StripByteCounts",
    "StripOffsets",
    "SubfileType",
    "SubjectArea",
    "SubjectDistance",
    "SubjectDistanceRange",
    "SubjectLocation",
    "SubSecTime",
    "SubSecTimeDigitized",
    "SubSecTimeDigitized",
    "SubSecTimeOriginal",
    "SubSecTimeOriginal",
    "ThumbnailImageLength",
    "ThumbnailImageWidth",
    "TransferFunction",
    "UserComment",
    "WhiteBalance",
    "WhitePoint",
    "XResolution",
    "YCbCrCoefficients",
    "YCbCrPositioning",
    "YCbCrSubSampling",
    "YResolution"
)

val exifGpsAttributes = arrayOf(
    "GPSAltitude",
    "GPSAltitudeRef",
    "GPSDateStamp",
    "GPSLatitude",
    "GPSLatitudeRef",
    "GPSLongitude",
    "GPSLongitudeRef",
    "GPSProcessingMethod",
    "GPSSpeed",
    "GPSSpeedRef",
    "GPSTimeStamp"
)

/**
 * Minimal portable stand-in for android.util.Rational, parsed from EXIF-style
 * "numerator/denominator" strings (android.util.Rational isn't available outside Android).
 */
data class ExifRational(val numerator: Int, val denominator: Int) {
    fun toDouble(): Double = numerator.toDouble() / denominator
    fun toFloat(): Float = numerator.toFloat() / denominator
}

fun parseExifRational(s: String): ExifRational {
    val parts = s.split("/")
    return if (parts.size == 2) {
        ExifRational(parts[0].trim().toInt(), parts[1].trim().toInt())
    } else {
        ExifRational(s.trim().toInt(), 1)
    }
}

/**
 * parses a gps string coming from Exif latitude or longitude
 * returns a readable latitude or longitude string like 48°34'23,003''
 * @param gpsExifStr : an Exif string for latitude or longitude
 */
fun parseGpsExif(gpsExifStr : String): String{
    val degMinSec = gpsExifStr.splitToSequence(",").toList()
    if (degMinSec.size !=3){
        return gpsExifStr
    }
    else{
        try {
            val deg = parseExifRational(degMinSec[0])
            val min = parseExifRational(degMinSec[1])
            val sec = parseExifRational(degMinSec[2])
            if (deg.denominator == 1 && min.denominator == 1){
                return deg.numerator.toString()+"°"+min.numerator.toString()+"'"+sec.toFloat().toString()+"''"
            }
        }
        catch(e : Exception){
            System.err.println("$TAG: Error in parseGpsExif : $e")
        }
    }
    return gpsExifStr
}

/**
 * converts a latitude or longitude expresses with 3 rationals degrees, minutes, seconds into a double
 * @param degrees
 * @param minutes
 * @param seconds
 */
fun rationalLatLongToDouble(degrees : ExifRational, minutes : ExifRational, seconds : ExifRational) : Double{
    return degrees.toDouble()+minutes.toDouble()/60+seconds.toDouble()/3600
}

/**
 * converts a gpsExifString representing a latitude or longitude into a Double
 * Useful for geocoder
 * TODO : there may be a bug because it does not take into account N, E, W, S (gpsLatitudeRef or gpsLongitudeRef)
 * @param gpsExifStr : an Exif string for latitude or longitude
 */
fun gpsExifStrLatLongToDouble(gpsExifStr : String): Double?{
    val degMinSec = gpsExifStr.splitToSequence(",").toList()
    if (degMinSec.size !=3){
        System.err.println("$TAG: Cannot get Degrees, Minutes, Seconds from GPS string : $gpsExifStr")
    }
    else{
        try {
            val deg = parseExifRational(degMinSec[0])
            val min = parseExifRational(degMinSec[1])
            val sec = parseExifRational(degMinSec[2])
            if (deg.denominator == 1 && min.denominator == 1){
                return rationalLatLongToDouble(deg, min, sec)
            }
        }
        catch(e : Exception){
            System.err.println("$TAG: Error in parseGpsExif : $e")
        }
    }
    return null
}

fun formatExposureTime(time : String?): String {
    return if (time == null) ""
    else {
        formatExposureTime(time.toDouble())
    }
}

fun formatExposureTime(value: Double): String {
    var output: String
    if (value < 1.0f) {
        output = String.format(java.util.Locale.getDefault(), "%d/%d", 1, (0.5f + 1 / value).toInt())
    } else {
        val integer = value.toInt()
        val time = value - integer
        output = String.format(java.util.Locale.getDefault(), "%d''", integer)

        if (time > 0.0001f) {
            output += String.format(java.util.Locale.getDefault(), " %d/%d", 1, (0.5f + 1 / time).toInt())
        }
    }

    return output
}

fun decodeFlashExifCode(code : String?): String {
    if (code == null || code == "-"){
        return "-"
    }
    val codeInt = code.toInt()
    val bit1 = codeInt.mod(2)
    val bit23 = (codeInt - bit1).mod(8)/2
    val flashMode = when((codeInt - bit1 - 2*bit23).mod(32)/8){
        0 -> ""
        1 -> " (compulsory mode)"
        2 -> " (no flash mode)"
        3 -> " (auto)"
        else -> throw(Exception("Exif Flash bit45 should be 0 to 3"))
    }
    return when (bit1){
        0 -> "No$flashMode"
        1 -> "Yes$flashMode"
        else -> throw(Exception("Exif Flash bit1 should be 0 or 1"))
    }
}

package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Maps a TextStyleConfig (domain model) to a Compose TextStyle, and positions it in a
// top/left/right-% bounding box (CSS `position: absolute` convention: box width is
// `100% - left% - right%`). Used by every title-slide render path (Side-by-Side, Beamer,
// Anaglyph) and by the style editor's live preview, so all of them share one positioning
// implementation. Deliberately has no android.* imports (parses colors by hand instead of
// androidx.core.graphics.toColorInt) so it stays portable. Sync via tools/sync-from-android.ps1.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.camera3d.camera.feature_playlists.domain.TextFontFamily
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig

/** Parses a "#RRGGBB"/"#AARRGGBB" hex string into a Color, or [fallback] if it's not valid. */
fun parseHexColor(hex: String, fallback: Color): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val argb = when (cleaned.length) {
            6 -> (0xFF000000L or cleaned.toLong(16)).toInt()
            8 -> cleaned.toLong(16).toInt()
            else -> return fallback
        }
        Color(argb)
    } catch (e: Exception) {
        fallback
    }
}

fun TextStyleConfig.toComposeTextStyle(): TextStyle {
    val family = when (fontFamily) {
        TextFontFamily.DEFAULT -> FontFamily.Default
        TextFontFamily.SERIF -> FontFamily.Serif
        TextFontFamily.SANS_SERIF -> FontFamily.SansSerif
        TextFontFamily.MONOSPACE -> FontFamily.Monospace
        TextFontFamily.CURSIVE -> FontFamily.Cursive
    }
    return TextStyle(
        fontFamily = family,
        fontSize = fontSizeSp.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
        color = parseHexColor(colorHex, Color.White),
        textAlign = TextAlign.Center,
    )
}

/**
 * Positions [content] (the actual text-drawing composable, e.g. a plain `Text` or
 * `ComposableAnaglyphText`) inside the bounding box described by [style]'s top/left/right
 * percentages, with an optional background rect behind it, and [extraShiftDp] layered on top
 * as an additional horizontal offset (the existing stereo depth-shift convention: callers pass
 * `-shift/2`/`shift/2` for the left/right eye, same as today's hardcoded offsets).
 */
@Composable
fun ComposableStyledPositionedText(
    style: TextStyleConfig,
    extraShiftDp: Dp = 0.dp,
    content: @Composable (TextStyle, Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxWidthFraction = (1f - (style.leftPercent + style.rightPercent) / 100f).coerceIn(0f, 1f)
        val boxWidth = maxWidth * boxWidthFraction
        val boxLeft = maxWidth * (style.leftPercent / 100f) + extraShiftDp
        val boxTop = maxHeight * (style.topPercent / 100f).coerceIn(0f, 1f)
        val backgroundColor = parseHexColor(style.backgroundColorHex, Color.Black)
            .copy(alpha = (style.backgroundOpacityPercent / 100f).coerceIn(0f, 1f))
        Box(
            modifier = Modifier
                .offset(x = boxLeft, y = boxTop)
                .width(boxWidth)
                .then(if (style.backgroundOpacityPercent > 0f) Modifier.background(backgroundColor) else Modifier),
            contentAlignment = Alignment.TopCenter,
        ) {
            content(style.toComposeTextStyle(), Modifier.fillMaxWidth())
        }
    }
}

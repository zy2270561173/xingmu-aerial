package app.xingmu.remote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Aerial (tvOS) look for the phone app: black canvas, near-white type, glass plates separated by
 * hairlines, and a white "focus plate" as the one accent. Mirrors the launcher's Palette/Type/Shape
 * so the phone and the TV speak the same visual language.
 */
object AerialPalette {
    val Background = Color(0xFF000000)
    val Label = Color(0xFFFFFFFF)
    val SecondaryLabel = Color(0x99EBEBF5)
    val TertiaryLabel = Color(0x4DEBEBF5)
    val Glass = Color(0xFF1C1C1E)
    val GlassSoft = Color(0xFF161618)
    val Fill = Color(0x2E787880)
    val FillStrong = Color(0x52787880)
    val Hairline = Color(0x26FFFFFF)
    val Focus = Color(0xFFF5F5F7)
    val OnFocus = Color(0xFF1D1D1F)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)
}

/** Dark scheme only: Aerial has no light appearance. */
private val AerialColors = darkColorScheme(
    primary = AerialPalette.Focus,
    onPrimary = AerialPalette.OnFocus,
    primaryContainer = AerialPalette.FillStrong,
    onPrimaryContainer = AerialPalette.Label,
    // Aerial has one accent: the near-white plate. Green is reserved for "online" state, so it is
    // not the secondary colour any more (it used to tint every button green).
    secondary = AerialPalette.Focus,
    onSecondary = AerialPalette.OnFocus,
    // Selected states (bottom nav indicator, filled buttons) are the tvOS focus plate: white with
    // dark content. Secondary fills would be a grey slab here.
    secondaryContainer = AerialPalette.Focus,
    onSecondaryContainer = AerialPalette.OnFocus,
    tertiary = AerialPalette.Orange,
    onTertiary = Color(0xFF241500),
    tertiaryContainer = Color(0x33FF9F0A),
    onTertiaryContainer = AerialPalette.Label,
    background = AerialPalette.Background,
    onBackground = AerialPalette.Label,
    surface = AerialPalette.Glass,
    onSurface = AerialPalette.Label,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = AerialPalette.SecondaryLabel,
    surfaceContainer = AerialPalette.GlassSoft,
    surfaceContainerHigh = Color(0xFF232325),
    outline = AerialPalette.Hairline,
    outlineVariant = Color(0x14FFFFFF),
    error = AerialPalette.Red,
    onError = Color(0xFF2A0503),
    errorContainer = Color(0x33FF453A),
    onErrorContainer = AerialPalette.Label,
    scrim = Color(0xB3000000),
)

/** Aerial's ramp: system sans, tight display tracking, secondary colour on supporting lines. */
private val AerialTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W600,
        fontSize = 34.sp, letterSpacing = (-0.02).em,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W600,
        fontSize = 26.sp, letterSpacing = (-0.015).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W600,
        fontSize = 21.sp, letterSpacing = (-0.01).em,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W600,
        fontSize = 19.sp, letterSpacing = (-0.01).em,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W600,
        fontSize = 15.sp, letterSpacing = (-0.005).em,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W500,
        fontSize = 13.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W400,
        fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W400,
        fontSize = 13.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W400,
        fontSize = 11.sp, letterSpacing = 0.01.em,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W600,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W500,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W500,
        fontSize = 11.sp,
    ),
)

private val AerialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AerialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AerialColors,
        typography = AerialTypography,
        shapes = AerialShapes,
    ) {
        // Sets LocalContentColor for the whole app: without it every Text that relies on the
        // default colour paints black on black (the login title disappeared that way).
        androidx.compose.material3.Surface(
            color = AerialColors.background,
            contentColor = AerialColors.onBackground,
            content = content,
        )
    }
}

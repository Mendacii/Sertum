package com.sertum.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SertumShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

private val DarkScheme = darkColorScheme(
    primary = WarmGold,
    onPrimary = Color.Black,
    background = PureBlack,
    onBackground = TextPrimary,
    surface = PureBlack,
    onSurface = TextPrimary,
    // Every structural slot goes black (user feedback 2026-10-02). The
    // `surfaceContainer*` roles are listed explicitly because
    // `darkColorScheme()` does not populate them, and any role left unset
    // falls back to the baseline purple-grey palette - which is what made
    // sheets and bars read as "grey" against a near-black page.
    surfaceVariant = PureBlack,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = PureBlack,
    surfaceContainerLow = PureBlack,
    surfaceContainer = PureBlack,
    surfaceContainerHigh = PureBlack,
    surfaceContainerHighest = PureBlack,
    surfaceDim = PureBlack,
    surfaceBright = PureBlack,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = Color(0xFFCF6679),
)

private val LightScheme = lightColorScheme(
    primary = WarmGold,
    onPrimary = Color.White,
    background = PaperWhite,
    onBackground = InkPrimary,
    surface = PaperSurface,
    onSurface = InkPrimary,
    surfaceVariant = Color(0xFFECE5D8),
    onSurfaceVariant = InkSecondary,
    outline = Color(0xFFD8D0C2),
)

/**
 * Tokens that follow the active theme, for call sites that need a specific role the
 * colour scheme does not name directly.
 *
 * These exist because component code was importing the raw colour constants
 * (`TextPrimary`, `TextSecondary`) instead of reading the scheme. Those constants are
 * light-on-dark values only, so the settings page kept near-white text on the light
 * theme's paper background and became unreadable. The constants are names, not roles:
 * `TextPrimary` does not mean "the main text colour", it means "#F2F2F2", which is only
 * correct in one theme.
 *
 * Gold is deliberately absent. It is an accent rather than a surface-dependent colour,
 * it reads correctly on both backgrounds, and every existing call site already reaches
 * for it directly.
 */
val sertumBodyText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

val sertumMutedText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun SertumTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = SertumTypography,
        shapes = SertumShapes,
        content = content,
    )
}

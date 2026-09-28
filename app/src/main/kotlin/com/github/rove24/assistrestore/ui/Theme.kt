package com.github.rove24.assistrestore.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The same Material 3 baseline palette the sibling modules of this family ship with: a fixed
 * Google-blue scheme with a hand-picked night variant, so the settings screens look identical to
 * "增强 Chrome" and "XposedSmsCode" instead of following the device wallpaper.
 *
 * <p>Dynamic colour (Monet) is deliberately off: these modules are meant to look the same on every
 * device, and the card surfaces below are tuned against this exact palette.</p>
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    inversePrimary = Color(0xFFA8C7FA),

    secondary = Color(0xFF00639B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE5FF),
    onSecondaryContainer = Color(0xFF001D33),

    tertiary = Color(0xFF5B5891),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE1DFFF),
    onTertiaryContainer = Color(0xFF18124B),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFF0F4F9),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFF0F4F9),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474E),

    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F4F9),
    surfaceContainerHighest = Color(0xFFE1E2EC),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF98CBFF),
    onPrimary = Color(0xFF013354),
    primaryContainer = Color(0xFF1B3B60),
    onPrimaryContainer = Color(0xFFD3E3FD),
    inversePrimary = Color(0xFF0B57D0),

    secondary = Color(0xFF95CCFF),
    onSecondary = Color(0xFF003354),
    secondaryContainer = Color(0xFF004A77),
    onSecondaryContainer = Color(0xFFCDE5FF),

    tertiary = Color(0xFFC5C2F5),
    onTertiary = Color(0xFF2D2A5E),
    tertiaryContainer = Color(0xFF434076),
    onTertiaryContainer = Color(0xFFE1DFFF),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF192028),
    onBackground = Color(0xFFE1E3E8),
    surface = Color(0xFF192028),
    onSurface = Color(0xFFE1E3E8),
    surfaceVariant = Color(0xFF242D38),
    onSurfaceVariant = Color(0xFF9CA2AE),

    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),

    surfaceContainerLowest = Color(0xFF0C131B),
    surfaceContainerLow = Color(0xFF141C24),
    surfaceContainer = Color(0xFF0C131B),
    surfaceContainerHigh = Color(0xFF242D38),
    surfaceContainerHighest = Color(0xFF2F3742),
)

/**
 * The card kit the sibling modules share, taken verbatim from their resources so the settings
 * screens are pixel-identical to "增强 Chrome" and "XposedSmsCode".
 */
object Cards {
    /** `dimen/m3_card_corner_radius` */
    val Corner = 16.dp

    /** `dimen/m3_card_margin_horizontal` / `dimen/m3_card_margin_bottom` */
    val MarginHorizontal = 16.dp
    val MarginBottom = 4.dp

    /** `color/m3_divider` — the 1dp line each row draws along its own bottom edge. */
    val DividerLight = Color(0xFFE1E2EC)
    val DividerDark = Color(0xFF192028)
}

private val LocalCardDivider = staticCompositionLocalOf { Cards.DividerLight }

/**
 * `m3_divider`. Material 3 has no divider slot in its colour scheme, and the reference modules use a
 * fixed pair rather than a semantic role, so it travels through a composition local.
 */
val cardDivider: Color
    @Composable get() = LocalCardDivider.current

@Composable
fun AssistRestoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCardDivider provides if (darkTheme) Cards.DividerDark else Cards.DividerLight,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}

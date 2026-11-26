package no.vardir.varde

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors Material does not name: build/review status, diffs and code. Always read through [VardeTheme.colors]. */
@Immutable
internal data class VardeColors(
    val success: Color, val successContainer: Color,
    val danger: Color, val dangerContainer: Color,
    val pending: Color, val pendingContainer: Color,
    val neutral: Color, val neutralContainer: Color,
    val diffAdded: Color, val diffRemoved: Color, val diffHunk: Color, val diffContext: Color,
    val codeKeyword: Color, val codeString: Color, val codeComment: Color, val codeNumber: Color,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF2A43B), onPrimary = Color(0xFF241707),
    primaryContainer = Color(0xFF3B3021), onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFC9C2B8), onSecondary = Color(0xFF2B2722),
    background = Color(0xFF101720), onBackground = Color(0xFFF5EEE8),
    surface = Color(0xFF101720), onSurface = Color(0xFFF5EEE8),
    surfaceVariant = Color(0xFF1D252F), onSurfaceVariant = Color(0xFFB8B6B5),
    surfaceContainerLowest = Color(0xFF0B1118), surfaceContainerLow = Color(0xFF151D26),
    surfaceContainer = Color(0xFF1A222C), surfaceContainerHigh = Color(0xFF212A35),
    surfaceContainerHighest = Color(0xFF29323E),
    outline = Color(0xFF5A636E), outlineVariant = Color(0xFF2E3742),
    error = Color(0xFFFF807E), onError = Color(0xFF3A0B0B),
    errorContainer = Color(0xFF4A1B1D), onErrorContainer = Color(0xFFFFDAD7),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF8F5200), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB3), onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF5E584F), onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBF8F4), onBackground = Color(0xFF1B2128),
    surface = Color(0xFFFBF8F4), onSurface = Color(0xFF1B2128),
    surfaceVariant = Color(0xFFEFE9E2), onSurfaceVariant = Color(0xFF55524D),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F1EB),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEFE9E2),
    surfaceContainerHighest = Color(0xFFE8E1D9),
    outline = Color(0xFF857F77), outlineVariant = Color(0xFFDDD5CB),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val DarkExtras = VardeColors(
    success = Color(0xFF7FD69A), successContainer = Color(0xFF173325),
    danger = Color(0xFFFF8A85), dangerContainer = Color(0xFF3F1C1E),
    pending = Color(0xFFF2C46B), pendingContainer = Color(0xFF3A2F17),
    neutral = Color(0xFFB8B6B5), neutralContainer = Color(0xFF262E38),
    diffAdded = Color(0xFF9DDBAE), diffRemoved = Color(0xFFFFA9A4), diffHunk = Color(0xFFADC8FF), diffContext = Color(0xFFE6E1DC),
    codeKeyword = Color(0xFFFFBF69), codeString = Color(0xFFA5D6A7), codeComment = Color(0xFFABB8C3), codeNumber = Color(0xFFB8C9FF),
)

private val LightExtras = VardeColors(
    success = Color(0xFF1E6B3A), successContainer = Color(0xFFD7F0DE),
    danger = Color(0xFFB3261E), dangerContainer = Color(0xFFF9DEDC),
    pending = Color(0xFF7A5300), pendingContainer = Color(0xFFFFE8B8),
    neutral = Color(0xFF55524D), neutralContainer = Color(0xFFEAE4DC),
    diffAdded = Color(0xFF1E6B3A), diffRemoved = Color(0xFFA3261E), diffHunk = Color(0xFF2F4F9A), diffContext = Color(0xFF2B2F35),
    codeKeyword = Color(0xFF8F4A00), codeString = Color(0xFF2E6B30), codeComment = Color(0xFF6A737D), codeNumber = Color(0xFF3949AB),
)

private val LocalVardeColors = staticCompositionLocalOf { DarkExtras }

internal object VardeTheme {
    val colors: VardeColors
        @Composable @ReadOnlyComposable get() = LocalVardeColors.current
}

internal fun vardeColorScheme(dark: Boolean): ColorScheme = if (dark) DarkScheme else LightScheme

@Composable
fun VardeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalVardeColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = vardeColorScheme(dark), content = content)
    }
}

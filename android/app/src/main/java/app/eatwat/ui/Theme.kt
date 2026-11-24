package app.eatwat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Chart palette: validated categorical order (light / dark steps) plus surfaces. */
@Immutable
data class VizColors(
    val series: List<Color>,
    val band: Color,
    val bandEdge: Color,
    val grid: Color,
    val axisText: Color,
    val ink: Color,
    val meal: Color,
    val surface: Color,
    val warn: Color,
)

private val LightViz = VizColors(
    series = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948).map { Color(it) },
    band = Color(0x140CA30C), bandEdge = Color(0x590CA30C), grid = Color(0xFFE9E8E4), axisText = Color(0xFF76756F),
    ink = Color(0xFF52514E), meal = Color(0xFF52514E), surface = Color(0xFFFCFCFB), warn = Color(0xFF8A5A00),
)

private val DarkViz = VizColors(
    series = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767).map { Color(it) },
    band = Color(0x160CA30C), bandEdge = Color(0x660CA30C), grid = Color(0xFF2A2A27), axisText = Color(0xFF9A998F),
    ink = Color(0xFFC3C2B7), meal = Color(0xFFC3C2B7), surface = Color(0xFF1A1A19), warn = Color(0xFFFAB219),
)

val LocalViz = staticCompositionLocalOf { LightViz }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2A78D6), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE2FB), onPrimaryContainer = Color(0xFF0D366B),
    secondary = Color(0xFF52514E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE9F8), onSecondaryContainer = Color(0xFF104281),
    tertiary = Color(0xFF1BAF7A),
    background = Color(0xFFF4F4F1), surface = Color(0xFFFCFCFB), surfaceVariant = Color(0xFFF0EFEC),
    surfaceContainer = Color(0xFFF7F7F4), surfaceContainerHigh = Color(0xFFF0EFEC), surfaceContainerLow = Color(0xFFFCFCFB),
    onSurface = Color(0xFF0B0B0B), onSurfaceVariant = Color(0xFF52514E), outline = Color(0xFFCFCEC8), outlineVariant = Color(0xFFE2E1DC),
    error = Color(0xFFB42323),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3987E5), onPrimary = Color.White,
    primaryContainer = Color(0xFF184F95), onPrimaryContainer = Color(0xFFCDE2FB),
    secondary = Color(0xFFC3C2B7), onSecondary = Color(0xFF111110),
    secondaryContainer = Color(0xFF203750), onSecondaryContainer = Color(0xFFCDE2FB),
    tertiary = Color(0xFF199E70),
    background = Color(0xFF111110), surface = Color(0xFF1A1A19), surfaceVariant = Color(0xFF242422),
    surfaceContainer = Color(0xFF1E1E1C), surfaceContainerHigh = Color(0xFF242422), surfaceContainerLow = Color(0xFF1A1A19),
    onSurface = Color(0xFFF0EFEC), onSurfaceVariant = Color(0xFFC3C2B7), outline = Color(0xFF4A4A45), outlineVariant = Color(0xFF33332F),
    error = Color(0xFFFF8A8A),
)

@Composable
fun EatWatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalViz provides if (dark) DarkViz else LightViz) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

package es.altamirano.gps.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import es.altamirano.common.ui.AppTheme
import es.altamirano.common.ui.withBrandSelection

// Verde de monte y azul de agua, fijos. Con los colores del fondo de pantalla (Material You) el
// móvil de Bruno daba una paleta gris y lo seleccionado no se distinguía: quiere verde clarito.
private val Brand = Color(0xFF1A7F60)
private val BrandDark = Color(0xFF4FD1A1)

private val Light = lightColorScheme(
    primary = Brand, onPrimary = Color.White, primaryContainer = Color(0xFFC8EEDC), onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF3F6656), onSecondary = Color.White,
    tertiary = Color(0xFF2B6A8C), onTertiary = Color.White, tertiaryContainer = Color(0xFFC7E7FF), onTertiaryContainer = Color(0xFF00344B),
    background = Color(0xFFF5FBF7), onBackground = Color(0xFF171D1A), surface = Color(0xFFF5FBF7), onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DE), onSurfaceVariant = Color(0xFF404943),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5F1), surfaceContainer = Color(0xFFE9F0EB),
    surfaceContainerHigh = Color(0xFFE3EAE5), surfaceContainerHighest = Color(0xFFDEE4E0),
    outline = Color(0xFF707973), outlineVariant = Color(0xFFBFC9C2),
).withBrandSelection(Brand, dark = false)

private val Dark = darkColorScheme(
    primary = BrandDark, onPrimary = Color(0xFF00382A), primaryContainer = Color(0xFF005140), onPrimaryContainer = Color(0xFFA6F2D0),
    secondary = Color(0xFFA5CFBC), onSecondary = Color(0xFF0D372A),
    tertiary = Color(0xFF94CDF5), onTertiary = Color(0xFF00344B), tertiaryContainer = Color(0xFF0B4C6B), onTertiaryContainer = Color(0xFFC7E7FF),
    background = Color(0xFF0F1512), onBackground = Color(0xFFDEE4E0), surface = Color(0xFF0F1512), onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFBFC9C2),
    surfaceContainerLowest = Color(0xFF0A0F0D), surfaceContainerLow = Color(0xFF171D1A), surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28), surfaceContainerHighest = Color(0xFF303633),
    outline = Color(0xFF89938C), outlineVariant = Color(0xFF404943),
).withBrandSelection(BrandDark, dark = true)

@Composable
fun GpsTheme(content: @Composable () -> Unit) = AppTheme(if (isSystemInDarkTheme()) Dark else Light, content = content)

/** Colores fijos del mapa: tienen que verse igual sobre el topográfico, la ortofoto y el Base. */
object MapColors {
    val recording = Color(0xFFE53935)
    val opened = Color(0xFF8E24AA)
    val position = Color(0xFF1E88E5)
    val stale = Color(0xFF9E9E9E)
}

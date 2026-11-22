package es.altamirano.gps.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Colores del sistema (Material You); los del mapa van fijos para que se vean sobre cualquier capa. */
@Composable
fun GpsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

object MapColors {
    val recording = Color(0xFFE53935)
    val opened = Color(0xFF7B1FA2)
    val position = Color(0xFF1E88E5)
    val stale = Color(0xFF9E9E9E)
}

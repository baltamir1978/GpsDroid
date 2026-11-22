package es.altamirano.gps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3 Expressive (la guía de diseño de Android 16 y 17) con los colores del fondo de pantalla
 * (Material You) y su movimiento con rebote. Lo pidió Bruno: «algo más colorido y translúcido».
 */
@Composable
fun GpsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/** Colores fijos del mapa: tienen que verse igual sobre el topográfico, la ortofoto y el Base. */
object MapColors {
    val recording = Color(0xFFE53935)
    val opened = Color(0xFF8E24AA)
    val position = Color(0xFF1E88E5)
    val stale = Color(0xFF9E9E9E)
}

/** Forma grande de las tarjetas de Expressive. */
val CardShape = RoundedCornerShape(28.dp)

/**
 * «Cristal»: una superficie translúcida tintada con el color del sistema, con un filo claro y
 * sombra suave, para lo que flota sobre el mapa (Compose no difumina lo de debajo sin pintarlo
 * dos veces; con el tinte y el filo basta para que se lea encima de cualquier capa).
 */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp), tint: Color = MaterialTheme.colorScheme.surfaceContainer, alpha: Float = 0.78f): Modifier =
    this.shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
        .clip(shape)
        .background(tint.copy(alpha = alpha))
        .border(1.dp, Color.White.copy(alpha = if (isSystemInDarkTheme()) 0.12f else 0.55f), shape)

/** El degradado de color de las cabeceras: primario → terciario del tema, muy suave. */
@Composable
fun headerBrush(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(c.primaryContainer, c.tertiaryContainer.copy(alpha = 0.85f), c.surface))
}

/**
 * Cabecera de las pestañas: título grande sobre un degradado de color que se funde con el fondo,
 * con un icono en un círculo de color (como las apps de Google con Expressive).
 */
@Composable
fun Header(title: String, subtitle: String?, icon: ImageVector, side: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().background(headerBrush()).statusBarsPadding().padding(start = side, end = side, top = 18.dp, bottom = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Column {
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                subtitle?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)) }
            }
        }
    }
}

/** Tarjeta tonal de Expressive: esquinas grandes y un color del tema de fondo. */
@Composable
fun TonalCard(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(CardShape).background(container)) { content() }
}

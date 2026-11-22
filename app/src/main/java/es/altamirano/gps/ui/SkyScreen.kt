package es.altamirano.gps.ui

import android.location.GnssStatus
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import es.altamirano.gps.core.Geo
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Satellite
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

private data class Constellation(val name: String, val color: Color)

private fun constellation(type: Int) = when (type) {
    GnssStatus.CONSTELLATION_GPS -> Constellation("GPS", Color(0xFF1E88E5))
    GnssStatus.CONSTELLATION_GALILEO -> Constellation("Galileo", Color(0xFF43A047))
    GnssStatus.CONSTELLATION_GLONASS -> Constellation("GLONASS", Color(0xFFE53935))
    GnssStatus.CONSTELLATION_BEIDOU -> Constellation("BeiDou", Color(0xFFFB8C00))
    GnssStatus.CONSTELLATION_QZSS -> Constellation("QZSS", Color(0xFF8E24AA))
    GnssStatus.CONSTELLATION_SBAS -> Constellation("SBAS", Color(0xFF6D4C41))
    GnssStatus.CONSTELLATION_IRNSS -> Constellation("NavIC", Color(0xFF00897B))
    else -> Constellation("Otro", Color(0xFF757575))
}

/**
 * El cielo (como la pantalla «Satélites» de ZeroGPS): norte arriba, círculos de elevación a 0°, 30°
 * y 60°, un punto por satélite del color de su constelación (relleno si se usa en el fix, más grande
 * cuanto más señal) y al lado el estado del fix. El móvil da la precisión en metros de verdad, no la
 * estimación por HDOP del Cardputer.
 */
@Composable
fun SkyScreen() {
    LifecycleStartEffect(Unit) {
        Gps.acquire("sky")
        onStopOrDispose { Gps.release("sky") }
    }
    val sky by Gps.sky.collectAsState()
    val loc by Gps.location.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    val placed = sky.filter { it.hasPosition }
    val unplaced = sky.size - placed.size
    val measurer = rememberTextMeasurer()
    val lineColor = Color.White.copy(alpha = 0.22f)
    val labelColor = Color.White.copy(alpha = 0.85f)

    val used = sky.count { it.usedInFix }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomBar.current + 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Satélites", if (sky.isEmpty()) "Esperando al GPS…" else "$used usados de ${sky.size} a la vista", Icons.Filled.SatelliteAlt)
        // El cielo de noche: un degradado azul oscuro en el que los colores de las constelaciones resaltan.
        Box(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF243B6B), Color(0xFF0E1630))))) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 * 0.84f
                val c = Offset(size.width / 2, size.height / 2)
                for (k in listOf(1f, 2f / 3, 1f / 3)) drawCircle(lineColor, r * k, c, style = Stroke(1.dp.toPx()))
                drawLine(lineColor, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
                drawLine(lineColor, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
                for ((txt, ang) in listOf("N" to 0f, "E" to 90f, "S" to 180f, "O" to 270f)) {
                    val m = measurer.measure(txt, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = labelColor))
                    val a = Math.toRadians(ang.toDouble())
                    val p = Offset(c.x + (r + 11.dp.toPx()) * sin(a).toFloat(), c.y - (r + 11.dp.toPx()) * cos(a).toFloat())
                    drawText(m, topLeft = Offset(p.x - m.size.width / 2, p.y - m.size.height / 2))
                }
                for (s in placed.sortedBy { it.usedInFix }) {
                    val d = r * (90f - s.elevation.coerceIn(0f, 90f)) / 90f
                    val a = Math.toRadians(s.azimuth.toDouble())
                    val p = Offset(c.x + d * sin(a).toFloat(), c.y - d * cos(a).toFloat())
                    val col = constellation(s.constellation).color
                    val rad = (4f + (s.cn0.coerceIn(0f, 50f) / 50f) * 6f).dp.toPx()
                    if (s.usedInFix) {
                        drawCircle(col.copy(alpha = 0.3f), rad * 1.9f, p)   // halo
                        drawCircle(col, rad, p)
                    } else drawCircle(col.copy(alpha = if (s.cn0 > 0) 0.9f else 0.4f), rad, p, style = Stroke(2.dp.toPx()))
                }
            }
        }
        if (unplaced > 0) Text("+$unplaced sin situar todavía", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Legend(sky)
        FixCard(sky, loc, tick)
    }
}

@Composable
private fun Legend(sky: List<Satellite>) {
    val groups = sky.groupBy { constellation(it.constellation) }.toList().sortedByDescending { it.second.size }
    if (groups.isEmpty()) return
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (c, list) ->
            Row(Modifier.clip(RoundedCornerShape(50)).background(c.color.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c.color))
                Text("  ${c.name}  ${list.count { it.usedInFix }}/${list.size}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FixCard(sky: List<Satellite>, loc: android.location.Location?, @Suppress("UNUSED_PARAMETER") tick: Long) {
    val fresh = Gps.isFresh(loc)
    val used = sky.count { it.usedInFix }
    val rows = buildList {
        add("Estado" to when {
            !Gps.hasPermission() -> "sin permiso de ubicación"
            !Gps.isProviderEnabled() -> "ubicación desactivada"
            fresh && loc != null && loc.hasAltitude() -> "fix 3D"
            fresh -> "fix 2D"
            else -> "buscando…"
        })
        add("Satélites" to "$used usados de ${sky.size} a la vista")
        if (loc != null) {
            add("Posición" to Geo.formatLatLon(loc.latitude, loc.longitude) + if (fresh) "" else " (última)")
            if (loc.hasAltitude()) add("Altitud" to "%.0f m".format(loc.altitude) +
                if (loc.hasVerticalAccuracy()) "  ±%.0f m".format(loc.verticalAccuracyMeters) else "")
            if (loc.hasAccuracy()) add("Precisión" to "±%.0f m".format(loc.accuracy))
            if (loc.hasSpeed()) add("Velocidad" to "%.1f km/h".format(loc.speed * 3.6f))
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.7f) add("Rumbo" to "%.0f°".format(loc.bearing))
            add("Hora del fix" to DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(loc.time).atZone(ZoneId.systemDefault())))
        }
        val best = sky.map { it.cn0 }.sorted().takeLast(4)
        if (best.isNotEmpty()) add("Señal (4 mejores)" to "%.0f dB-Hz".format(best.average()))
    }
    TonalCard(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp), MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (k, v) ->
                Row { Text(k, Modifier.weight(0.42f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)); Text(v, Modifier.weight(0.58f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
        }
    }
}

package es.altamirano.gps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.Gpx
import es.altamirano.gps.core.OfflineZone
import es.altamirano.gps.core.ProfilePoint
import es.altamirano.gps.core.TrackStats
import es.altamirano.gps.data.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * La ficha de un track: lo que se anduvo, cuánto se tardó (en total y en movimiento), el desnivel y
 * el perfil de altitud. Se lee el GPX al abrirla (puede ser largo: en segundo plano).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackDetailSheet(info: TrackInfo, onDismiss: () -> Unit, onShowOnMap: () -> Unit, onShare: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stats by produceState<TrackStats?>(null, info.file, info.modified) {
        value = withContext(Dispatchers.IO) {
            runCatching { Gpx.parse(info.file.readText(), info.name)?.let { TrackStats.of(it.points) } }.getOrNull()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(info.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val s = stats
            if (s == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                    val cells = buildList {
                        add("Distancia" to Geo.formatDistance(s.distanceM))
                        s.durationS?.let { add("Tiempo total" to Geo.formatDuration(it)) }
                        s.movingS?.let { add("En movimiento" to Geo.formatDuration(it)) }
                        s.movingSpeedKmh?.let { add("Media" to TrackStats.formatSpeed(it)) }
                        s.paceSPerKm?.let { add("Ritmo" to TrackStats.formatPace(it)) }
                        if (s.maxEleM != null) {
                            add("Subida" to "↑ " + meters(s.ascentM))
                            add("Bajada" to "↓ " + meters(s.descentM))
                            add("Altitud máx." to meters(s.maxEleM))
                            add("Altitud mín." to meters(s.minEleM ?: s.maxEleM))
                        }
                    }
                    cells.forEach { (label, value) -> StatCell(label, value, Modifier.weight(1f)) }
                    // Que la última fila no estire sus celdas hasta ocupar todo el ancho.
                    repeat((3 - cells.size % 3) % 3) { Box(Modifier.weight(1f)) }
                }
                if (s.profile.size >= 2) {
                    Text("Perfil de altitud", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    ElevationProfile(s.profile, Modifier.fillMaxWidth().height(190.dp))
                    Text("Desliza el dedo por el perfil para ver la altitud en cada punto.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (s.maxEleM == null) {
                    Text("Este track no tiene altitudes: no hay perfil.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.durationS == null) Text("Sin horas en el GPX (una ruta planificada, quizá): no hay tiempos.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onShowOnMap, enabled = info.points > 0) { Icon(Icons.Filled.Map, null); Text("  Ver en el mapa") }
                OutlinedButton(onClick = onShare) { Icon(Icons.Filled.Share, null); Text("  Compartir") }
            }
        }
    }
}

/** "1.540 m" (punto de millares, como en el resto de la app). */
private fun meters(m: Double) = OfflineZone.formatCount(m.toLong()) + " m"

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Altitud frente a distancia, con el área rellena en degradado. Tocar o arrastrar marca un punto y
 * enseña su distancia y altitud. El eje de altitud deja algo de margen arriba y abajo y nunca menos
 * de 50 m de rango (un paseo llano no debe parecer una montaña).
 */
@Composable
fun ElevationProfile(points: List<ProfilePoint>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val markStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onPrimary)
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    val minE = points.minOf { it.eleM }
    val maxE = points.maxOf { it.eleM }
    val span = max(50.0, maxE - minE)
    val lo = (minE + maxE) / 2 - span * 0.6
    val hi = (minE + maxE) / 2 + span * 0.6
    val d0 = points.first().distanceM
    val total = max(1.0, points.last().distanceM - d0)

    fun nearest(x: Float, left: Float, width: Float): Int {
        val d = d0 + ((x - left) / width).coerceIn(0f, 1f) * total
        var best = 0
        for (i in points.indices) if (kotlin.math.abs(points[i].distanceM - d) < kotlin.math.abs(points[best].distanceM - d)) best = i
        return best
    }

    Box(modifier.clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainer)) {
        Canvas(Modifier.matchParentSize().padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
            .pointerInput(points) {
                val left = 44.dp.toPx()
                detectTapGestures { selected = nearest(it.x, left, size.width - left) }
            }
            .pointerInput(points) {
                val left = 44.dp.toPx()
                detectHorizontalDragGestures(onDragEnd = {}) { change, _ -> selected = nearest(change.position.x, left, size.width - left) }
            }) {
            val left = 44.dp.toPx()
            val bottomAxis = 18.dp.toPx()
            val w = size.width - left
            val h = size.height - bottomAxis
            fun px(p: ProfilePoint) = Offset(left + ((p.distanceM - d0) / total * w).toFloat(), ((hi - p.eleM) / (hi - lo) * h).toFloat())

            // Rejilla: tres alturas de referencia redondeadas, con su etiqueta a la izquierda.
            val step = niceStep((hi - lo) / 3)
            var g = kotlin.math.ceil(lo / step) * step
            while (g <= hi) {
                val y = ((hi - g) / (hi - lo) * h).toFloat()
                drawLine(colors.outlineVariant, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                val tl = measurer.measure(OfflineZone.formatCount(g.toLong()) + " m", labelStyle)
                drawText(tl, topLeft = Offset(0f, y - tl.size.height / 2f))
                g += step
            }

            val line = Path()
            points.forEachIndexed { i, p -> val o = px(p); if (i == 0) line.moveTo(o.x, o.y) else line.lineTo(o.x, o.y) }
            val area = Path().apply {
                addPath(line)
                lineTo(px(points.last()).x, h); lineTo(px(points.first()).x, h); close()
            }
            drawPath(area, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.45f), colors.primary.copy(alpha = 0.05f)), startY = 0f, endY = h))
            drawPath(line, colors.primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Eje de distancia: el inicio y el total.
            val start = measurer.measure("0", labelStyle)
            drawText(start, topLeft = Offset(left, h + 3.dp.toPx()))
            val end = measurer.measure(Geo.formatDistance(total), labelStyle)
            drawText(end, topLeft = Offset(size.width - end.size.width, h + 3.dp.toPx()))

            selected?.let { i ->
                val p = points[i]
                val o = px(p)
                drawLine(colors.tertiary, Offset(o.x, 0f), Offset(o.x, h), strokeWidth = 1.5.dp.toPx())
                drawCircle(colors.surface, radius = 7.dp.toPx(), center = o)
                drawCircle(colors.tertiary, radius = 5.dp.toPx(), center = o)
                val label = measurer.measure("${Geo.formatDistance(p.distanceM - d0)} · ${OfflineZone.formatCount(p.eleM.toLong())} m", markStyle)
                val pad = 6.dp.toPx()
                val bw = label.size.width + pad * 2
                val bx = (o.x - bw / 2).coerceIn(left, size.width - bw)
                drawRoundRect(colors.tertiary, topLeft = Offset(bx, 0f), size = androidx.compose.ui.geometry.Size(bw, label.size.height + pad),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                drawText(label, topLeft = Offset(bx + pad, pad / 2))
            }
        }
    }
}

/** Paso «redondo» (1, 2, 5 × 10ⁿ) para las líneas de la rejilla. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 10.0
    val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    val f = raw / mag
    return mag * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
}

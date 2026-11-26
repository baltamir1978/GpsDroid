package es.altamirano.gps.ui

import android.content.Intent
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import es.altamirano.common.ui.glass
import es.altamirano.gps.core.Direction
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.altitudeMsl
import es.altamirano.gps.core.Altitude
import es.altamirano.gps.data.Waypoints
import java.util.Locale
import kotlin.math.roundToInt

/** Distancia y rumbo desde donde estás, o null sin posición de ahora. */
data class Away(val distanceM: Double, val bearing: Double)

fun away(loc: Location?, w: Waypoint): Away? {
    if (loc == null || !Gps.isFresh(loc)) return null
    return Away(Geo.distanceM(loc.latitude, loc.longitude, w.lat, w.lon), Direction.bearingDeg(loc.latitude, loc.longitude, w.lat, w.lon))
}

/**
 * "1.245 m · 230 m más arriba": la altitud del punto y, con posición de ahora, cuánto hay que subir o
 * bajar. null si el punto no tiene altitud (todavía: se pide a la red al guardarlo).
 */
fun altitudeLine(w: Waypoint, loc: Location?): String? {
    val e = w.ele ?: return null
    val mine = loc?.takeIf { Gps.isFresh(it) }?.altitudeMsl()
    return listOfNotNull(Altitude.format(e), Altitude.relative(e, mine)).joinToString(" · ")
}

/**
 * Arriba en el mapa, bajo la posición: hacia dónde queda el destino. La flecha gira con la brújula
 * (apunta de verdad hacia el punto con el móvil en la mano) y, si vas en marcha, con tu rumbo.
 */
@Composable
fun TargetPill(t: Waypoint, loc: Location?, onShow: () -> Unit, onClose: () -> Unit) {
    val a = away(loc, t)
    val compass by rememberCompass(a != null)
    val heading = loc?.travelHeading() ?: compass
    Row(Modifier.glass(RoundedCornerShape(24.dp), tint = MapColors.target, alpha = 0.9f).clickable(onClick = onShow)
        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            if (a != null) Icon(Icons.Filled.Navigation, null, Modifier.size(24.dp).rotate((a.bearing - (heading ?: 0f)).toFloat()), tint = MapColors.target)
            else Icon(Icons.Filled.Place, null, Modifier.size(22.dp), tint = MapColors.target)
        }
        Column(Modifier.weight(1f, fill = false).padding(start = 10.dp)) {
            Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when {
                a == null -> "Sin posición: no sé hacia dónde queda"
                a.distanceM < 15 -> "Has llegado"
                heading != null -> "${Geo.formatDistance(a.distanceM)} al ${Direction.cardinal(a.bearing)} · ${Direction.relative(a.bearing, heading.toDouble())}"
                else -> Direction.describe(a.distanceM, a.bearing)
            }, fontSize = 12.sp, color = Color.White.copy(alpha = 0.92f))
        }
        IconButton(onClick = onClose, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Dejar de ir", Modifier.size(18.dp), tint = Color.White) }
    }
}

/** Pedir un nombre (guardar o renombrar un punto), con el texto ya seleccionado para escribir encima. */
@Composable
fun NameDialog(title: String, initial: String, subtitle: String?, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, label = { Text("Nombre") })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

/** La ficha de un punto: dónde está, a qué distancia y hacia dónde, y lo que se puede hacer con él. */
@Composable
fun WaypointSheet(w: Waypoint, loc: Location?, isTarget: Boolean, onDismiss: () -> Unit, onShowOnMap: (() -> Unit)? = null) {
    val context = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val a = away(loc, w)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (isTarget) MapColors.target else MapColors.waypoint),
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.Place, null, tint = Color.White) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(w.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(Geo.formatLatLon(w.lat, w.lon) + (w.ele?.let { "  ·  " + Altitude.format(it) } ?: ""),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (a != null) Row(verticalAlignment = Alignment.Bottom) {
                Text(Geo.formatDistance(a.distanceM), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("  al ${Direction.cardinalName(a.bearing)} · ${a.bearing.roundToInt() % 360}°", fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 6.dp))
            } else Text("Sin posición del GPS: no sé a qué distancia queda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            w.ele?.let { e ->
                val rel = Altitude.relative(e, loc?.takeIf { Gps.isFresh(it) }?.altitudeMsl())
                Text("Altitud ${Altitude.format(e)}" + (rel?.let { " · $it" } ?: ""), fontSize = 16.sp)
            }

            Button(onClick = { Waypoints.setTarget(if (isTarget) null else w); onDismiss() }, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MapColors.target, contentColor = Color.White)) {
                Icon(if (isTarget) Icons.Filled.Close else Icons.Filled.Navigation, null)
                Text(if (isTarget) "  Dejar de ir" else "  Ir hacia aquí", fontWeight = FontWeight.SemiBold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onShowOnMap?.let { OutlinedButton(onClick = it) { Icon(Icons.Filled.Map, null); Text("  Ver en el mapa") } }
                OutlinedButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, null); Text("  Renombrar") }
                OutlinedButton(onClick = {
                    // Otra app de mapas (Organic Maps, OsmAnd…) para llegar en coche por carretera.
                    val uri = "geo:%.7f,%.7f?q=%.7f,%.7f(%s)".format(Locale.ROOT, w.lat, w.lon, w.lat, w.lon, android.net.Uri.encode(w.name)).toUri()
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), w.name)) }
                }) { Icon(Icons.Filled.OpenInNew, null); Text("  Abrir en otra app") }
                OutlinedButton(onClick = { delete = true }) { Icon(Icons.Filled.Delete, null); Text("  Borrar") }
            }
        }
    }
    if (rename) NameDialog("Renombrar punto", w.name, null, "Guardar", onDismiss = { rename = false }) { Waypoints.rename(w, it); rename = false }
    if (delete) AlertDialog(onDismissRequest = { delete = false },
        title = { Text("¿Borrar el punto?") },
        text = { Text("«${w.name}» se borra y no se puede recuperar.") },
        confirmButton = { TextButton(onClick = { delete = false; Waypoints.delete(w); onDismiss() }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancelar") } })
}

/** Una fila de la lista de puntos (en Tracks). */
@Composable
fun WaypointRow(modifier: Modifier, w: Waypoint, loc: Location?, isTarget: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val a = away(loc, w)
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerHigh).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (isTarget) MapColors.target else MapColors.waypoint),
                contentAlignment = Alignment.Center) {
                if (a != null) Icon(Icons.Filled.Navigation, null, Modifier.rotate(a.bearing.toFloat()), tint = Color.White)
                else Icon(Icons.Filled.Place, null, tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(w.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a?.let { Direction.describe(it.distanceM, it.bearing) } ?: Geo.formatLatLon(w.lat, w.lon), fontSize = 13.sp)
                altitudeLine(w, loc)?.let { Text("Altitud $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (isTarget) Text("Destino", fontSize = 12.sp, color = MapColors.target, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

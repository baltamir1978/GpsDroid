package es.altamirano.gps.ui

import android.content.Context
import android.location.Location
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import es.altamirano.gps.core.Maneuver
import es.altamirano.gps.core.Maneuver.Kind
import es.altamirano.gps.core.TravelMode
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Routing
import es.altamirano.gps.data.RoutePreview
import es.altamirano.gps.data.RoutePreviewState
import es.altamirano.gps.core.Geo
import es.altamirano.common.ui.glass
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Navigation
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import es.altamirano.gps.core.OfflineZone
import androidx.compose.material3.LinearWavyProgressIndicator
import kotlinx.coroutines.launch

/** Más que esto entre el final de la ruta y el destino se avisa (y al llegar se guía en línea recta). */
const val END_GAP_M = 50.0

/** El dibujo de cada maniobra (los de Material, los mismos que usan los navegadores de Android). */
fun maneuverIcon(m: Maneuver): ImageVector = when (m.kind) {
    Kind.LEFT -> Icons.Filled.TurnLeft
    Kind.RIGHT -> Icons.Filled.TurnRight
    Kind.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    Kind.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    Kind.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    Kind.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    Kind.KEEP_LEFT, Kind.EXIT_LEFT -> Icons.Filled.ForkLeft
    Kind.KEEP_RIGHT, Kind.EXIT_RIGHT -> Icons.Filled.ForkRight
    Kind.UTURN_LEFT, Kind.UTURN -> Icons.Filled.UTurnLeft
    Kind.UTURN_RIGHT -> Icons.Filled.UTurnRight
    Kind.ROUNDABOUT -> Icons.Filled.RoundaboutRight
    Kind.ROUNDABOUT_LEFT -> Icons.Filled.RoundaboutLeft
    Kind.END -> Icons.Filled.Flag
    else -> Icons.Filled.Straight
}

private fun modeIcon(m: TravelMode) = when (m) {
    TravelMode.FOOT -> Icons.Filled.DirectionsWalk
    TravelMode.BIKE -> Icons.Filled.DirectionsBike
    TravelMode.CAR -> Icons.Filled.DirectionsCar
}

private fun lastMode(context: Context) =
    TravelMode.fromKey(context.getSharedPreferences("gps", Context.MODE_PRIVATE).getString("travel_mode", null))

/**
 * «Ruta hasta aquí»: elegir cómo (a pie, en bici, en coche), calcular la ruta desde donde estás con
 * el motor de BRouter que lleva la app y empezar a ir por ella. Si faltan los datos de rutas de la
 * zona, ofrece bajarlos (una vez; luego van sin conexión). [onStarted]: cerrar la ficha.
 */
@Composable
fun RouteToButton(w: Waypoint, loc: Location?, onStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askMode by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Faltan cuadros: cuáles, lo que pesan (null mientras se pregunta) y con qué modo se iba a calcular.
    var missing by remember { mutableStateOf<Triple<List<String>, Long?, TravelMode>?>(null) }
    // Bajando: cuadro, bytes y total.
    var downloading by remember { mutableStateOf<Triple<String, Long, Long>?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    lateinit var calculate: (TravelMode) -> Unit
    calculate = calc@{ mode ->
        context.getSharedPreferences("gps", Context.MODE_PRIVATE).edit { putString("travel_mode", mode.name) }
        // La de ahora, no la de cuando se abrió la ficha: tras bajar un cuadro pueden haber pasado minutos.
        val here = (Gps.location.value ?: loc)?.takeIf { Gps.isFresh(it) }
        if (here == null) { error = "Todavía no hay posición del GPS: la ruta sale desde donde estás."; return@calc }
        working = scope.launch {
            val r = Routing.route(context, here.latitude to here.longitude, w.lat to w.lon, mode, here.travelHeading(1f))
            working = null
            when (r) {
                is Routing.Result.Failed -> error = r.message
                is Routing.Result.MissingTiles -> {
                    missing = Triple(r.tiles, null, mode)
                    // Lo que pesan, para decirlo antes de bajar nada.
                    val sizes = withContext(Dispatchers.IO) { r.tiles.map { Routing.remoteSize(it) } }
                    if (missing?.first == r.tiles) missing = Triple(r.tiles, sizes.filterNotNull().sum().takeIf { sizes.all { it != null } }, mode)
                }
                is Routing.Result.Ok -> {
                    // Primero se ve en el mapa, con sus datos y las alternativas; desde ahí se empieza.
                    RoutePreview.show(r.route, w.name, w.lat, w.lon, mode, here.latitude to here.longitude, here.travelHeading(1f))
                    onStarted()
                }
            }
        }
    }
    val downloadThenRoute: (List<String>, TravelMode) -> Unit = { tiles, mode ->
        downloadJob = scope.launch {
            for (name in tiles) {
                downloading = Triple(name, 0L, 0L)
                val err = Routing.download(context, name) { got, total -> downloading = Triple(name, got, total) }
                if (err != null) { downloading = null; error = err; return@launch }
            }
            downloading = null
            calculate(mode)
        }
    }

    OutlinedButton(onClick = { askMode = true }) {
        Icon(Icons.Filled.Directions, null); Text("  Ruta hasta aquí")
    }

    if (askMode) AlertDialog(onDismissRequest = { askMode = false },
        title = { Text("Ruta hasta «${w.name}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Por caminos y carreteras, sin conexión (datos de OpenStreetMap). ¿Cómo vas?", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val last = lastMode(context)
                TravelMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { askMode = false; calculate(m) }
                        .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(modeIcon(m), null, tint = if (m == last) MapColors.follow else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("   ${m.label}", fontSize = 17.sp, fontWeight = if (m == last) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { askMode = false }) { Text("Cancelar") } })

    working?.let { job ->
        AlertDialog(onDismissRequest = {},
            title = { Text("Calculando la ruta…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Text("  Puede tardar unos segundos si el destino está lejos.", fontSize = 14.sp)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job.cancel(); working = null }) { Text("Cancelar") } })
    }

    missing?.let { (tiles, bytes, mode) ->
        AlertDialog(onDismissRequest = { missing = null },
            title = { Text("Faltan los datos de rutas") },
            text = {
                Text("Para calcular rutas en esta zona hace falta bajar ${if (tiles.size == 1) "su cuadro de datos" else "${tiles.size} cuadros de datos"} " +
                    "(${tiles.joinToString()}${bytes?.let { ", " + OfflineZone.formatBytes(it) } ?: ""}). Se baja una vez: luego las rutas " +
                    "funcionan sin conexión. Mejor con wifi.")
            },
            confirmButton = { TextButton(onClick = { missing = null; downloadThenRoute(tiles, mode) }) { Text("Bajar") } },
            dismissButton = { TextButton(onClick = { missing = null }) { Text("Ahora no") } })
    }

    downloading?.let { (name, got, total) ->
        AlertDialog(onDismissRequest = {},
            title = { Text("Bajando datos de rutas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cuadro $name: ${OfflineZone.formatBytes(got)}${if (total > 0) " de " + OfflineZone.formatBytes(total) else ""}", fontSize = 14.sp)
                    if (total > 0) LinearWavyProgressIndicator(progress = { (got.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    Text("Si lo cancelas, la próxima vez sigue donde se quedó.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { downloadJob?.cancel(); downloading = null }) { Text("Cancelar") } })
    }

    error?.let { e ->
        AlertDialog(onDismissRequest = { error = null },
            title = { Text("No hay ruta") },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Vale") } })
    }
}

/**
 * Abajo en el mapa, con una ruta en vista previa: cuál es (de cuántas), lo que mide, lo que se tarda
 * según BRouter y el desnivel; «Otra ruta» pide la siguiente alternativa y «Empezar» va por ella.
 */
@Composable
fun RoutePreviewCard(p: RoutePreviewState, modifier: Modifier, onNext: () -> Unit, onCancel: () -> Unit, onStart: () -> Unit) {
    val o = p.current
    Column(modifier.glass(RoundedCornerShape(28.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(modeIcon(p.mode), null, tint = MapColors.follow)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Hasta «${p.destName}»", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(if (p.options.size > 1 || p.exhausted) "Ruta ${p.selected + 1} de ${p.options.size}" else p.mode.label,
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(buildList {
            add(Geo.formatDistance(o.lengthM))
            o.route.totalS?.let { add(Geo.formatDuration(it).let { d -> if (it >= 3600) d.substringBeforeLast(':') + " h" else d.substringBefore(':') + " min" }) }
            if (o.ascentM != null && o.descentM != null) add("↑ ${es.altamirano.gps.core.Altitude.format(o.ascentM)} ↓ ${es.altamirano.gps.core.Altitude.format(o.descentM)}")
        }.joinToString("  ·  "), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        // Lo que la ruta no cubre: hasta el punto no hay camino conocido (falta en OpenStreetMap o es privado).
        val gap = o.route.gapToM(p.destLat, p.destLon)
        if (gap > END_GAP_M) Text("La ruta acaba a ${Geo.formatDistance(gap)} del punto: no hay camino conocido hasta él. " +
            "Al final te guío en línea recta.", fontSize = 13.sp, color = MapColors.target, fontWeight = FontWeight.SemiBold)
        if (o.route.offroad) Text("Incluye pistas de tierra (la ruta por carretera se quedaba lejos).", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        p.message?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancelar") }
            OutlinedButton(onClick = onNext, enabled = !p.loading) {
                if (p.loading) LoadingIndicator(Modifier.size(20.dp)) else Icon(Icons.Filled.AltRoute, null)
                Text(if (p.loading) "  Buscando…" else "  Otra ruta")
            }
            Button(onClick = onStart, enabled = !p.loading, colors = ButtonDefaults.buttonColors(containerColor = MapColors.follow, contentColor = Color.White)) {
                Icon(Icons.Filled.Navigation, null); Text("  Empezar", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

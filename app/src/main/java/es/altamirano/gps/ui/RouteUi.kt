package es.altamirano.gps.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.net.toUri
import es.altamirano.gps.core.Maneuver
import es.altamirano.gps.core.Maneuver.Kind
import es.altamirano.gps.core.PlannedRoute
import es.altamirano.gps.core.TravelMode
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.Following
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Routing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * «Ruta hasta aquí»: elegir cómo (a pie, en bici, en coche), pedir la ruta a BRouter desde donde
 * estás y empezar a ir por ella. Sin BRouter, explica cómo conseguirlo. [onStarted]: cerrar la ficha.
 */
@Composable
fun RouteToButton(w: Waypoint, loc: Location?, onStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askMode by remember { mutableStateOf(false) }
    var noBRouter by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<PlannedRoute, TravelMode>?>(null) }

    val go: (PlannedRoute, TravelMode) -> Unit = { r, mode ->
        Following.startRoute(context, r, w.name, w.lat, w.lon, mode)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            ?: onStarted()
    }
    // Las indicaciones y los avisos van también por notificación: primero el permiso.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let { (r, m) -> go(r, m) }; pending = null
    }
    val calculate: (TravelMode) -> Unit = calc@{ mode ->
        context.getSharedPreferences("gps", Context.MODE_PRIVATE).edit { putString("travel_mode", mode.name) }
        val here = loc?.takeIf { Gps.isFresh(it) }
        if (here == null) { error = "Todavía no hay posición del GPS: la ruta sale desde donde estás."; return@calc }
        working = scope.launch {
            val r = Routing.route(context, here.latitude to here.longitude, w.lat to w.lon, mode, here.travelHeading(1f))
            working = null
            when (r) {
                is Routing.Result.Failed -> error = r.message
                is Routing.Result.Ok -> if (Build.VERSION.SDK_INT >= 33) {
                    pending = r.route to mode; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else go(r.route, mode)
            }
        }
    }

    OutlinedButton(onClick = { if (Routing.isInstalled(context)) askMode = true else noBRouter = true }) {
        Icon(Icons.Filled.Directions, null); Text("  Ruta hasta aquí")
    }

    if (askMode) AlertDialog(onDismissRequest = { askMode = false },
        title = { Text("Ruta hasta «${w.name}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Por caminos y carreteras, calculada por BRouter sin conexión. ¿Cómo vas?", fontSize = 14.sp,
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
                    Text("  BRouter puede tardar unos segundos si el destino está lejos.", fontSize = 14.sp)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job.cancel(); working = null }) { Text("Cancelar") } })
    }

    error?.let { e ->
        AlertDialog(onDismissRequest = { error = null },
            title = { Text("No hay ruta") },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Vale") } },
            dismissButton = if (e.contains("BRouter")) ({
                TextButton(onClick = { error = null; openBRouter(context) }) { Text("Abrir BRouter") }
            }) else null)
    }

    if (noBRouter) AlertDialog(onDismissRequest = { noBRouter = false },
        title = { Text("Hace falta BRouter") },
        text = {
            Text("Las rutas las calcula BRouter, una app libre y gratuita que funciona sin conexión con los " +
                "mapas de OpenStreetMap. Instálala, ábrela una vez (elige su carpeta) y, en «Download Manager», " +
                "baja los cuadros de las zonas por donde vayas a moverte.")
        },
        confirmButton = {
            TextButton(onClick = {
                noBRouter = false
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Routing.FDROID_URL.toUri())) }
            }) { Text("F-Droid") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    noBRouter = false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=${Routing.PACKAGE}".toUri())) }
                        .onFailure { Toast.makeText(context, "No hay tienda de apps", Toast.LENGTH_SHORT).show() }
                }) { Text("Google Play") }
                TextButton(onClick = { noBRouter = false }) { Text("Cancelar") }
            }
        })
}

private fun openBRouter(context: Context) {
    context.packageManager.getLaunchIntentForPackage(Routing.PACKAGE)?.let { runCatching { context.startActivity(it) } }
}

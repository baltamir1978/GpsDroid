package es.altamirano.gps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.altamirano.gps.core.MapLayer
import es.altamirano.gps.core.OfflineZone
import es.altamirano.gps.core.RouteTiles
import es.altamirano.gps.data.DataUpdateWorker
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.data.Radars
import es.altamirano.gps.data.Routing
import es.altamirano.gps.data.Tiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline → Actualizaciones: de cuándo son los datos que usa la app (radares, cámaras de semáforo,
 * datos de rutas y mapas guardados), si hay algo nuevo y un botón para bajarlo ya. Lo pidió Bruno:
 * «que se pueda forzar desde la app… que marque si hay nuevas y deje descargar». Lo mismo se hace solo
 * una vez al día con wifi ([DataUpdateWorker]); esto es para no esperar.
 *
 * Al abrirse (con «Usar la red») pregunta las fechas al servidor: un HEAD por fuente, nada se baja.
 * Las cámaras de semáforo (Overpass) y los mapas del IGN no dicen la fecha: de esos se dice cuándo se
 * bajaron y se deja renovarlos.
 */
@Composable
internal fun UpdatesCard(online: Boolean) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    // Sube cada vez que algo se baja o se renueva: vuelve a leer las fechas.
    var stamp by remember { mutableIntStateOf(0) }
    var radarRemote by remember { mutableStateOf<Long?>(null) }
    var tileRemote by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var checking by remember { mutableStateOf(false) }
    var checkedOnce by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var renewLayer by remember { mutableStateOf<MapLayer?>(null) }

    val routeTiles by Routing.tiles.collectAsState()
    val routesWork by remember { DataUpdateWorker.routesNowFlow(context) }.collectAsState(emptyList())
    val routesRunning = routesWork.any { !it.state.isFinished }

    fun check() {
        if (!Tiles.canUseNetwork(context)) return
        checking = true
        scope.launch {
            val (r, t) = withContext(Dispatchers.IO) {
                Routing.refreshTiles(context)
                Radars.remoteModified() to Routing.tiles.value.associate { it.name to Routing.remoteModified(it.name) }
            }
            radarRemote = r; tileRemote = t; checking = false; checkedOnce = true
        }
    }
    LaunchedEffect(online) { if (online) check() }
    // Al acabar de bajar cuadros de rutas, se vuelven a leer.
    LaunchedEffect(routesRunning) { if (!routesRunning) { Routing.refreshTiles(context); stamp++ } }

    val radarsLocal = remember(stamp) { Radars.updatedAt(context) }
    val redLocal = remember(stamp) { Radars.redLightsUpdatedAt(context) }
    // La base de datos de teselas, fuera del hilo de la pantalla.
    val layers by produceState(emptyList<Triple<MapLayer, Long, Long?>>(), stamp) {
        value = withContext(Dispatchers.IO) { MapLayer.entries.map { Triple(it, Tiles.countFor(it), Tiles.oldestSaved(it)) }.filter { it.second > 0 } }
    }

    fun run(id: String, block: suspend () -> String?) {
        busy = id; message = null
        scope.launch {
            message = block() ?: "Hecho"
            busy = null; stamp++
            check()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Radares.
        val radarsNew = radarRemote != null && RouteTiles.outdated(radarsLocal ?: 0L, radarRemote)
        UpdateRow(
            title = "Radares",
            detail = (radarsLocal?.let { "Lista del ${shortDate(it)}" } ?: "La lista que trae la app") +
                when {
                    radarsNew -> " · hay una nueva del ${shortDate(radarRemote!!)}"
                    radarRemote != null -> " · al día"
                    else -> ""
                },
            isNew = radarsNew,
            button = if (busy == "radares") "Bajando…" else if (radarsNew) "Descargar" else "Volver a bajar",
            enabled = online && busy == null,
        ) { run("radares") { Radars.updateRadars(context) } }

        // Cámaras de semáforo: Overpass no da fecha; se dice cuándo se bajó.
        val redOld = redLocal == null || System.currentTimeMillis() - redLocal > java.util.concurrent.TimeUnit.DAYS.toMillis(7)
        UpdateRow(
            title = "Cámaras de semáforo",
            detail = (redLocal?.let { "Lista del ${shortDate(it)}" } ?: "La lista que trae la app") +
                (if (redOld) " · puede haber cambios" else ""),
            isNew = false,
            button = if (busy == "semaforos") "Bajando… (hasta 3 min)" else "Actualizar",
            enabled = online && busy == null,
        ) { run("semaforos") { Radars.updateRedLights(context) } }

        // Datos de rutas.
        val outdated = routeTiles.filter { RouteTiles.outdated(it.modified, tileRemote[it.name]) }
        UpdateRow(
            title = "Datos de rutas",
            detail = if (routeTiles.isEmpty()) "Ninguno todavía: se bajan al pedir la primera ruta en una zona"
            else routeTiles.joinToString("\n") { t ->
                "${t.name}: datos del ${shortDate(t.modified)}" + when {
                    t in outdated -> " · hay nuevos del ${shortDate(tileRemote[t.name]!!)} (${OfflineZone.formatBytes(t.bytes)})"
                    tileRemote[t.name] != null -> " · al día"
                    else -> ""
                }
            },
            isNew = outdated.isNotEmpty(),
            button = when {
                routesRunning -> "Bajando…"
                outdated.isNotEmpty() -> "Descargar"
                else -> null
            },
            enabled = online && !routesRunning,
        ) { DataUpdateWorker.updateRoutesNow(context) }

        // Mapas guardados: el IGN no dice si una tesela ha cambiado.
        UpdateRow(
            title = "Mapas guardados",
            detail = if (layers.isEmpty()) "Ninguno todavía"
            else layers.joinToString("\n") { (l, n, oldest) ->
                "${l.label}: ${OfflineZone.formatCount(n)} teselas · " +
                    (if (oldest == -1L) "se renuevan al verlas con red" else oldest?.let { "las más viejas, del ${shortDate(it)}" } ?: "")
            } + "\nEl IGN no dice si un mapa ha cambiado: se renuevan solos al cumplir un año.",
            isNew = false,
            button = null,
            enabled = false,
        ) {}
        layers.forEach { (l, _, oldest) ->
            if (oldest != -1L) TextButton(onClick = { renewLayer = l }) { Text("Renovar ${l.label}") }
        }

        if (checking) Text("Buscando novedades…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, fontSize = 13.sp) }
        if (!online) Text("Activa «Usar la red» para buscar novedades y descargar.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        val checked = prefs.routeTilesChecked
        Text("Solas, una vez al día con wifi: los radares (si tienen más de un día), las cámaras de semáforo " +
            "(más de una semana) y los datos de rutas (cada semana)" +
            (if (checked > 0) "; última comprobación de las rutas: ${shortDate(checked)}." else "."),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (online && checkedOnce && !checking) TextButton(onClick = { check() }) { Text("Buscar novedades otra vez") }
    }

    renewLayer?.let { l ->
        AlertDialog(onDismissRequest = { renewLayer = null },
            title = { Text("¿Renovar ${l.label}?") },
            text = { Text("No se borra nada: sin red se siguen viendo. Cada tesela se vuelve a bajar del IGN al verla con red, " +
                "o al repetir la descarga de una zona (gasta lo mismo que la primera vez).") },
            confirmButton = { TextButton(onClick = {
                val layer = l; renewLayer = null
                run("mapas") { withContext(Dispatchers.IO) { Tiles.renew(layer) }; "Marcadas para renovar" }
            }) { Text("Renovar") } },
            dismissButton = { TextButton(onClick = { renewLayer = null }) { Text("Cancelar") } })
    }
}

/** Una fila: qué es, de cuándo y si hay algo nuevo (en el color de la marca), con su botón. */
@Composable
private fun UpdateRow(title: String, detail: String, isNew: Boolean, button: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = if (isNew) FontWeight.SemiBold else FontWeight.Normal)
                if (isNew) Text("  Nuevo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (button != null) {
            if (isNew) FilledTonalButton(onClick = onClick, enabled = enabled) { Text(button) }
            else TextButton(onClick = onClick, enabled = enabled) { Text(button) }
        }
    }
}

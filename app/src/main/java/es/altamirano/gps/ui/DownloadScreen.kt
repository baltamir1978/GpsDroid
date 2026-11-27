package es.altamirano.gps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import es.altamirano.common.ui.CardShape
import es.altamirano.common.ui.TonalCard
import es.altamirano.common.ui.Header
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.altamirano.common.ui.gutter
import es.altamirano.gps.core.BBox
import es.altamirano.gps.core.MapLayer
import es.altamirano.gps.core.MapStyle
import es.altamirano.gps.core.OfflineZone
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Routing
import es.altamirano.gps.data.Radars
import es.altamirano.gps.data.RadarSettings
import es.altamirano.gps.data.RadarAlertMode
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.data.Tiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val RADII = listOf(1.0, 2.0, 5.0, 10.0, 20.0)

/**
 * Bajar una zona para cuando no haya cobertura (como «Mapas offline» de ZeroGPS): alrededor del
 * centro del mapa o de tu posición, o una sierra entera; uno de los mapas o todos; de qué zoom a
 * qué zoom. Antes de empezar dice cuántas teselas son, lo que ocupará y el espacio libre.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val dl by Tiles.download.collectAsState()

    // Dónde: 0 = centro del mapa, 1 = mi posición, 2+ = zona ya hecha.
    var where by rememberSaveable { mutableIntStateOf(0) }
    var radius by rememberSaveable { mutableStateOf(5.0) }
    // Qué mapa: índice de MapStyle o -1 = todos (lo que pidió Bruno por defecto en ZeroGPS).
    var which by rememberSaveable { mutableIntStateOf(-1) }
    var zmin by rememberSaveable { mutableIntStateOf(OfflineZone.ZOOM_MIN) }
    var zmax by rememberSaveable { mutableIntStateOf(OfflineZone.ZOOM_MAX) }
    var online by remember { mutableStateOf(prefs.online) }
    var keepOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var purge by remember { mutableStateOf<MapLayer?>(null) }

    val here = Gps.location.collectAsState().value
    val center: Pair<Double, Double>? = when (where) {
        0 -> prefs.view?.let { it.first to it.second } ?: prefs.last
        1 -> here?.let { it.latitude to it.longitude } ?: prefs.last
        else -> null
    }
    val box: BBox? = if (where >= 2) OfflineZone.PRESETS[where - 2].second
        else center?.let { OfflineZone.bboxAround(it.first, it.second, radius) }
    val styles = if (which < 0) MapStyle.entries else listOf(MapStyle.entries[which])
    // El híbrido es la ortofoto más los rótulos: si también va «Satélite», la ortofoto se baja una vez.
    val layers = MapStyle.layersOf(styles)
    val (tiles, bytes) = box?.let { OfflineZone.plan(it, zmin, zmax, layers) } ?: (0L to 0L)
    val free = Tiles.cacheDir.apply { mkdirs() }.usableSpace
    val tooMany = tiles > OfflineZone.MAX_TILES

    var stamp by remember { mutableIntStateOf(0) }
    val saved by produceState(emptyMap<MapLayer, es.altamirano.gps.data.SavedLayer>(), dl.running, stamp) {
        Tiles.ensureMapLibre(context)
        value = Tiles.saved()
    }
    val dbBytes by produceState(0L, dl.running, stamp) { value = withContext(Dispatchers.IO) { Tiles.cacheBytes() } }
    // Los mapas guardados con la versión anterior (osmdroid): ya no se usan.
    val oldBytes by produceState(0L, stamp) { value = withContext(Dispatchers.IO) { Tiles.oldCacheBytes() } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var clearViewed by remember { mutableStateOf(false) }
    var deleteOld by remember { mutableStateOf(false) }

    val side = gutter()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomBar.current + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Mapas offline", "Para cuando no haya cobertura", Icons.Filled.CloudDownload, side)
        Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Section("Zona") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(where == 0, { where = 0 }, { Text("Centro del mapa") })
                FilterChip(where == 1, { where = 1 }, { Text("Mi posición") })
            }
            if (where < 2) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RADII.forEach { r -> FilterChip(radius == r, { radius = r }, { Text("${r.roundToInt()} km") }) }
            }
            if (where < 2 && center == null) Text("Sin posición todavía: abre el mapa o espera al GPS.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            Text("O una zona entera:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OfflineZone.PRESETS.forEachIndexed { i, (name, _) -> FilterChip(where == i + 2, { where = i + 2 }, { Text(name) }) }
            }
        }

        Section("Mapa") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(which < 0, { which = -1 }, { Text("Todos") })
                MapStyle.entries.forEachIndexed { i, s -> FilterChip(which == i, { which = i }, { Text(s.label) }) }
            }
        }

        Section("Zoom") {
            val top = layers.maxOf { it.maxZoom }
            Text("De z$zmin a " + (if (zmax >= top) "máx. (" + styles.map { it.maxZoom }.distinct().joinToString(" / ") { "z$it" } + ")" else "z$zmax"), fontSize = 14.sp)
            RangeSlider(value = zmin.toFloat()..zmax.coerceAtMost(top).toFloat(),
                onValueChange = { zmin = it.start.roundToInt(); zmax = it.endInclusive.roundToInt().let { e -> if (e >= top) OfflineZone.ZOOM_MAX else e } },
                valueRange = OfflineZone.ZOOM_MIN.toFloat()..top.toFloat(), steps = top - OfflineZone.ZOOM_MIN - 1)
            Text("z12 se ve la comarca, z15 los caminos, z17 cada casa; cada zoom más multiplica por cuatro.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Box(Modifier.fillMaxWidth().clip(CardShape).background(Brush.linearGradient(listOf(
            MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.primaryContainer)))) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dl.running) {
                    Text((if (dl.route) "Guardando los mapas del camino" else "Descargando") + (dl.layer?.let { " · ${it.label}" } ?: ""), fontWeight = FontWeight.Bold)
                    LinearWavyProgressIndicator(progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f }, Modifier.fillMaxWidth().height(14.dp))
                    Text("${OfflineZone.formatCount(dl.done.toLong())} de ${OfflineZone.formatCount(dl.total.toLong())} archivos" +
                        if (dl.failed > 0) " · ${dl.failed} fallos" else "", fontSize = 14.sp)
                    Text("Mantén la app abierta hasta que acabe.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { Tiles.cancel() }) { Text("Cancelar") }
                } else {
                    Text(OfflineZone.formatBytes(bytes), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("${OfflineZone.formatCount(tiles)} teselas (aprox.)", fontWeight = FontWeight.Medium)
                    Text("Libre en el móvil: ${OfflineZone.formatBytes(free)}", fontSize = 14.sp)
                    if (tooMany) Text("Pasa del tope de ${OfflineZone.formatCount(OfflineZone.MAX_TILES)} teselas por descarga: baja el zoom máximo o el radio. (El IGN es un servicio público: nada de bajarlo entero.)",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    if (bytes > free) Text("No cabe.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    dl.message?.let { Text(it, fontSize = 13.sp) }
                    Button(onClick = { box?.let { Tiles.start(it, zmin, zmax, layers) } }, Modifier.height(52.dp),
                        enabled = box != null && tiles > 0 && !tooMany && bytes < free && online) {
                        Icon(Icons.Filled.CloudDownload, null); Text("  Descargar", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                    if (!online) Text("Activa «Usar la red» para descargar.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Text("Lo que ya está guardado no se vuelve a bajar: repetir una descarga la reanuda.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Section("Guardado en el móvil · ${OfflineZone.formatBytes(dbBytes)}") {
            MapLayer.entries.forEach { l ->
                val sv = saved[l]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.label, fontSize = 14.sp)
                        Text(if (sv == null) "Nada descargado" else "${sv.regions} ${if (sv.regions == 1) "descarga" else "descargas"} (zonas y caminos) · ${OfflineZone.formatBytes(sv.bytes)}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { purge = l }, enabled = !dl.running && sv != null) { Text("Vaciar") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lo visto en el mapa (sin descargar)", Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = { clearViewed = true }, enabled = !dl.running) { Text("Vaciar") }
            }
            Text("Lo que se ve con red se guarda solo y sirve sin cobertura; no tiene tope. Al empezar a seguir una ruta o un track con red se guardan además los mapas del camino (±500 m): el callejero y el mapa que tengas puesto.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (oldBytes > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mapas de la versión anterior · ${OfflineZone.formatBytes(oldBytes)}", fontSize = 14.sp)
                    Text("Desde la 0.27.0 los mapas van con otro motor: lo descargado antes ya no se usa. Vuelve a bajar las zonas que quieras.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { deleteOld = true }) { Text("Borrar") }
            }
        }

        Section("Actualizaciones") { UpdatesCard(online) }

        Section("Datos para rutas") {
            // Los cuadros de BRouter: se bajan al pedir la primera ruta en una zona; aquí se ven y se borran.
            val routeTiles by Routing.tiles.collectAsState()
            LaunchedEffect(Unit) { Routing.refreshTiles(context) }
            var deleteTile by remember { mutableStateOf<Routing.Tile?>(null) }
            if (routeTiles.isEmpty()) Text("Ninguno todavía. Se bajan solos (preguntando) la primera vez que pides una ruta en una zona: cada cuadro cubre 5° × 5° y pesa de 20 a 170 MB.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            routeTiles.forEach { tile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tile.name, fontSize = 15.sp)
                        Text(OfflineZone.formatBytes(tile.bytes) + " · datos del " + shortDate(tile.modified),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { deleteTile = tile }) { Text("Borrar") }
                }
            }
            Text("Datos de rutas: © colaboradores de OpenStreetMap (ODbL), preparados por BRouter.", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            deleteTile?.let { tile ->
                AlertDialog(onDismissRequest = { deleteTile = null },
                    title = { Text("¿Borrar ${tile.name}?") },
                    text = { Text("Sin él no se pueden calcular rutas en esa zona hasta volver a bajarlo (${OfflineZone.formatBytes(tile.bytes)}).") },
                    confirmButton = { TextButton(onClick = { Routing.deleteTile(context, tile.name); deleteTile = null }) { Text("Borrar") } },
                    dismissButton = { TextButton(onClick = { deleteTile = null }) { Text("Cancelar") } })
            }
        }

        Section("Ajustes") {
            RadarSettingsCard(context)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Usar la red", fontSize = 15.sp)
                    Text("Bajar los mapas que falten al mover el mapa. Apagado, sólo se ve lo guardado (no gasta datos).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(online, { online = it; prefs.online = it; Tiles.setOnline(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pantalla siempre encendida en el mapa", fontSize = 15.sp)
                    Text("Para llevar el móvil en el soporte de la bici o el coche. Gasta batería.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(keepOn, { keepOn = it; prefs.keepScreenOn = it })
            }
        }
        Text("Mapas: ${MapLayer.ATTRIBUTION} (CC BY 4.0).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Radares: Radares Anunciados (ODbL) con datos de la DGT, el Servei Català de Trànsit, Euskadi, ayuntamientos y © colaboradores de OpenStreetMap.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Para saber qué versión hay en el móvil sin ir a los ajustes de Android.
        Text("IGN GPS ${es.altamirano.gps.BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    purge?.let { l ->
        AlertDialog(onDismissRequest = { purge = null },
            title = { Text("¿Vaciar ${l.label}?") },
            text = { Text("Se borran las zonas y los caminos descargados de este mapa. Sin cobertura dejarán de verse.") },
            confirmButton = { TextButton(onClick = { purge = null; scope.launch { Tiles.purge(l); stamp++ } }) { Text("Vaciar") } },
            dismissButton = { TextButton(onClick = { purge = null }) { Text("Cancelar") } })
    }
    if (clearViewed) AlertDialog(onDismissRequest = { clearViewed = false },
        title = { Text("¿Vaciar lo visto?") },
        text = { Text("Se borra lo que se ha guardado al ir viendo el mapa con red. Lo descargado (zonas y caminos) se queda.") },
        confirmButton = { TextButton(onClick = { clearViewed = false; scope.launch { Tiles.clearViewed(); stamp++ } }) { Text("Vaciar") } },
        dismissButton = { TextButton(onClick = { clearViewed = false }) { Text("Cancelar") } })
    if (deleteOld) AlertDialog(onDismissRequest = { deleteOld = false },
        title = { Text("¿Borrar los mapas de la versión anterior?") },
        text = { Text("Son ${OfflineZone.formatBytes(oldBytes)} que ya no usa la app (el motor de mapas es otro desde la 0.27.0). No se puede deshacer; las zonas se vuelven a bajar desde aquí.") },
        confirmButton = { TextButton(onClick = { deleteOld = false; scope.launch { withContext(Dispatchers.IO) { Tiles.deleteOldCache() }; stamp++ } }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { deleteOld = false }) { Text("Cancelar") } })
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    TonalCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Los ajustes de los avisos de radares: encendido, a qué distancia, cómo y de qué clases. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RadarSettingsCard(context: android.content.Context) {
    val s by Radars.settingsFlow(context).collectAsState()
    val cur = s ?: return
    val set: (RadarSettings) -> Unit = { Radars.save(context, it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Avisar de radares en coche", fontSize = 15.sp)
            Text("Yendo por una ruta en coche: radares de la DGT, Cataluña, Euskadi, ayuntamientos y OpenStreetMap (lista de Radares Anunciados). " +
                "La fecha de la lista, en Actualizaciones.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(cur.enabled, { set(cur.copy(enabled = it)) })
    }
    if (!cur.enabled) return
    Text("Avisar a", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadarSettings.DISTANCES.forEach { d ->
            FilterChip(selected = cur.distanceM == d, onClick = { set(cur.copy(distanceM = d)) },
                label = { Text(if (d >= 1000) "${d / 1000} km" else "$d m") })
        }
    }
    Text("Cómo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadarAlertMode.entries.forEach { m ->
            FilterChip(selected = cur.mode == m, onClick = { set(cur.copy(mode = m)) }, label = { Text(m.label) })
        }
    }
    Text("De qué radares", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    listOf(
        es.altamirano.gps.core.RadarKind.FIXED to "Fijos y en remolque",
        es.altamirano.gps.core.RadarKind.SECTION to "Tramos de velocidad media",
        es.altamirano.gps.core.RadarKind.MOBILE to "Móviles anunciados (el día que están)",
        es.altamirano.gps.core.RadarKind.RED_LIGHT to "Cámaras de semáforo en rojo (a 200 m como mucho)",
        es.altamirano.gps.core.RadarKind.MOBILE_STRETCH to "Tramos donde la DGT pone radares móviles (muchos; casi nunca hay uno)",
    ).forEach { (k, label) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(k in cur.kinds, { on -> set(cur.copy(kinds = if (on) cur.kinds + k else cur.kinds - k)) })
            Text(label, fontSize = 14.sp)
        }
    }
}

internal fun shortDate(ms: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

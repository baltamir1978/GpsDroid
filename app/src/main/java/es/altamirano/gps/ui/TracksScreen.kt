package es.altamirano.gps.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import es.altamirano.common.ui.CardShape
import es.altamirano.common.ui.Header
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Route
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Map
import androidx.lifecycle.compose.LifecycleResumeEffect
import es.altamirano.common.ui.TonalCard
import es.altamirano.common.ui.gutter
import es.altamirano.gps.core.Geo
import es.altamirano.gps.data.Recording
import es.altamirano.gps.data.TrackInfo
import es.altamirano.gps.data.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TracksScreen() {
    val context = LocalContext.current
    val version by Tracks.version.collectAsState()
    val rec by Recording.state.collectAsState()
    val list by produceState(emptyList<TrackInfo>(), version, rec.active) {
        value = withContext(Dispatchers.IO) { Tracks.list() }
    }
    var toDelete by remember { mutableStateOf<TrackInfo?>(null) }
    var details by remember { mutableStateOf<TrackInfo?>(null) }
    val share: (TrackInfo) -> Unit = { t ->
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", t.file)
        val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, t.name))
    }
    // Si Android optimiza la batería de la app, con la pantalla apagada puede espaciar las
    // posiciones del GPS aunque el servicio siga vivo: se ofrece quitarlo (se mira al volver).
    var batteryOptimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    LifecycleResumeEffect(Unit) { batteryOptimized = isBatteryOptimized(context); onPauseOrDispose {} }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Recording.start(context) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Tracks.import(context, uri)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val side = gutter()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomBar.current + 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Header("Tracks", when (list.size) { 0 -> "Graba o importa un GPX"; 1 -> "1 guardado"; else -> "${list.size} guardados" }, Icons.Filled.Route, side)
        }
        item {
            val recordingBrush = Brush.linearGradient(listOf(MapColors.recording, Color(0xFFFF7043)))
            Box(Modifier.padding(horizontal = side).fillMaxWidth().clip(CardShape)
                .then(if (rec.active) Modifier.background(recordingBrush) else Modifier.background(MaterialTheme.colorScheme.primaryContainer))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rec.active) {
                        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                        LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
                        Text("Grabando", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Text(Geo.formatDistance(rec.lengthM), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${Geo.formatDuration((now - rec.startedElapsed) / 1000)} · ${rec.points.size} puntos · ↑ ${Geo.formatDistance(Geo.ascentM(rec.points))}",
                            color = Color.White)
                        Text("Sigue con la pantalla apagada. Se guarda cada minuto.", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
                        Button(onClick = { Recording.stop(context) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MapColors.recording)) {
                            Icon(Icons.Filled.Stop, null); Text("  Parar y guardar", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text("Graba por dónde vas", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Un punto cada 5 m (o cada 30 s si vas despacio); las posiciones con más de 30 m de error no entran.",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else Recording.start(context)
                            }, colors = ButtonDefaults.buttonColors(containerColor = MapColors.recording, contentColor = Color.White)) {
                                Icon(Icons.Filled.FiberManualRecord, null); Text("  Grabar", fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, null); Text("  Importar GPX") }
                        }
                    }
                }
            }
        }
        if (batteryOptimized) item {
            TonalCard(Modifier.padding(horizontal = side)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BatteryAlert, null, tint = MaterialTheme.colorScheme.tertiary)
                        Text("  Para grabar con la pantalla apagada", fontWeight = FontWeight.Bold)
                    }
                    Text("Android está optimizando la batería de IGN GPS: con la pantalla apagada puede tardar en darle posiciones y el track saldría a saltos. Quitarlo sólo gasta más mientras grabas (con el GPS apagado no hace nada).",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { requestNoBatteryOptimization(context) }) { Text("Quitar la optimización de batería") }
                }
            }
        }
        if (list.isEmpty()) item {
            Text("Todavía no hay tracks guardados.", Modifier.padding(horizontal = side, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(list, key = { _, t -> t.file.name }) { i, t ->
            val recordingThis = rec.active && rec.file == t.file
            TrackRow(Modifier.padding(horizontal = side), i, t, recordingThis, onOpen = { details = t }, onMap = { Tracks.open(t.file) },
                onDelete = { toDelete = t }, onShare = { share(t) })
        }
    }

    toDelete?.let { t ->
        AlertDialog(onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar el track?") },
            text = { Text("«${t.name}» se borra del móvil y no se puede recuperar.") },
            confirmButton = { TextButton(onClick = { Tracks.delete(t.file); toDelete = null }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } })
    }

    details?.let { t ->
        TrackDetailSheet(t, onDismiss = { details = null }, onShowOnMap = { details = null; Tracks.open(t.file) }, onShare = { share(t) })
    }
}

private fun isBatteryOptimized(context: Context): Boolean =
    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/**
 * El diálogo del sistema que pregunta directamente (no hay Play Store que lo prohíba: la app no va a
 * Google Play). Si el sistema no lo tiene, la lista de apps de la optimización.
 */
@android.annotation.SuppressLint("BatteryLife")
private fun requestNoBatteryOptimization(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ("package:" + context.packageName).toUri())
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

@Composable
private fun TrackRow(modifier: Modifier, index: Int, t: TrackInfo, recording: Boolean, onOpen: () -> Unit, onMap: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // Cada fila con un color del tema para el icono, por turnos: más vivo que una lista gris.
    val c = MaterialTheme.colorScheme
    val (bg, fg) = listOf(c.primary to c.onPrimary, c.tertiary to c.onTertiary, c.secondary to c.onSecondary)[index % 3]
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerHigh).clickable(enabled = t.points > 0, onClick = onOpen)) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (recording) MapColors.recording else bg), contentAlignment = Alignment.Center) {
                Icon(if (recording) Icons.Filled.FiberManualRecord else Icons.Filled.Route, null, tint = if (recording) Color.White else fg)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = buildList {
                    add(Geo.formatDistance(t.lengthM))
                    t.durationS?.let { add(Geo.formatDuration(it)) }
                    if (t.ascentM > 0) add("↑ " + Geo.formatDistance(t.ascentM))
                    add("${t.points} puntos")
                }
                Text(parts.joinToString(" · "), fontSize = 13.sp)
                Text(if (recording) "Grabándose ahora" else dateFormat.format(Instant.ofEpochMilli(t.modified).atZone(ZoneId.systemDefault())),
                    fontSize = 12.sp, color = if (recording) MapColors.recording else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (t.points > 0) DropdownMenuItem(text = { Text("Ver en el mapa") }, leadingIcon = { Icon(Icons.Filled.Map, null) }, onClick = { menu = false; onMap() })
                    DropdownMenuItem(text = { Text("Compartir GPX") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
                    if (!recording) DropdownMenuItem(text = { Text("Borrar") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

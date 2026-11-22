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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
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

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Recording.start(context) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Tracks.import(context, uri)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val side = gutter()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(side, 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Tracks", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rec.active) {
                        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                        LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
                        Text("Grabando", fontWeight = FontWeight.Bold, color = MapColors.recording)
                        Text("${Geo.formatDistance(rec.lengthM)} · ${Geo.formatDuration((now - rec.startedElapsed) / 1000)} · ${rec.points.size} puntos" +
                            " · ↑ ${Geo.formatDistance(Geo.ascentM(rec.points))}")
                        Text("Sigue con la pantalla apagada. Se guarda cada minuto.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { Recording.stop(context) }, colors = ButtonDefaults.buttonColors(containerColor = MapColors.recording)) {
                            Icon(Icons.Filled.Stop, null); Text("  Parar y guardar")
                        }
                    } else {
                        Text("Graba por dónde vas en un GPX. Un punto cada 5 m (o cada 30 s si vas despacio); las posiciones con más de 30 m de error no entran.",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else Recording.start(context)
                            }) { Icon(Icons.Filled.FiberManualRecord, null, tint = Color(0xFFFFCDD2)); Text("  Grabar") }
                            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, null); Text("  Importar GPX") }
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) item {
            Text("Todavía no hay tracks guardados.", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(list, key = { it.file.name }) { t ->
            val recordingThis = rec.active && rec.file == t.file
            TrackRow(t, recordingThis, onOpen = { Tracks.open(t.file) }, onDelete = { toDelete = t }, onShare = {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", t.file)
                val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, t.name))
            })
        }
    }

    toDelete?.let { t ->
        AlertDialog(onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar el track?") },
            text = { Text("«${t.name}» se borra del móvil y no se puede recuperar.") },
            confirmButton = { TextButton(onClick = { Tracks.delete(t.file); toDelete = null }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } })
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

@Composable
private fun TrackRow(t: TrackInfo, recording: Boolean, onOpen: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(enabled = t.points > 0, onClick = onOpen)) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
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
                    DropdownMenuItem(text = { Text("Compartir GPX") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
                    if (!recording) DropdownMenuItem(text = { Text("Borrar") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

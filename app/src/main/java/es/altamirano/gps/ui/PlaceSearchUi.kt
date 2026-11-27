package es.altamirano.gps.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.altamirano.gps.core.Direction
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.Place
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Places
import es.altamirano.gps.data.Waypoints
import kotlinx.coroutines.delay

/**
 * La lupa del mapa: buscar un sitio por nombre o dirección (Photon, sobre OpenStreetMap) mientras
 * se escribe. Tocar un resultado lo pasa a [onPick] (que abre su ficha).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaceSearchSheet(loc: Location?, onDismiss: () -> Unit, onPick: (Place) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var submit by remember { mutableStateOf(0) }
    val handled = remember { intArrayOf(0) }
    val focus = remember { FocusRequester() }

    // Al escribir, medio segundo de espera (no una petición por letra); «Buscar» en el teclado, en el acto.
    LaunchedEffect(query, submit) {
        val q = query.trim()
        if (q.length < 3) { results = emptyList(); message = null; loading = false; return@LaunchedEffect }
        if (submit == handled[0]) delay(500)
        handled[0] = submit
        loading = true
        when (val r = Places.search(context, q)) {
            is Places.Result.Ok -> { results = r.places; message = if (r.places.isEmpty()) "No se ha encontrado nada" else null }
            is Places.Result.Failed -> { results = emptyList(); message = r.message }
        }
        loading = false
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                placeholder = { Text("Dirección, pueblo, sitio…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (loading) LoadingIndicator(Modifier.size(28.dp))
                    else if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Borrar") }
                },
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit++ }))
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(4.dp)) }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(results) { p -> PlaceRow(p, loc) { onPick(p) } }
            }
            Text("Búsqueda: Photon · © OpenStreetMap", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaceRow(p: Place, loc: Location?, onClick: () -> Unit) {
    val here = loc?.takeIf { Gps.isFresh(it) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) { Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.detail.isNotEmpty()) Text(p.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        here?.let { Text(Geo.formatDistance(Geo.distanceM(it.latitude, it.longitude, p.lat, p.lon)), fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp)) }
    }
}

/** La ficha de un sitio encontrado: ruta hasta allí, ir hacia allí, verlo en el mapa o guardarlo como punto. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaceSheet(p: Place, loc: Location?, onDismiss: () -> Unit, onShowOnMap: () -> Unit) {
    var save by remember { mutableStateOf(false) }
    val w = remember(p) { p.toWaypoint() }
    val here = loc?.takeIf { Gps.isFresh(it) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MapColors.target),
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.Place, null, tint = Color.White) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (p.detail.isNotEmpty()) Text(p.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (here != null) {
                val bearing = Direction.bearingDeg(here.latitude, here.longitude, p.lat, p.lon)
                Text("${Geo.formatDistance(Geo.distanceM(here.latitude, here.longitude, p.lat, p.lon))} al ${Direction.cardinalName(bearing)}",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            RouteToButton(w, loc, onStarted = onDismiss)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Waypoints.setTarget(w); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = MapColors.target, contentColor = Color.White)) {
                    Icon(Icons.Filled.Navigation, null); Text("  Ir hacia aquí")
                }
                OutlinedButton(onClick = onShowOnMap) { Icon(Icons.Filled.Map, null); Text("  Ver en el mapa") }
                OutlinedButton(onClick = { save = true }) { Icon(Icons.Filled.AddLocationAlt, null); Text("  Guardar como punto") }
            }
        }
    }
    if (save) NameDialog("Guardar punto", p.name, Geo.formatLatLon(p.lat, p.lon), "Guardar", onDismiss = { save = false }) { name ->
        Waypoints.add(name, p.lat, p.lon, null)
        save = false
        onDismiss()
    }
}

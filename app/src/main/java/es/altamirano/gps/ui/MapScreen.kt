package es.altamirano.gps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import es.altamirano.gps.core.Geo
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.data.Recording
import es.altamirano.gps.data.Tiles
import es.altamirano.gps.data.Tracks
import kotlinx.coroutines.delay
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay

@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var layer by remember { mutableStateOf(prefs.layer) }
    var online by remember { mutableStateOf(prefs.online) }
    // Seguir la posición: se quita al mover el mapa con el dedo y se vuelve con el botón.
    var follow by remember { mutableStateOf(prefs.view == null) }

    val location by Gps.location.collectAsState()
    val rec by Recording.state.collectAsState()
    val opened by Tracks.opened.collectAsState()
    val fit by Tracks.fitRequest.collectAsState()

    LifecycleStartEffect(Unit) {
        Gps.acquire("map")
        onStopOrDispose { Gps.release("map") }
    }

    val density = context.resources.displayMetrics.density
    val openedLine = remember { trackLine(MapColors.opened.toArgb(), 5f * density) }
    val recLine = remember { trackLine(MapColors.recording.toArgb(), 4f * density) }
    val position = remember { PositionOverlay(density) }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(Tiles.sources.getValue(layer))
            isTilesScaledToDpi = true
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            maxZoomLevel = layer.maxZoom.toDouble()
            // España por defecto; si no, dónde se quedó o la última posición conocida.
            val v = prefs.view
            val last = prefs.last
            when {
                v != null -> { controller.setZoom(v.third); controller.setCenter(GeoPoint(v.first, v.second)) }
                last != null -> { controller.setZoom(15.0); controller.setCenter(GeoPoint(last.first, last.second)) }
                else -> { controller.setZoom(6.0); controller.setCenter(GeoPoint(40.2, -3.7)) }
            }
            overlays += openedLine
            overlays += recLine
            overlays += position
            overlays += ScaleBarOverlay(this).apply { setAlignBottom(true); setScaleBarOffset((12 * density).toInt(), (12 * density).toInt()) }
            overlays += CopyrightOverlay(context).apply { setAlignRight(true) }
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_MOVE) follow = false; false }
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            val c = mapView.mapCenter
            prefs.view = Triple(c.latitude, c.longitude, mapView.zoomLevelDouble)
            mapView.onPause(); mapView.onDetach()
        }
    }

    LaunchedEffect(layer) {
        prefs.layer = layer
        mapView.setTileSource(Tiles.sources.getValue(layer))
        mapView.maxZoomLevel = layer.maxZoom.toDouble()
        if (mapView.zoomLevelDouble > layer.maxZoom) mapView.controller.setZoom(layer.maxZoom.toDouble())
    }
    LaunchedEffect(online) { prefs.online = online; mapView.setUseDataConnection(online) }
    LaunchedEffect(Unit) { mapView.keepScreenOn = prefs.keepScreenOn }

    // La posición: azul con fix, gris con la última conocida. Se guarda al alejarse 200 m.
    LaunchedEffect(location) {
        val loc = location
        position.location = loc
        position.fallback = prefs.last
        if (loc != null && Gps.isFresh(loc)) {
            val last = prefs.last
            if (last == null || Geo.distanceM(last.first, last.second, loc.latitude, loc.longitude) > 200) prefs.last = loc.latitude to loc.longitude
            if (follow) mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        }
        mapView.invalidate()
    }
    // Repintar el marcador cuando la posición envejece (de azul a gris) aunque no llegue nada.
    LaunchedEffect(Unit) { while (true) { delay(5000); mapView.invalidate() } }
    LaunchedEffect(follow) {
        val loc = location
        if (follow && loc != null) {
            if (mapView.zoomLevelDouble < 14) mapView.controller.setZoom(15.0)
            mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        }
    }
    LaunchedEffect(rec.points) { recLine.setPoints(rec.points.map { GeoPoint(it.lat, it.lon) }); mapView.invalidate() }
    LaunchedEffect(opened) { openedLine.setPoints(opened?.points.orEmpty().map { GeoPoint(it.lat, it.lon) }); mapView.invalidate() }
    LaunchedEffect(fit) {
        val pts = opened?.points ?: return@LaunchedEffect
        if (pts.isEmpty()) return@LaunchedEffect
        follow = false
        // Tras el primer pintado: antes el MapView no sabe su tamaño y el encuadre sale mal.
        mapView.post {
            if (pts.size == 1) mapView.controller.setCenter(GeoPoint(pts[0].lat, pts[0].lon))
            else mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lon) }).increaseByScale(1.2f), false)
        }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Recording.start(context) }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        // Arriba: dónde estás y, si se graba o hay un track abierto, su resumen.
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChip(location)
            if (rec.active) RecordingChip(rec.lengthM, rec.startedElapsed)
            opened?.let { t ->
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(MapColors.opened))
                    Text(" ${t.name} · ${Geo.formatDistance(Geo.lengthM(t.points))}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    IconButton(onClick = { Tracks.close() }, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Cerrar el track", Modifier.size(18.dp)) }
                }
            }
        }

        // A la derecha: capa, online, seguir y grabar.
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallFloatingActionButton(onClick = { layer = layer.next() }) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Layers, "Cambiar de mapa"); Text(" ${layer.label}", fontSize = 13.sp)
                }
            }
            SmallFloatingActionButton(onClick = { online = !online }) {
                if (online) Text("Online", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
                else Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp)); Text(" Sin red", fontSize = 12.sp)
                }
            }
            SmallFloatingActionButton(onClick = { follow = true }) {
                Icon(if (follow) Icons.Filled.MyLocation else Icons.Filled.LocationSearching, "Seguir mi posición")
            }
            FloatingActionButton(onClick = {
                if (rec.active) Recording.stop(context)
                else if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else Recording.start(context)
            }, containerColor = if (rec.active) MapColors.recording else MaterialTheme.colorScheme.primaryContainer) {
                if (rec.active) Icon(Icons.Filled.Stop, "Parar la grabación")
                else Icon(Icons.Filled.FiberManualRecord, "Grabar track", tint = MapColors.recording)
            }
        }
    }
}

@Composable
private fun InfoChip(loc: Location?) {
    val text = when {
        !Gps.hasPermission() -> "Sin permiso de ubicación"
        !Gps.isProviderEnabled() -> "La ubicación está desactivada"
        loc == null || !Gps.isFresh(loc) -> "Buscando satélites…"
        else -> buildString {
            append(Geo.formatLatLon(loc.latitude, loc.longitude))
            if (loc.hasAltitude()) append("\n%.0f m".format(loc.altitude))
            if (loc.hasAccuracy()) append(if (loc.hasAltitude()) "  ·  ±%.0f m".format(loc.accuracy) else "\n±%.0f m".format(loc.accuracy))
            if (loc.hasSpeed() && loc.speed > 0.7f) append("  ·  %.1f km/h".format(loc.speed * 3.6f).replace('.', ','))
        }
    }
    Text(text, fontSize = 13.sp, lineHeight = 17.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)).padding(10.dp, 6.dp))
}

@Composable
private fun RecordingChip(lengthM: Double, started: Long) {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(MapColors.recording).padding(10.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(12.dp), tint = androidx.compose.ui.graphics.Color.White)
        Text("  ${Geo.formatDistance(lengthM)}  ·  ${Geo.formatDuration((now - started) / 1000)}", color = androidx.compose.ui.graphics.Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun trackLine(color: Int, width: Float) = Polyline().apply {
    outlinePaint.color = color
    outlinePaint.strokeWidth = width
    outlinePaint.strokeCap = Paint.Cap.ROUND
    outlinePaint.strokeJoin = Paint.Join.ROUND
    outlinePaint.isAntiAlias = true
    // Sin globo al tocarla.
    infoWindow = null
}

/** Tu posición: el círculo de precisión, el punto y, si te mueves, una flecha hacia donde vas. */
private class PositionOverlay(private val density: Float) : Overlay() {
    var location: Location? = null
    var fallback: Pair<Double, Double>? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = android.graphics.Point()
    private val pt2 = android.graphics.Point()
    private val arrow = Path()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = location
        val fresh = Gps.isFresh(loc)
        val (lat, lon) = when {
            loc != null -> loc.latitude to loc.longitude
            else -> fallback ?: return
        }
        val color = if (fresh) MapColors.position.toArgb() else MapColors.stale.toArgb()
        val proj = mapView.projection
        proj.toPixels(GeoPoint(lat, lon), pt)
        if (fresh && loc != null && loc.hasAccuracy()) {
            proj.toPixels(GeoPoint(lat + loc.accuracy / 111_320.0, lon), pt2)
            val r = (pt.y - pt2.y).toFloat()
            if (r > 12 * density) {
                halo.color = color; halo.alpha = 40
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, halo)
            }
        }
        if (fresh && loc != null && loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.7f) {
            arrow.reset()
            val s = 9 * density
            arrow.moveTo(0f, -s * 2.1f); arrow.lineTo(s * 0.9f, -s * 0.6f); arrow.lineTo(-s * 0.9f, -s * 0.6f); arrow.close()
            c.save(); c.translate(pt.x.toFloat(), pt.y.toFloat()); c.rotate(loc.bearing - mapView.mapOrientation)
            fill.color = color; c.drawPath(arrow, fill); c.restore()
        }
        fill.color = color
        ring.strokeWidth = 2.5f * density
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * density, fill)
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * density, ring)
    }
}


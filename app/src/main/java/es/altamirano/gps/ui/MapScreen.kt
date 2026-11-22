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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import es.altamirano.common.ui.glass
import es.altamirano.gps.core.MapLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.AnimatedVisibility
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
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
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
    var zoom by remember { mutableDoubleStateOf(prefs.view?.third ?: 6.0) }

    val location by Gps.location.collectAsState()
    val rec by Recording.state.collectAsState()
    val opened by Tracks.opened.collectAsState()
    val fit by Tracks.fitRequest.collectAsState()

    LifecycleStartEffect(Unit) {
        Gps.acquire("map")
        onStopOrDispose { Gps.release("map") }
    }

    val density = context.resources.displayMetrics.density
    val topInsetPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val bottomBar = LocalBottomBar.current
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
            // La escala arriba a la derecha: abajo la taparían la barra flotante y la de navegación.
            overlays += ScaleBarOverlay(this).apply {
                setAlignRight(true); setScaleBarOffset((16 * density).toInt(), (topInsetPx + 14 * density).toInt())
            }
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_MOVE) follow = false; false }
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?) = false
                override fun onZoom(event: ZoomEvent?): Boolean { zoom = zoomLevelDouble; return false }
            })
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
        zoom = mapView.zoomLevelDouble
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

        // Arriba: dónde estás y, si se graba o hay un track abierto, su resumen. En cristal.
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp).widthIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoPill(location)
            AnimatedVisibility(rec.active, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                RecordingPill(rec.lengthM, rec.startedElapsed)
            }
            opened?.let { t ->
                Row(Modifier.glass(RoundedCornerShape(50), tint = MapColors.opened, alpha = 0.85f).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Route, null, Modifier.size(16.dp), tint = Color.White)
                    Text("  ${t.name} · ${Geo.formatDistance(Geo.lengthM(t.points))}", fontSize = 13.sp, color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    IconButton(onClick = { Tracks.close() }, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Cerrar el track", Modifier.size(18.dp), tint = Color.White) }
                }
            }
        }

        // Abajo: la atribución del IGN (la pide la licencia) y la barra flotante de Expressive.
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBar + 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                    toolbarContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                    fabContainerColor = if (rec.active) MapColors.recording else MaterialTheme.colorScheme.tertiaryContainer,
                    fabContentColor = if (rec.active) Color.White else MapColors.recording,
                ),
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = {
                        if (rec.active) Recording.stop(context)
                        else if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else Recording.start(context)
                    }, containerColor = if (rec.active) MapColors.recording else MaterialTheme.colorScheme.tertiaryContainer) {
                        if (rec.active) Icon(Icons.Filled.Stop, "Parar la grabación", tint = Color.White)
                        else Icon(Icons.Filled.FiberManualRecord, "Grabar track", tint = MapColors.recording)
                    }
                },
            ) {
                TextButton(onClick = { layer = layer.next() }) {
                    Icon(layerIcon(layer), null, Modifier.size(20.dp)); Text(" ${layer.label}", fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { online = !online }) {
                    Icon(if (online) Icons.Filled.Cloud else Icons.Filled.CloudOff, if (online) "Usando la red" else "Sin red")
                }
                IconToggleButton(checked = follow, onCheckedChange = { follow = it }) {
                    Icon(if (follow) Icons.Filled.MyLocation else Icons.Filled.LocationSearching, "Seguir mi posición")
                }
                Text("z%d".format(zoom.toInt()) + if (zoom >= layer.maxZoom - 0.01) "+" else "", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
            Text(MapLayer.ATTRIBUTION, fontSize = 10.sp, color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

private fun layerIcon(l: MapLayer) = when (l) {
    MapLayer.TOPO -> Icons.Filled.Terrain
    MapLayer.ORTHO -> Icons.Filled.SatelliteAlt
    MapLayer.BASE -> Icons.Filled.Map
}

@Composable
private fun InfoPill(loc: Location?) {
    val fresh = Gps.isFresh(loc)
    val (title, detail) = when {
        !Gps.hasPermission() -> "Sin permiso de ubicación" to null
        !Gps.isProviderEnabled() -> "La ubicación está desactivada" to null
        loc == null || !fresh -> "Buscando satélites…" to null
        else -> Geo.formatLatLon(loc.latitude, loc.longitude) to buildList {
            if (loc.hasAltitude()) add("%.0f m".format(loc.altitude))
            if (loc.hasAccuracy()) add("±%.0f m".format(loc.accuracy))
            if (loc.hasSpeed() && loc.speed > 0.7f) add("%.1f km/h".format(loc.speed * 3.6f))
        }.joinToString("  ·  ")
    }
    Row(Modifier.glass().padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (fresh) MapColors.position else MaterialTheme.colorScheme.tertiary),
            contentAlignment = Alignment.Center) {
            if (fresh) Icon(Icons.Filled.GpsFixed, null, Modifier.size(18.dp), tint = Color.White)
            else LoadingIndicator(Modifier.size(26.dp), color = MaterialTheme.colorScheme.onTertiary)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            detail?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun RecordingPill(lengthM: Double, started: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
    // El punto late mientras se graba.
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(0.35f, 1f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
    Row(Modifier.glass(RoundedCornerShape(50), tint = MapColors.recording, alpha = 0.88f).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = pulse)))
        Text("  Grabando  ·  ${Geo.formatDistance(lengthM)}  ·  ${Geo.formatDuration((now - started) / 1000)}", color = Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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


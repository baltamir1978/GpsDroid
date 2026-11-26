package es.altamirano.gps.ui

import android.Manifest
import android.annotation.SuppressLint
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
import es.altamirano.gps.core.MapStyle
import es.altamirano.gps.core.MapTiles
import androidx.compose.material.icons.filled.Layers
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import es.altamirano.gps.core.Geo
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.altitudeMsl
import es.altamirano.gps.core.Altitude
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.data.Recording
import es.altamirano.gps.data.Tiles
import es.altamirano.gps.data.Tracks
import es.altamirano.gps.data.Following
import es.altamirano.gps.data.RoutePreview
import es.altamirano.gps.data.Waypoints
import es.altamirano.gps.core.Waypoint
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AltRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.pow

@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var style by remember { mutableStateOf(prefs.style) }
    var online by remember { mutableStateOf(prefs.online) }
    // Seguir la posición: se quita al mover el mapa con el dedo y se vuelve con el botón.
    var follow by remember { mutableStateOf(prefs.view == null) }
    var zoom by remember { mutableDoubleStateOf(prefs.view?.third ?: 6.0) }

    val location by Gps.location.collectAsState()
    val rec by Recording.state.collectAsState()
    val opened by Tracks.opened.collectAsState()
    val fit by Tracks.fitRequest.collectAsState()
    val following by Following.state.collectAsState()
    val preview by RoutePreview.state.collectAsState()
    val previewFit by RoutePreview.fitRequest.collectAsState()
    val followFit by Following.fitRequest.collectAsState()

    LifecycleStartEffect(Unit) {
        Gps.acquire("map")
        onStopOrDispose { Gps.release("map") }
    }

    val density = context.resources.displayMetrics.density
    // safeDrawing y no statusBars: en modo escritorio arriba está la barra de título de la ventana.
    val topInsetPx = WindowInsets.safeDrawing.getTop(LocalDensity.current).toFloat()
    val bottomBar = LocalBottomBar.current
    val scene = remember { MapScene(context, density) }
    val position = scene.position
    val points by Waypoints.list.collectAsState()
    val target by Waypoints.target.collectAsState()
    val focus by Waypoints.focus.collectAsState()
    // Guardar un punto: dónde (pulsación larga o tu posición) y, mientras, el diálogo del nombre.
    var newPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var newPointEle by remember { mutableStateOf<Double?>(null) }
    var selected by remember { mutableStateOf<Waypoint?>(null) }
    scene.points.onTap = { selected = it }
    scene.points.onLongPress = { newPoint = it; newPointEle = null }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(Tiles.sources.getValue(style.base))
            isTilesScaledToDpi = true
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            maxZoomLevel = style.maxZoom.toDouble()
            // España por defecto; si no, dónde se quedó o la última posición conocida.
            val v = prefs.view
            val last = prefs.last
            when {
                v != null -> { controller.setZoom(v.third); controller.setCenter(GeoPoint(v.first, v.second)) }
                last != null -> { controller.setZoom(15.0); controller.setCenter(GeoPoint(last.first, last.second)) }
                else -> { controller.setZoom(6.0); controller.setCenter(GeoPoint(40.2, -3.7)) }
            }
            scene.install(this)
            // La escala arriba a la derecha: abajo la taparían la barra flotante y la de navegación.
            overlays += ScaleBarOverlay(this).apply {
                setAlignRight(true); setScaleBarOffset((16 * density).toInt(), (topInsetPx + 14 * density).toInt())
            }
            val wheel = WheelZoom(this)
            setOnTouchListener { _, e ->
                // Tocar a medio zoom con la rueda: se aplica ya, para no arrastrar un mapa estirado.
                if (e.actionMasked == MotionEvent.ACTION_DOWN) wheel.commit()
                if (e.actionMasked == MotionEvent.ACTION_MOVE) follow = false
                false
            }
            // La rueda del ratón (modo escritorio) acerca y aleja; osmdroid no la atiende.
            setOnGenericMotionListener { v, e ->
                if (e.actionMasked != MotionEvent.ACTION_SCROLL) return@setOnGenericMotionListener false
                val ticks = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (ticks == 0f) return@setOnGenericMotionListener false
                // Siguiendo tu posición se acerca por el centro (sigues en medio); si no, por
                // donde está el cursor, como en un mapa de escritorio.
                if (follow) wheel.onWheel(ticks, v.width / 2f, v.height / 2f)
                else wheel.onWheel(ticks, e.x, e.y)
                true
            }
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
            mapView.onPause()
            scene.release(mapView)
        }
    }

    LaunchedEffect(style) {
        prefs.style = style
        scene.applyStyle(mapView, style)
        zoom = mapView.zoomLevelDouble
    }
    LaunchedEffect(online) { prefs.online = online; scene.setOnline(mapView, online) }
    LaunchedEffect(Unit) { mapView.keepScreenOn = prefs.keepScreenOn }

    // La posición: azul con fix, gris con la última conocida. Se guarda al alejarse 200 m.
    LaunchedEffect(location) {
        val loc = location
        position.location = loc
        position.fallback = prefs.last
        scene.points.from = loc?.takeIf { Gps.isFresh(it) }?.let { it.latitude to it.longitude }
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
    LaunchedEffect(rec.points) { scene.setTrack(scene.recording, rec.points); mapView.invalidate() }
    LaunchedEffect(opened) { scene.setTrack(scene.opened, opened?.points.orEmpty()); mapView.invalidate() }
    LaunchedEffect(following, location) {
        scene.setFollow(following, location)
        scene.radars.update(location, following)
        mapView.invalidate()
    }
    // Los radares del mapa, con los ajustes de ahora (cambian desde Offline o el coche).
    val radarSettings by es.altamirano.gps.data.Radars.settingsFlow(context).collectAsState()
    LaunchedEffect(radarSettings) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scene.setRadars(context) }
        mapView.invalidate()
    }
    // Todas las rutas posibles a la vez: la elegida, fuerte; las otras, claras y se eligen tocándolas.
    LaunchedEffect(preview?.options, preview?.selected) { scene.setPreview(mapView, preview) { RoutePreview.select(it) }; mapView.invalidate() }
    // La ruta en vista previa, entera (con tu posición, que es donde empieza).
    LaunchedEffect(previewFit) {
        val pts = preview?.current?.route?.points ?: return@LaunchedEffect
        if (previewFit == 0 || pts.size < 2) return@LaunchedEffect
        follow = false
        mapView.post {
            // Más margen abajo: ahí va la tarjeta con los datos y los botones.
            mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lon) }).increaseByScale(1.5f), false)
        }
    }
    // Al empezar a seguir: el track entero y tu posición, si estás cerca.
    LaunchedEffect(followFit) {
        val pts = following?.route?.points ?: return@LaunchedEffect
        if (followFit == 0 || pts.isEmpty()) return@LaunchedEffect
        follow = false
        mapView.post {
            val geo = pts.map { GeoPoint(it.lat, it.lon) }.toMutableList()
            location?.takeIf { Gps.isFresh(it) }?.let { l ->
                val g = GeoPoint(l.latitude, l.longitude)
                if (pts.minOf { Geo.distanceM(it.lat, it.lon, g.latitude, g.longitude) } < 5000) geo += g
            }
            mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.2f), false)
        }
    }
    LaunchedEffect(points, target) { scene.points.points = points; scene.points.target = target; mapView.invalidate() }
    // «Ver en el mapa» desde la lista de puntos.
    LaunchedEffect(focus) {
        val w = focus.point ?: return@LaunchedEffect
        follow = false
        mapView.post {
            if (mapView.zoomLevelDouble < 14) mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(GeoPoint(w.lat, w.lon))
        }
    }
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
    // Empezar la ruta de la vista previa: las indicaciones y avisos van también por notificación.
    val startRoute = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        RoutePreview.start(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // clipToBounds: Compose no recorta las vistas de Android a su hueco, y el MapView (más aún
    // estirado por la rueda, WheelZoom) pintaba las teselas encima del riel de navegación.
    Box(Modifier.fillMaxSize().clipToBounds()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        // Arriba: dónde estás y, si se graba o hay un track abierto, su resumen. En cristal.
        Column(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(12.dp).widthIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoPill(location)
            target?.let { t ->
                TargetPill(t, location, onShow = {
                    // Que se vean los dos: tú y el destino.
                    follow = false
                    val loc = location
                    if (loc != null && Gps.isFresh(loc)) mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(listOf(
                        GeoPoint(loc.latitude, loc.longitude), GeoPoint(t.lat, t.lon))).increaseByScale(1.4f), true)
                    else mapView.controller.animateTo(GeoPoint(t.lat, t.lon))
                }, onClose = { Waypoints.setTarget(null) })
            }
            following?.let { st ->
                FollowPill(st, onShow = {
                    follow = false
                    mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(st.route.points.map { GeoPoint(it.lat, it.lon) }).increaseByScale(1.2f), true)
                }, onClose = { Following.stop(context) })
            }
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
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomBar + 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preview?.let { p ->
                RoutePreviewCard(p, Modifier.padding(horizontal = 12.dp).widthIn(max = 520.dp),
                    onSelect = { RoutePreview.select(it) }, onCancel = { RoutePreview.cancel() },
                    onStart = {
                        if (Build.VERSION.SDK_INT >= 33) startRoute.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else RoutePreview.start(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    })
            }
            if (preview == null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                // Yendo por una ruta: el botón de las dos flechas (como Waze) para ver las otras rutas
                // desde donde estás y cambiar a otra.
                if (following?.nav != null) {
                    var searching by remember { mutableStateOf(false) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    androidx.compose.material3.FloatingActionButton(onClick = {
                        if (searching) return@FloatingActionButton
                        searching = true
                        scope.launch {
                            RoutePreview.fromNavigation(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            searching = false
                        }
                    }, containerColor = MapColors.follow, contentColor = Color.White) {
                        if (searching) LoadingIndicator(Modifier.size(28.dp), color = Color.White)
                        else Icon(Icons.Filled.AltRoute, "Otras rutas")
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                }
                SmallFloatingActionButton(onClick = {
                    val loc = location
                    if (loc == null || !Gps.isFresh(loc)) Toast.makeText(context, "Todavía no hay posición del GPS", Toast.LENGTH_SHORT).show()
                    else { newPoint = GeoPoint(loc.latitude, loc.longitude); newPointEle = loc.altitudeMsl() }
                }, containerColor = MapColors.waypoint, contentColor = Color.White) {
                    Icon(Icons.Filled.AddLocationAlt, "Guardar mi posición como punto")
                }
            }
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
                TextButton(onClick = { style = style.next() }) {
                    Icon(styleIcon(style), null, Modifier.size(20.dp)); Text(" ${style.label}", fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { online = !online }) {
                    Icon(if (online) Icons.Filled.Cloud else Icons.Filled.CloudOff, if (online) "Usando la red" else "Sin red")
                }
                IconToggleButton(checked = follow, onCheckedChange = { follow = it }) {
                    Icon(if (follow) Icons.Filled.MyLocation else Icons.Filled.LocationSearching, "Seguir mi posición")
                }
                Text("z%d".format(zoom.toInt()) + if (zoom >= style.maxZoom - 0.01) "+" else "", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
            Text(MapLayer.ATTRIBUTION, fontSize = 10.sp, color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }

    newPoint?.let { g ->
        NameDialog("Guardar punto", Waypoints.nextName(), Geo.formatLatLon(g.latitude, g.longitude), "Guardar",
            onDismiss = { newPoint = null }) { name ->
            Waypoints.add(name, g.latitude, g.longitude, newPointEle)
            newPoint = null
        }
    }
    selected?.let { w ->
        // Puede haber cambiado (renombrado) o desaparecido mientras está abierta.
        val current = points.firstOrNull { it.id == w.id }
        if (current == null) selected = null
        else WaypointSheet(current, location, isTarget = target?.id == current.id, onDismiss = { selected = null })
    }
}

/**
 * Zoom con la rueda, medio nivel por golpe (los touchpads mandan fracciones). Mientras se gira, el
 * mapa sólo se estira como una imagen alrededor del cursor, como osmdroid al pellizcar; al parar
 * [SETTLE_MS] se aplica el zoom de una vez, fijando el punto que había bajo el cursor. Aplicarlo
 * golpe a golpe pedía las teselas de cada nivel intermedio (la cola se llenaba de teselas que ya
 * no hacían falta y los rótulos se borraban y volvían): eso eran los saltos en giros largos.
 * Tampoco vale la animación de osmdroid: descarta lo que llega mientras dura.
 */
private class WheelZoom(private val map: MapView) {
    private var pending = 0.0
    private var px = 0f
    private var py = 0f
    private val settle = Runnable { commit() }

    fun onWheel(ticks: Float, x: Float, y: Float) {
        val z = map.zoomLevelDouble
        // El punto fijo es el del primer golpe: con la vista ya estirada, mover el pivote la
        // haría saltar (y las coordenadas del evento llegan ya transformadas por la escala).
        if (pending == 0.0) { px = x; py = y }
        pending = (z + pending + ticks * 0.5).coerceIn(map.minZoomLevel, map.maxZoomLevel) - z
        val s = 2.0.pow(pending).toFloat()
        map.pivotX = px; map.pivotY = py
        map.scaleX = s; map.scaleY = s
        map.removeCallbacks(settle)
        map.postDelayed(settle, SETTLE_MS)
    }

    fun commit() {
        map.removeCallbacks(settle)
        val d = pending
        pending = 0.0
        map.scaleX = 1f; map.scaleY = 1f
        if (d == 0.0) return
        val anchor = map.projection.fromPixels(px.toInt(), py.toInt())
        val center = map.mapCenter
        val (lat, lon) = MapTiles.zoomAnchoredCenter(center.latitude, center.longitude, anchor.latitude, anchor.longitude, d)
        map.controller.setZoom(map.zoomLevelDouble + d)
        map.controller.setCenter(GeoPoint(lat, lon))
    }

    private companion object { const val SETTLE_MS = 200L }
}

private fun styleIcon(s: MapStyle) = when (s) {
    MapStyle.TOPO -> Icons.Filled.Terrain
    MapStyle.ORTHO -> Icons.Filled.SatelliteAlt
    MapStyle.HYBRID -> Icons.Filled.Layers
    MapStyle.BASE -> Icons.Filled.Map
}

@Composable
private fun InfoPill(loc: Location?) {
    val fresh = Gps.isFresh(loc)
    val (title, detail) = when {
        !Gps.hasPermission() -> "Sin permiso de ubicación" to null
        !Gps.isProviderEnabled() -> "La ubicación está desactivada" to null
        loc == null || !fresh -> "Buscando satélites…" to null
        else -> Geo.formatLatLon(loc.latitude, loc.longitude) to buildList {
            loc.altitudeMsl()?.let { add(Altitude.format(it)) }
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

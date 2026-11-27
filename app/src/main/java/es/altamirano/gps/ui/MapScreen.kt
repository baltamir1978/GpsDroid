package es.altamirano.gps.ui

import android.Manifest
import android.location.Location
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.material.icons.filled.Search
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
import kotlin.math.pow

import es.altamirano.gps.map.GpsLayers
import es.altamirano.gps.map.MapStyles
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * El mapa del móvil, con MapLibre (desde la 0.27.0; antes osmdroid): el callejero vectorial del IGN,
 * el topográfico, el satélite y el híbrido (ortofoto con los nombres del vectorial). Lo de la app
 * (tracks, ruta, puntos, radares, tu posición) son capas de [GpsLayers], las mismas que en el coche.
 * Gestos de MapLibre (arrastrar, pellizcar, rueda del ratón) sin girar ni inclinar: norte arriba.
 *
 * Zoom: lo que se enseña y se guarda (`z15`…) es el de siempre (teselas de 256); MapLibre cuenta uno menos.
 */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var style by remember { mutableStateOf(prefs.style) }
    var online by remember { mutableStateOf(prefs.online) }
    // Seguir la posición: se quita al mover el mapa con el dedo y se vuelve con el botón.
    var follow by remember { mutableStateOf(prefs.view == null) }
    var zoom by remember { mutableDoubleStateOf(prefs.view?.third ?: 6.0) }
    // La escala: metros que ocupa la barra y su ancho en dp.
    var scale by remember { mutableStateOf<Pair<Double, Float>?>(null) }

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
    val bottomBar = LocalBottomBar.current
    val points by Waypoints.list.collectAsState()
    val target by Waypoints.target.collectAsState()
    val focus by Waypoints.focus.collectAsState()
    // Guardar un punto: dónde (pulsación larga o tu posición) y, mientras, el diálogo del nombre.
    var newPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var newPointEle by remember { mutableStateOf<Double?>(null) }
    var selected by remember { mutableStateOf<Waypoint?>(null) }

    val layers = remember { GpsLayers(context, density, car = false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember {
        Tiles.ensureMapLibre(context)
        // España por defecto; si no, dónde se quedó o la última posición conocida.
        val v = prefs.view
        val last = prefs.last
        val (center, z) = when {
            v != null -> LatLng(v.first, v.second) to v.third
            last != null -> LatLng(last.first, last.second) to 15.0
            else -> LatLng(40.2, -3.7) to 6.0
        }
        val opts = MapLibreMapOptions.createFromAttributes(context)
            .compassEnabled(false).logoEnabled(false).attributionEnabled(false)
            .rotateGesturesEnabled(false).tiltGesturesEnabled(false)
            .camera(CameraPosition.Builder().target(center).zoom(z - 1).build())
        MapView(context, opts).apply { onCreate(null) }
    }
    // El ciclo de vida del MapView, con el de la pantalla.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            map?.cameraPosition?.target?.let { c -> prefs.view = Triple(c.latitude, c.longitude, (map?.cameraPosition?.zoom ?: 5.0) + 1) }
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    /** La escala: una distancia redonda que quepa en unos 90 dp. */
    fun updateScale(m: MapLibreMap) {
        val lat = m.cameraPosition.target?.latitude ?: return
        val perDp = m.projection.getMetersPerPixelAtLatitude(lat)
        if (perDp <= 0) return
        val max = perDp * 90
        val nice = listOf(1.0, 2.0, 5.0).flatMap { b -> (0..7).map { b * 10.0.pow(it) } }.filter { it <= max }.maxOrNull() ?: return
        scale = nice to (nice / perDp).toFloat()
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setMinZoomPreference(4.0)
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false }
            m.addOnCameraMoveListener { zoom = m.cameraPosition.zoom + 1; updateScale(m) }
            m.addOnCameraIdleListener { zoom = m.cameraPosition.zoom + 1; updateScale(m) }
            m.addOnMapLongClickListener { ll -> newPoint = ll.latitude to ll.longitude; newPointEle = null; true }
            m.addOnMapClickListener { ll ->
                // Tocar un punto abre su ficha; tocar una ruta clara de la vista previa la elige.
                val p = m.projection.toScreenLocation(ll)
                val r = 18 * density
                val box = android.graphics.RectF(p.x - r, p.y - r, p.x + r, p.y + r)
                val id = m.queryRenderedFeatures(box, GpsLayers.POINTS_LAYER, GpsLayers.POINTS_TEXT_LAYER)
                    .firstNotNullOfOrNull { f -> f.getNumberProperty("id")?.toLong() }
                if (id != null) { selected = Waypoints.list.value.firstOrNull { it.id == id }; return@addOnMapClickListener true }
                val alt = m.queryRenderedFeatures(box, GpsLayers.ALT_LAYER).firstNotNullOfOrNull { f -> f.getNumberProperty("i")?.toInt() }
                if (alt != null) { RoutePreview.select(alt); return@addOnMapClickListener true }
                false
            }
            map = m
            updateScale(m)
        }
    }

    LaunchedEffect(style, map) {
        val m = map ?: return@LaunchedEffect
        prefs.style = style
        m.setMaxZoomPreference(style.viewMaxZoom - 1.0)
        MapStyles.apply(m, style) { st -> layers.install(st) }
    }
    LaunchedEffect(online) { prefs.online = online; Tiles.setOnline(online) }
    LaunchedEffect(Unit) { mapView.keepScreenOn = prefs.keepScreenOn }

    /** Mover el mapa a un punto (con [z] en la escala de siempre, o el zoom de ahora). */
    fun moveTo(lat: Double, lon: Double, z: Double? = null) {
        val m = map ?: return
        m.easeCamera(if (z == null) CameraUpdateFactory.newLatLng(LatLng(lat, lon)) else CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z - 1), 500)
    }

    /** Encuadrar unos puntos (más margen abajo si [bottomExtra], por la tarjeta de la vista previa). */
    fun fitTo(pts: List<Pair<Double, Double>>, bottomExtra: Boolean = false) {
        val m = map ?: return
        if (pts.isEmpty()) return
        if (pts.size == 1 || pts.distinct().size == 1) { moveTo(pts[0].first, pts[0].second); return }
        val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.first, it.second)) } }.build()
        val pad = (48 * density).toInt()
        val bottom = pad + ((if (bottomExtra) 260 else 120) * density).toInt()
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, pad, (140 * density).toInt(), pad, bottom), 500)
    }

    // La posición: azul con fix, gris con la última conocida. Se guarda al alejarse 200 m.
    LaunchedEffect(location, map) {
        val loc = location
        layers.setPosition(loc, prefs.last)
        if (loc != null && Gps.isFresh(loc)) {
            val last = prefs.last
            if (last == null || Geo.distanceM(last.first, last.second, loc.latitude, loc.longitude) > 200) prefs.last = loc.latitude to loc.longitude
            if (follow) moveTo(loc.latitude, loc.longitude)
        }
    }
    // Repintar el marcador cuando la posición envejece (de azul a gris) aunque no llegue nada.
    LaunchedEffect(Unit) { while (true) { delay(5000); layers.setPosition(Gps.location.value, prefs.last) } }
    LaunchedEffect(follow, map) {
        val loc = location
        val m = map
        if (!follow || loc == null || m == null) return@LaunchedEffect
        // Muy alejado: a z15 de golpe. Con animación, la posición siguiente (cada segundo, con su propia
        // animación) la cortaba y el mapa se quedaba lejos (Bruno, 27-nov-2026).
        if (zoom < 14) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0 - 1))
        else moveTo(loc.latitude, loc.longitude)
    }
    LaunchedEffect(rec.points) { layers.setRecording(rec.points) }
    LaunchedEffect(opened) { layers.setOpened(opened?.points.orEmpty()) }
    LaunchedEffect(following, location) { layers.setFollow(following, location) }
    // Los radares del mapa, con los ajustes de ahora (cambian desde Offline o el coche).
    val radarSettings by es.altamirano.gps.data.Radars.settingsFlow(context).collectAsState()
    LaunchedEffect(radarSettings) { layers.reloadRadars() }
    // Todas las rutas posibles a la vez: la elegida, fuerte; las otras, claras y se eligen tocándolas.
    LaunchedEffect(preview?.options, preview?.selected) { layers.setPreview(preview) }
    // La ruta en vista previa, entera (con tu posición, que es donde empieza).
    // Se lee el valor de ahora y no el recogido: el aviso de encuadrar puede llegar a la pantalla antes que
    // la ruta (con osmdroid funcionaba de rebote porque encuadraba un instante después, con `post`).
    LaunchedEffect(previewFit, map) {
        val pts = RoutePreview.state.value?.current?.route?.points ?: return@LaunchedEffect
        if (previewFit == 0 || pts.size < 2 || map == null) return@LaunchedEffect
        follow = false
        fitTo(pts.map { it.lat to it.lon }, bottomExtra = true)
    }
    // Al empezar a seguir: el track entero y tu posición, si estás cerca.
    LaunchedEffect(followFit, map) {
        val pts = Following.state.value?.route?.points ?: return@LaunchedEffect
        if (followFit == 0 || pts.isEmpty() || map == null) return@LaunchedEffect
        follow = false
        val geo = pts.map { it.lat to it.lon }.toMutableList()
        location?.takeIf { Gps.isFresh(it) }?.let { l ->
            if (pts.minOf { Geo.distanceM(it.lat, it.lon, l.latitude, l.longitude) } < 5000) geo += l.latitude to l.longitude
        }
        fitTo(geo)
    }
    LaunchedEffect(points, target) { layers.setPoints(points, target) }
    // «Ver en el mapa» desde la lista de puntos.
    LaunchedEffect(focus, map) {
        val w = Waypoints.focus.value.point ?: return@LaunchedEffect
        if (map == null) return@LaunchedEffect
        follow = false
        moveTo(w.lat, w.lon, maxOf(zoom, 15.0))
    }
    LaunchedEffect(fit, map) {
        val pts = Tracks.opened.value?.points ?: return@LaunchedEffect
        if (pts.isEmpty() || map == null) return@LaunchedEffect
        follow = false
        fitTo(pts.map { it.lat to it.lon })
    }

    var searchOpen by remember { mutableStateOf(false) }
    var foundPlace by remember { mutableStateOf<es.altamirano.gps.core.Place?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Recording.start(context) }
    // Empezar la ruta de la vista previa: las indicaciones y avisos van también por notificación.
    val startRoute = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        RoutePreview.start(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // clipToBounds: Compose no recorta las vistas de Android a su hueco (el mapa pintaba encima del riel).
    Box(Modifier.fillMaxSize().clipToBounds()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        // La escala, arriba a la derecha: abajo la taparían la barra flotante y la de navegación.
        scale?.let { (m, w) ->
            Column(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 14.dp, end = 16.dp),
                horizontalAlignment = Alignment.End) {
                Text(if (m >= 1000) "${(m / 1000).toInt()} km" else "${m.toInt()} m", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.Black, modifier = Modifier.background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                Box(Modifier.padding(top = 2.dp).size(width = w.dp, height = 4.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
            }
        }

        // Arriba: dónde estás y, si se graba o hay un track abierto, su resumen. En cristal.
        Column(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(12.dp).widthIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoPill(location)
            target?.let { t ->
                TargetPill(t, location, onShow = {
                    // Que se vean los dos: tú y el destino.
                    follow = false
                    val loc = location
                    if (loc != null && Gps.isFresh(loc)) fitTo(listOf(loc.latitude to loc.longitude, t.lat to t.lon))
                    else moveTo(t.lat, t.lon)
                }, onClose = { Waypoints.setTarget(null) })
            }
            following?.let { st ->
                FollowPill(st, onShow = {
                    follow = false
                    fitTo(st.route.points.map { it.lat to it.lon })
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
                // La lupa: buscar un sitio por nombre o dirección y llevar allí la ruta.
                SmallFloatingActionButton(onClick = { searchOpen = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Icon(Icons.Filled.Search, "Buscar un sitio")
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                SmallFloatingActionButton(onClick = {
                    val loc = location
                    if (loc == null || !Gps.isFresh(loc)) Toast.makeText(context, "Todavía no hay posición del GPS", Toast.LENGTH_SHORT).show()
                    else { newPoint = loc.latitude to loc.longitude; newPointEle = loc.altitudeMsl() }
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

    newPoint?.let { (lat, lon) ->
        NameDialog("Guardar punto", Waypoints.nextName(), Geo.formatLatLon(lat, lon), "Guardar",
            onDismiss = { newPoint = null }) { name ->
            Waypoints.add(name, lat, lon, newPointEle)
            newPoint = null
        }
    }
    if (searchOpen) PlaceSearchSheet(location, onDismiss = { searchOpen = false }) { p -> searchOpen = false; foundPlace = p }
    foundPlace?.let { p ->
        PlaceSheet(p, location, onDismiss = { foundPlace = null }, onShowOnMap = {
            follow = false
            moveTo(p.lat, p.lon, maxOf(zoom, 15.0))
            foundPlace = null
        })
    }
    selected?.let { w ->
        // Puede haber cambiado (renombrado) o desaparecido mientras está abierta.
        val current = points.firstOrNull { it.id == w.id }
        if (current == null) selected = null
        else WaypointSheet(current, location, isTarget = target?.id == current.id, onDismiss = { selected = null })
    }
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

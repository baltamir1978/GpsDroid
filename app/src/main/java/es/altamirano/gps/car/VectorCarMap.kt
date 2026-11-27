package es.altamirano.gps.car

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.car.app.SurfaceContainer
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import es.altamirano.gps.core.FollowStatus
import es.altamirano.gps.core.Radar
import es.altamirano.gps.core.RadarKind
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.data.RoutePreviewState
import es.altamirano.gps.ui.MapColors
import es.altamirano.gps.ui.RadarsOverlay
import es.altamirano.gps.ui.visibleRadars
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as P
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.ln
import kotlin.math.round

/**
 * El mapa del coche con el **Mapa Base vectorial del IGN** (MapLibre): el mapa se dibuja en el móvil,
 * así que al girar con la dirección de la marcha los nombres de las calles siguen derechos y nítidos
 * (con las teselas raster giraban con el mapa y quedaban del revés; Bruno, 27-nov-2026).
 *
 * Como [OsmCarMap]: pantalla virtual sobre la `Surface` del coche y un `Presentation` con el mapa;
 * encima, una vista ([Hud]) con la velocidad, la tarjeta de la maniobra y la atribución. La ruta, el
 * track, los puntos, los radares y la posición van como capas GeoJSON del propio mapa.
 *
 * Zoom: MapLibre cuenta con teselas de 512, osmdroid (y [Prefs.carZoom]) con 256 ampliadas: aquí es
 * uno menos para la misma escala.
 */
class VectorCarMap(private val ctx: Context, private val dark: Boolean) : CarMap {
    private val prefs = Prefs(ctx)
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var hud: Hud? = null
    private var size = 0 to 0
    private var visible: Rect? = null
    private var density = 1f

    private var location: Location? = null
    private var points: List<Waypoint> = emptyList()
    private var target: Waypoint? = null
    private var track: List<TrackPoint> = emptyList()
    private var following: FollowState? = null
    private var preview: RoutePreviewState? = null
    private var panel: ManeuverOverlay.Panel? = null

    /** Para las reglas de cuándo se ven los radares y para dibujar sus iconos (con la densidad del coche). */
    private var radarPainter = RadarsOverlay(1f)
    private var radars: List<Radar> = emptyList()
    private var radarsShown: Boolean? = null

    private var zoom = prefs.carZoom - 1
    private var bearing = 0.0

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val resumeFollow = Runnable { follow = true }

    override var follow = true
        set(v) {
            val changed = field != v; field = v
            main.removeCallbacks(resumeFollow)
            if (v) recenter(animate = false) else main.postDelayed(resumeFollow, RESUME_FOLLOW_MS)
            if (changed) onFollowChanged?.invoke()
        }
    override var onFollowChanged: (() -> Unit)? = null

    override fun userMovedMap() { follow = false; main.removeCallbacks(resumeFollow); main.postDelayed(resumeFollow, RESUME_FOLLOW_MS) }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        size = container.width to container.height
        density = container.dpi / 160f
        radarPainter = RadarsOverlay(density)
        val vd = ctx.getSystemService(DisplayManager::class.java).createVirtualDisplay("IGN GPS", container.width, container.height,
            container.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = vd
        val p = Presentation(ctx, vd.display)
        MapLibre.getInstance(ctx.applicationContext)
        setUpCache(ctx.applicationContext)
        // Sin red (el interruptor de la app): sólo lo que ya está en la caché de MapLibre.
        MapLibre.setConnected(if (prefs.online) null else false)
        val start = location?.let { LatLng(it.latitude, it.longitude) }
            ?: prefs.last?.let { LatLng(it.first, it.second) } ?: LatLng(40.2, -3.7)
        val opts = MapLibreMapOptions.createFromAttributes(p.context)
            // TextureView: se compone como una vista más dentro del Presentation, debajo del Hud.
            .textureMode(true)
            .compassEnabled(false).logoEnabled(false).attributionEnabled(false)
            .camera(CameraPosition.Builder().target(start).zoom(zoom).build())
        val mv = MapView(p.context, opts)
        mv.onCreate(null)
        val h = Hud(p.context, density).also { it.area = visible; it.maneuver.panel = panel }
        val frame = FrameLayout(p.context)
        frame.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(h, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.setContentView(frame)
        p.show()
        mv.onStart(); mv.onResume()
        presentation = p; mapView = mv; hud = h
        mv.getMapAsync { m ->
            if (mapView !== mv) return@getMapAsync
            map = m
            // Los gestos los manda el coche (onScroll/onScale), no la pantalla virtual.
            m.uiSettings.setAllGesturesEnabled(false)
            m.setMinZoomPreference(MIN_ZOOM)
            m.setMaxZoomPreference(MAX_ZOOM)
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { st ->
                if (map !== m) return@setStyle
                style = st
                installLayers(st)
                radarsChanged()
                lastRouteKey = null; lastPointsKey = null; lastPreviewKey = null
                redraw()
                showPreview(preview, refit = true)
                recenter(animate = false)
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = visibleArea
        hud?.area = visibleArea
        hud?.invalidate()
        recenter(animate = false)
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val m = map ?: return
        userMovedMap()
        m.scrollBy(-distanceX, -distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Doble toque: el coche manda un factor fijo (2); pellizco: el cambio de escala, a niveles de zoom.
        setZoom((map?.cameraPosition?.zoom ?: zoom) + ln(scaleFactor.toDouble()) / ln(2.0))
    }

    override fun zoomBy(delta: Double) = setZoom(round((map?.cameraPosition?.zoom ?: zoom) + delta))

    private fun setZoom(z: Double) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        prefs.carZoom = zoom + 1
        val m = map ?: return
        if (follow) recenter(animate = true) else m.easeCamera(CameraUpdateFactory.zoomTo(zoom), 250)
    }

    // ---- Capas ----

    private fun source(id: String) = style?.getSourceAs<GeoJsonSource>(id)

    private fun installLayers(st: Style) {
        listOf(DIM, ALT, PREVIEW, RECORDING, DONE, AHEAD, GAP, TARGET, STRETCH, POINTS, RADARS, POSITION)
            .forEach { st.addSource(GeoJsonSource(it)) }
        val round = arrayOf(P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND))
        fun line(id: String, src: String, color: Int, width: Float, vararg extra: org.maplibre.android.style.layers.PropertyValue<*>): Layer =
            LineLayer(id, src).withProperties(P.lineColor(color), P.lineWidth(width), *round, *extra)
        val white = android.graphics.Color.WHITE
        val dash = P.lineDasharray(arrayOf(2f, 1.6f))
        if (dark) {
            // De noche, un velo sobre el mapa (no sobre la ruta): un mapa claro deslumbra.
            source(DIM)?.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(listOf(
                Point.fromLngLat(-180.0, -85.0), Point.fromLngLat(180.0, -85.0), Point.fromLngLat(180.0, 85.0),
                Point.fromLngLat(-180.0, 85.0), Point.fromLngLat(-180.0, -85.0))))))
            st.addLayer(FillLayer("$DIM-l", DIM).withProperties(P.fillColor(android.graphics.Color.BLACK), P.fillOpacity(0.4f)))
        }
        // Grosores en dp, como en osmdroid con el factor del coche (1,7).
        st.addLayer(line("$ALT-l", ALT, MapColors.followDone.toArgb(), 10f))
        st.addLayer(line("$PREVIEW-c", PREVIEW, white, 16f))
        st.addLayer(line("$PREVIEW-l", PREVIEW, MapColors.follow.toArgb(), 12f))
        st.addLayer(line("$RECORDING-l", RECORDING, MapColors.recording.toArgb(), 6f))
        st.addLayer(line("$DONE-l", DONE, MapColors.followDone.toArgb(), 10f))
        st.addLayer(line("$AHEAD-c", AHEAD, white, 14f))
        st.addLayer(line("$AHEAD-l", AHEAD, MapColors.follow.toArgb(), 10f))
        st.addLayer(line("$GAP-l", GAP, MapColors.offTrack.toArgb(), 4f, dash))
        st.addLayer(line("$TARGET-l", TARGET, MapColors.target.toArgb(), 4f, dash))
        st.addLayer(LineLayer("$STRETCH-l", STRETCH).withProperties(P.lineColor(Expression.toColor(Expression.get("color"))),
            P.lineWidth(4f), dash, *round).apply { minZoom = RADAR_MIN_ZOOM })
        st.addLayer(CircleLayer("$POINTS-c", POINTS).withProperties(
            P.circleColor(Expression.toColor(Expression.get("color"))), P.circleRadius(Expression.get("r")),
            P.circleStrokeColor(white), P.circleStrokeWidth(2.5f)))
        st.addLayer(SymbolLayer("$POINTS-t", POINTS).withProperties(
            P.textField(Expression.get("name")), P.textFont(arrayOf("Noto Sans Bold")), P.textSize(14f),
            P.textColor(Expression.toColor(Expression.get("color"))), P.textHaloColor(white), P.textHaloWidth(2f),
            P.textAnchor(Property.TEXT_ANCHOR_TOP), P.textOffset(arrayOf(0f, 1.1f)), P.textOptional(true)))
        st.addLayer(SymbolLayer("$RADARS-l", RADARS).withProperties(
            P.iconImage(Expression.get("icon")), P.iconAllowOverlap(true), P.iconIgnorePlacement(true),
            // Derechos aunque el mapa gire, como las señales de verdad.
            P.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT)).apply { minZoom = RADAR_MIN_ZOOM })
        st.addImage(ICON_ARROW, positionIcon(arrow = true, color = MapColors.position.toArgb()))
        st.addImage(ICON_DOT, positionIcon(arrow = false, color = MapColors.position.toArgb()))
        st.addImage(ICON_STALE, positionIcon(arrow = false, color = MapColors.stale.toArgb()))
        st.addLayer(SymbolLayer("$POSITION-l", POSITION).withProperties(
            P.iconImage(Expression.get("icon")), P.iconRotate(Expression.get("bearing")),
            // La flecha apunta al rumbo respecto al norte: gira con el mapa.
            P.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            P.iconAllowOverlap(true), P.iconIgnorePlacement(true)))
    }

    /** Un mapa de bits pintado a la densidad del coche y marcado con ella (MapLibre lo toma en dp). */
    private fun bitmap(sizeDp: Float, paint: (Canvas, Float) -> Unit): Bitmap {
        val px = (sizeDp * density).toInt().coerceAtLeast(1)
        val b = createBitmap(px, px)
        b.density = (density * 160).toInt()
        paint(Canvas(b), px / 2f)
        return b
    }

    /** Tu posición: en marcha, la flecha de los navegadores; parado, el punto (gris si es una posición vieja). */
    private fun positionIcon(arrow: Boolean, color: Int): Bitmap = bitmap(if (arrow) 52f else 30f) { c, mid ->
        val d = density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.color = android.graphics.Color.WHITE; strokeWidth = 3.5f * d; strokeJoin = Paint.Join.ROUND
        }
        if (arrow) {
            val s = 15 * d
            val path = Path().apply {
                moveTo(mid, mid - s * 1.5f); lineTo(mid + s, mid + s); lineTo(mid, mid + s * 0.45f); lineTo(mid - s, mid + s); close()
            }
            c.drawPath(path, fill); c.drawPath(path, ring)
        } else {
            c.drawCircle(mid, mid, 11 * d, fill); c.drawCircle(mid, mid, 11 * d, ring)
        }
    }

    private fun radarIconName(rd: Radar): String = when {
        rd.kind == RadarKind.RED_LIGHT -> "radar-semaforo"
        else -> "radar-${if (radarPainter.colorOf(rd) == 0xFFEF6C00.toInt()) "movil" else "fijo"}-${rd.maxspeed ?: "camara"}"
    }

    // ---- Datos ----

    override fun radarsChanged() {
        radars = visibleRadars(ctx)
        val st = style ?: return
        val icons = HashSet<String>()
        val features = ArrayList<Feature>()
        val stretches = ArrayList<Feature>()
        for (rd in radars) {
            if (rd.isStretch && rd.endLat != null && rd.endLon != null) {
                stretches += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(rd.lon, rd.lat), Point.fromLngLat(rd.endLon, rd.endLat))))
                    .apply { addStringProperty("color", String.format("#%06X", radarPainter.colorOf(rd) and 0xFFFFFF)) }
            }
            // Los tramos de radar móvil son sólo la línea (son muchos y casi nunca hay radar).
            if (rd.kind == RadarKind.MOBILE_STRETCH) continue
            val name = radarIconName(rd)
            if (icons.add(name) && st.getImage(name) == null) st.addImage(name, bitmap(30f) { c, mid -> radarPainter.paintRadar(c, mid, mid, rd) })
            features += Feature.fromGeometry(Point.fromLngLat(rd.lon, rd.lat)).apply { addStringProperty("icon", name) }
        }
        source(RADARS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        source(STRETCH)?.setGeoJson(FeatureCollection.fromFeatures(stretches))
        radarsShown = null
        redraw()
    }

    override fun update(location: Location?, points: List<Waypoint>, target: Waypoint?, track: List<TrackPoint>, following: FollowState?) {
        this.location = location; this.points = points; this.target = target; this.track = track; this.following = following
        location?.takeIf { Gps.isFresh(it) }?.let { loc ->
            val last = prefs.last
            if (last == null || es.altamirano.gps.core.Geo.distanceM(last.first, last.second, loc.latitude, loc.longitude) > 50)
                prefs.last = loc.latitude to loc.longitude
        }
        redraw()
        if (follow) recenter(animate = true)
    }

    private var lastRouteKey: Any? = null
    private var lastPointsKey: Any? = null
    private var lastTrackSize = -1

    private fun redraw() {
        val st = style ?: return
        val loc = location
        val fresh = loc?.takeIf { Gps.isFresh(it) }

        // Posición: la de ahora, o la última (en gris).
        val at = loc?.let { it.latitude to it.longitude } ?: prefs.last
        source(POSITION)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(at?.let { (lat, lon) ->
            val moving = fresh != null && fresh.hasBearing() && fresh.hasSpeed() && fresh.speed > 0.7f
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("icon", when { moving -> ICON_ARROW; fresh != null -> ICON_DOT; else -> ICON_STALE })
                addNumberProperty("bearing", if (moving) fresh!!.bearing else 0f)
            }
        })))

        // La ruta o el track que se sigue, partido por donde vas (sólo se rehace al pasar de tramo).
        val fol = following
        val route = fol?.route
        val fix = fol?.fix
        val split = if (fix == null || fix.status == FollowStatus.APPROACHING) 0 else fix.match.segment + 1
        val key = route?.let { System.identityHashCode(it) to split }
        if (key != lastRouteKey) {
            lastRouteKey = key
            if (route == null) {
                source(DONE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                source(AHEAD)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            } else {
                val all = route.points.map { Point.fromLngLat(it.lon, it.lat) }
                val here = fix?.takeIf { split > 0 }?.let { Point.fromLngLat(it.match.lon, it.match.lat) }
                source(DONE)?.setGeoJson(lineOf(if (here == null) emptyList() else all.take(split) + here))
                source(AHEAD)?.setGeoJson(lineOf(if (here == null) all else listOf(here) + all.drop(split)))
            }
        }
        val away = fix != null && fresh != null && fix.status in setOf(FollowStatus.OFF_TRACK, FollowStatus.APPROACHING)
        source(GAP)?.setGeoJson(lineOf(if (away) listOf(Point.fromLngLat(fresh!!.longitude, fresh.latitude), Point.fromLngLat(fix!!.match.lon, fix.match.lat)) else emptyList()))

        // El track que se graba.
        if (track.size != lastTrackSize) {
            lastTrackSize = track.size
            source(RECORDING)?.setGeoJson(lineOf(track.map { Point.fromLngLat(it.lon, it.lat) }))
        }

        // Los puntos (el destino en otro color) y la línea recta hasta el destino.
        val pKey = points to target?.id
        if (pKey != lastPointsKey) {
            lastPointsKey = pKey
            source(POINTS)?.setGeoJson(FeatureCollection.fromFeatures(points.map { w ->
                val isTarget = w.id == target?.id
                Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat)).apply {
                    addStringProperty("name", w.name)
                    addStringProperty("color", String.format("#%06X", (if (isTarget) MapColors.target else MapColors.waypoint).toArgb() and 0xFFFFFF))
                    addNumberProperty("r", if (isTarget) 10 else 8)
                }
            }))
        }
        val t = target
        source(TARGET)?.setGeoJson(lineOf(if (t != null && fresh != null) listOf(Point.fromLngLat(fresh.longitude, fresh.latitude), Point.fromLngLat(t.lon, t.lat)) else emptyList()))

        // Radares: sólo yendo en coche (las mismas reglas que en osmdroid).
        radarPainter.update(loc, fol)
        val show = radarPainter.visible && radars.isNotEmpty()
        if (show != radarsShown) {
            radarsShown = show
            val v = P.visibility(if (show) Property.VISIBLE else Property.NONE)
            st.getLayer("$RADARS-l")?.setProperties(v)
            st.getLayer("$STRETCH-l")?.setProperties(v)
        }

        // Velocidad (y el límite del radar que viene).
        hud?.let { h ->
            val s = loc?.takeIf { Gps.isFresh(it) && it.hasSpeed() }
            h.speed.speedKmh = s?.let { kotlin.math.round(it.speed * 3.6f).toInt() }
            h.speed.limitKmh = fol?.radar?.maxspeed
            h.invalidate()
        }
    }

    private fun lineOf(pts: List<Point>): FeatureCollection =
        FeatureCollection.fromFeatures(if (pts.size < 2) emptyList() else listOf(Feature.fromGeometry(LineString.fromLngLats(pts))))

    private var lastPreviewKey: Any? = null

    override fun showPreview(st: RoutePreviewState?) = showPreview(st, refit = false)

    /**
     * Las rutas posibles: la elegida en azul con borde y las demás claras debajo. Al aparecer (o al
     * cambiar de elegida) se encuadra y se deja de seguir; al acabar, se sigue otra vez.
     */
    private fun showPreview(st: RoutePreviewState?, refit: Boolean) {
        val was = preview
        preview = st
        if (style != null) {
            val selected = st?.current?.route?.points.orEmpty()
            source(PREVIEW)?.setGeoJson(lineOf(selected.map { Point.fromLngLat(it.lon, it.lat) }))
            source(ALT)?.setGeoJson(FeatureCollection.fromFeatures(st?.options.orEmpty().filterIndexed { i, _ -> i != st?.selected }
                .mapNotNull { o -> o.route.points.takeIf { it.size > 1 }?.let { pts -> Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })) } }))
            val m = map
            if (m != null && selected.size > 1 && (refit || was == null || was.selected != st?.selected)) {
                main.removeCallbacks(resumeFollow)
                if (follow) { follow = false; main.removeCallbacks(resumeFollow) }
                val b = LatLngBounds.Builder().apply { selected.forEach { include(LatLng(it.lat, it.lon)) } }.build()
                val r = visible ?: Rect(0, 0, size.first, size.second)
                val pad = (40 * density).toInt()
                bearing = 0.0
                m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, 0.0, 0.0, r.left + pad, r.top + pad, size.first - r.right + pad, size.second - r.bottom + pad), 600)
            }
        }
        if (st == null && was != null) follow = true
    }

    // ---- Cámara ----

    private fun recenter(animate: Boolean) {
        val m = map ?: return
        if (!follow) return
        val loc = location?.takeIf { Gps.isFresh(it) } ?: return
        // La dirección de la marcha hacia arriba: sólo en marcha (parado, el rumbo del GPS baila),
        // girando a medias hacia el nuevo para que no dé tirones.
        val headingUp = prefs.carHeadingUp
        if (!headingUp) bearing = 0.0
        else if (loc.hasBearing() && loc.hasSpeed() && loc.speed >= HEADING_UP_MIN_SPEED) {
            val diff = ((loc.bearing - bearing + 540.0) % 360.0) - 180.0
            bearing = (bearing + diff * 0.6 + 360.0) % 360.0
        }
        // El coche en lo que no tapan los botones: en el centro con el norte arriba y, con la
        // dirección arriba, al 70 % del alto (más carretera por delante que por detrás).
        val r = visible ?: Rect(0, 0, size.first, size.second)
        val top = if (headingUp) r.top + r.height() * (2 * HEADING_UP_Y - 1) else r.top.toFloat()
        val pos = CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude)).zoom(zoom).bearing(bearing)
            .padding(r.left.toDouble(), top.toDouble(), (size.first - r.right).toDouble(), (size.second - r.bottom).toDouble())
            .build()
        val u = CameraUpdateFactory.newCameraPosition(pos)
        // Una posición por segundo: la animación lineal de algo menos de un segundo la deja suave y al día.
        if (animate) m.easeCamera(u, 900, false) else m.moveCamera(u)
    }

    override fun setHeadingUp(on: Boolean) {
        prefs.carHeadingUp = on
        if (!on) bearing = 0.0
        recenter(animate = true)
    }

    override fun setPanel(p: ManeuverOverlay.Panel?) {
        if (p == panel) return
        panel = p
        hud?.let { it.maneuver.panel = p; it.invalidate() }
    }

    override fun release() {
        main.removeCallbacks(resumeFollow)
        mapView?.let { mv -> runCatching { mv.onPause(); mv.onStop(); mv.onDestroy() } }
        presentation?.dismiss()
        display?.release()
        mapView = null; map = null; style = null; hud = null; presentation = null; display = null
    }

    /** Lo que va encima del mapa, sin girar: velocidad, tarjeta de la maniobra y la atribución (CC-BY del IGN). */
    private class Hud(context: Context, private val d: Float) : View(context) {
        val speed = SpeedOverlay(d)
        val maneuver = ManeuverOverlay(d)
        var area: Rect? = null
            set(v) { field = v; speed.area = v; maneuver.area = v }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF37474F.toInt(); textSize = 10f * d; textAlign = Paint.Align.RIGHT
            setShadowLayer(2f * d, 0f, 0f, android.graphics.Color.WHITE)
        }

        override fun onDraw(c: Canvas) {
            speed.paint(c, width, height)
            maneuver.paint(c, width, height)
            val a = area ?: Rect(0, 0, width, height)
            c.drawText(ATTRIBUTION, a.right - 8f * d, a.bottom - 6f * d, text)
        }
    }

    companion object {
        /** El Mapa Base vectorial del IGN (CNIG), estilo en color; sin clave. Comprobado el 27-nov-2026. */
        const val STYLE_URL = "https://vt-mapabase.idee.es/files/styles/mapaBase_scn_color1_CNIG.json"
        const val ATTRIBUTION = "© IGN · Mapa Base"
        private const val RESUME_FOLLOW_MS = 15_000L
        private const val HEADING_UP_MIN_SPEED = 2f
        private const val HEADING_UP_Y = 0.7f
        private const val MIN_ZOOM = 4.0
        private const val MAX_ZOOM = 19.0
        /** z11 en osmdroid: más lejos los radares serían una nube. */
        private const val RADAR_MIN_ZOOM = 10f

        private const val DIM = "gps-dim"
        private const val ALT = "gps-alt"
        private const val PREVIEW = "gps-preview"
        private const val RECORDING = "gps-recording"
        private const val DONE = "gps-done"
        private const val AHEAD = "gps-ahead"
        private const val GAP = "gps-gap"
        private const val TARGET = "gps-target"
        private const val STRETCH = "gps-stretch"
        private const val POINTS = "gps-points"
        private const val RADARS = "gps-radars"
        private const val POSITION = "gps-position"
        private const val ICON_ARROW = "gps-pos-arrow"
        private const val ICON_DOT = "gps-pos-dot"
        private const val ICON_STALE = "gps-pos-stale"

        private var cacheSet = false

        /**
         * La caché de MapLibre (lo visto se guarda y sirve sin cobertura) a 1 GB: de serie son 50 MB,
         * poco para varios viajes. Una vez por proceso.
         */
        fun setUpCache(app: Context) {
            if (cacheSet) return
            cacheSet = true
            runCatching {
                OfflineManager.getInstance(app).setMaximumAmbientCacheSize(1_000_000_000L, object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {}
                    override fun onError(message: String) {}
                })
            }
        }
    }
}

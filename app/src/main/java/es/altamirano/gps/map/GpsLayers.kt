package es.altamirano.gps.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import es.altamirano.gps.core.FollowStatus
import es.altamirano.gps.core.Radar
import es.altamirano.gps.core.RadarKind
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.RoutePreviewState
import es.altamirano.gps.ui.MapColors
import es.altamirano.gps.ui.RadarMarks
import es.altamirano.gps.ui.visibleRadars
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as P
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lo de la app encima del mapa de MapLibre, igual en el móvil y en el coche: track abierto, track que
 * se graba, rutas de la vista previa, lo que se sigue (andado, por andar y el hueco hasta él), la línea
 * recta al destino, los puntos con su nombre, los radares y tu posición. Todo en capas GeoJSON encima
 * del estilo; al cambiar de mapa (un estilo nuevo borra las capas) [install] las vuelve a poner con lo
 * último que se sabía.
 *
 * [car]: más grueso y la flecha grande de los navegadores (la pantalla está lejos y se mira de reojo).
 * [dark]: de noche, un velo sobre el mapa (no sobre lo de la app).
 */
class GpsLayers(private val ctx: Context, private val density: Float, private val car: Boolean, private val dark: Boolean = false) {
    private var style: Style? = null

    // Lo último de cada cosa, para volver a ponerlo tras cambiar de estilo.
    private var location: Location? = null
    private var fallback: Pair<Double, Double>? = null
    private var following: FollowState? = null
    private var recording: List<TrackPoint> = emptyList()
    private var opened: List<TrackPoint> = emptyList()
    private var points: List<Waypoint> = emptyList()
    private var target: Waypoint? = null
    private var preview: RoutePreviewState? = null

    private val radarMarks = RadarMarks(density)
    private var radars: List<Radar> = emptyList()
    private var radarsShown: Boolean? = null

    private var lastRouteKey: Any? = null

    /** Metro, Metro Ligero y Cercanías de Madrid (sólo en el móvil). */
    private var transit = false

    /** Las capas que se pueden tocar en el móvil. */
    companion object {
        const val POINTS_LAYER = "gps-points-c"
        const val POINTS_TEXT_LAYER = "gps-points-t"
        const val ALT_LAYER = "gps-alt-l"

        private const val DIM = "gps-dim"
        private const val ALT = "gps-alt"
        private const val PREVIEW = "gps-preview"
        private const val OPENED = "gps-opened"
        private const val RECORDING = "gps-recording"
        private const val DONE = "gps-done"
        private const val AHEAD = "gps-ahead"
        private const val GAP = "gps-gap"
        private const val TARGET = "gps-target"
        private const val STRETCH = "gps-stretch"
        private const val POINTS = "gps-points"
        private const val RADARS = "gps-radars"
        private const val ACCURACY = "gps-accuracy"
        private const val POSITION = "gps-position"
        private const val ICON_ARROW = "gps-pos-arrow"
        private const val ICON_DOT = "gps-pos-dot"
        private const val ICON_STALE = "gps-pos-stale"
        private const val TRANSIT = "gps-transit"
        private const val TRANSIT_ASSET = "transporte_madrid.geojson"
        /** Las estaciones desde z12 de las teselas de 256 y sus nombres desde z14 (MapLibre: uno menos). */
        private const val STATION_MIN_ZOOM = 11f
        private const val STATION_TEXT_MIN_ZOOM = 13f

        /** El GeoJSON de `assets` (264 KB), leído una vez: se vuelve a poner en cada cambio de estilo. */
        @Volatile private var transitJson: String? = null
        /** z11 de las teselas de 256 (MapLibre cuenta uno menos): más lejos los radares serían una nube. */
        private const val RADAR_MIN_ZOOM = 10f
    }

    /**
     * El estilo, sólo si está cargado del todo. Al cambiar de mapa MapLibre invalida el anterior y,
     * si algo (la posición, cada segundo) lo toca, cierra la app: «Calling getSourceAs when a newer
     * style is loading» (le pasó a Bruno con la 0.27.0).
     */
    private val live: Style? get() = style?.takeIf { it.isFullyLoaded }

    private fun source(id: String) = live?.getSourceAs<GeoJsonSource>(id)

    /** Antes de cambiar de estilo: las capas se sueltan del actual hasta que [install] las ponga en el nuevo. */
    fun detach() { style = null }

    /** Pone las capas de la app en un estilo recién cargado y les da lo último que se sabía. */
    fun install(st: Style) {
        style = st
        listOf(DIM, ALT, PREVIEW, OPENED, RECORDING, DONE, AHEAD, GAP, TARGET, STRETCH, POINTS, RADARS, ACCURACY, POSITION)
            .forEach { if (st.getSource(it) == null) st.addSource(GeoJsonSource(it)) }
        // Grosores en dp: los del móvil de siempre y, en el coche, 1,7 veces.
        val k = if (car) 1.7f else 1f
        val round = arrayOf(P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND))
        fun line(id: String, src: String, color: Int, width: Float, vararg extra: PropertyValue<*>): Layer =
            LineLayer(id, src).withProperties(P.lineColor(color), P.lineWidth(width), *round, *extra)
        val white = android.graphics.Color.WHITE
        val dash = P.lineDasharray(arrayOf(2f, 1.6f))
        if (dark) {
            source(DIM)?.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(listOf(
                Point.fromLngLat(-180.0, -85.0), Point.fromLngLat(180.0, -85.0), Point.fromLngLat(180.0, 85.0),
                Point.fromLngLat(-180.0, 85.0), Point.fromLngLat(-180.0, -85.0))))))
            st.addLayer(FillLayer("$DIM-l", DIM).withProperties(P.fillColor(android.graphics.Color.BLACK), P.fillOpacity(0.4f)))
        }
        if (!car) addTransit(st)
        st.addLayer(line(ALT_LAYER, ALT, MapColors.followDone.toArgb(), 6f * k))
        st.addLayer(line("$PREVIEW-c", PREVIEW, white, 7f * k + 4f))
        st.addLayer(line("$PREVIEW-l", PREVIEW, MapColors.follow.toArgb(), 7f * k))
        st.addLayer(line("$OPENED-l", OPENED, MapColors.opened.toArgb(), 5f * k))
        st.addLayer(line("$RECORDING-l", RECORDING, MapColors.recording.toArgb(), 4f * k))
        st.addLayer(line("$DONE-l", DONE, MapColors.followDone.toArgb(), 6f * k))
        // Lo que queda, con un borde blanco: sobre el mapa claro del IGN se ve mucho mejor.
        st.addLayer(line("$AHEAD-c", AHEAD, white, 6f * k + 4f))
        st.addLayer(line("$AHEAD-l", AHEAD, MapColors.follow.toArgb(), 6f * k))
        st.addLayer(line("$GAP-l", GAP, MapColors.offTrack.toArgb(), 3f * k, dash))
        st.addLayer(line("$TARGET-l", TARGET, MapColors.target.toArgb(), 3f * k, dash))
        st.addLayer(LineLayer("$STRETCH-l", STRETCH).withProperties(P.lineColor(Expression.toColor(Expression.get("color"))),
            P.lineWidth(3f * k), dash, *round).apply { minZoom = RADAR_MIN_ZOOM })
        st.addLayer(CircleLayer(POINTS_LAYER, POINTS).withProperties(
            P.circleColor(Expression.toColor(Expression.get("color"))), P.circleRadius(Expression.get("r")),
            P.circleStrokeColor(white), P.circleStrokeWidth(2.5f)))
        st.addLayer(SymbolLayer(POINTS_TEXT_LAYER, POINTS).withProperties(
            P.textField(Expression.get("name")), P.textFont(arrayOf("Noto Sans Bold")), P.textSize(if (car) 14f else 12f),
            P.textColor(Expression.toColor(Expression.get("color"))), P.textHaloColor(white), P.textHaloWidth(2f),
            P.textAnchor(Property.TEXT_ANCHOR_TOP), P.textOffset(arrayOf(0f, 1.1f)), P.textOptional(true)))
        st.addLayer(SymbolLayer("$RADARS-l", RADARS).withProperties(
            P.iconImage(Expression.get("icon")), P.iconAllowOverlap(true), P.iconIgnorePlacement(true),
            // Derechos aunque el mapa gire, como las señales de verdad.
            P.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT)).apply { minZoom = RADAR_MIN_ZOOM })
        st.addLayer(FillLayer("$ACCURACY-l", ACCURACY).withProperties(P.fillColor(MapColors.position.toArgb()), P.fillOpacity(0.16f)))
        st.addImage(ICON_ARROW, positionIcon(arrow = true, color = MapColors.position.toArgb()))
        st.addImage(ICON_DOT, positionIcon(arrow = false, color = MapColors.position.toArgb()))
        st.addImage(ICON_STALE, positionIcon(arrow = false, color = MapColors.stale.toArgb()))
        st.addLayer(SymbolLayer("$POSITION-l", POSITION).withProperties(
            P.iconImage(Expression.get("icon")), P.iconRotate(Expression.get("bearing")),
            // La flecha apunta al rumbo respecto al norte: gira con el mapa.
            P.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            P.iconAllowOverlap(true), P.iconIgnorePlacement(true)))
        // Lo último que se sabía.
        lastRouteKey = null; radarsShown = null
        reloadRadars(); pushPosition(); pushFollow(); pushTracks(); pushPoints(); pushPreview()
    }

    // ---- Transporte de Madrid ----

    /**
     * Debajo de todo lo de la app: las líneas con su color, semitransparentes para que se lea el mapa
     * debajo; las estaciones, puntos blancos; y sus nombres con las líneas al acercarse. Los datos
     * salen de `Tools/transporte_madrid.py` (CRTM).
     */
    private fun addTransit(st: Style) {
        val json = transitJson ?: runCatching {
            ctx.assets.open(TRANSIT_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()?.also { transitJson = it } ?: return
        if (st.getSource(TRANSIT) == null) st.addSource(GeoJsonSource(TRANSIT, json))
        val isLine = Expression.eq(Expression.get("k"), Expression.literal("l"))
        val isStation = Expression.eq(Expression.get("k"), Expression.literal("s"))
        val mode = Expression.get("mode")
        // Más gruesas al acercarse; el metro algo más que Cercanías, que cubre mucho más terreno. El
        // zoom tiene que ir arriba del todo (MapLibre no lo admite dentro de un `match`: se ignoraba
        // y salían de 1 dp), así que el modo va dentro de cada parada. [extra]: el borde.
        fun lineWidth(extra: Float) = Expression.interpolate(Expression.exponential(1.4f), Expression.zoom(),
            *listOf(9 to 0.5f, 13 to 1f, 16 to 2f).map { (z, k) ->
                Expression.stop(z, Expression.match(mode, Expression.literal("cercanias"),
                    Expression.literal(4f * k + extra), Expression.literal(5.5f * k + extra)))
            }.toTypedArray())
        // El Ramal es blanco: un borde oscuro debajo de todas para que se vea sobre el mapa claro.
        st.addLayer(LineLayer("$TRANSIT-c", TRANSIT).withProperties(
            P.lineColor(android.graphics.Color.rgb(40, 40, 40)), P.lineOpacity(0.35f), P.lineWidth(lineWidth(1.5f)),
            P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND)).withFilter(isLine))
        st.addLayer(LineLayer("$TRANSIT-l", TRANSIT).withProperties(
            P.lineColor(Expression.toColor(Expression.get("color"))), P.lineOpacity(0.7f), P.lineWidth(lineWidth(0f)),
            P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND)).withFilter(isLine))
        st.addLayer(CircleLayer("$TRANSIT-s", TRANSIT).withProperties(
            P.circleColor(android.graphics.Color.WHITE), P.circleOpacity(0.9f),
            P.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(STATION_MIN_ZOOM, 2.5f), Expression.stop(16, 6f))),
            P.circleStrokeColor(android.graphics.Color.rgb(40, 40, 40)), P.circleStrokeWidth(1.5f),
        ).withFilter(isStation).apply { minZoom = STATION_MIN_ZOOM })
        st.addLayer(SymbolLayer("$TRANSIT-t", TRANSIT).withProperties(
            P.textField(Expression.concat(Expression.get("name"), Expression.literal("\n"), Expression.get("lines"))),
            P.textFont(arrayOf("Noto Sans Bold")), P.textSize(11f),
            P.textColor(android.graphics.Color.rgb(30, 30, 30)), P.textHaloColor(android.graphics.Color.WHITE), P.textHaloWidth(1.6f),
            P.textAnchor(Property.TEXT_ANCHOR_TOP), P.textOffset(arrayOf(0f, 0.7f)), P.textOptional(true),
        ).withFilter(isStation).apply { minZoom = STATION_TEXT_MIN_ZOOM })
        pushTransitVisibility()
    }

    /** Enseña u oculta la capa de transporte (el botón del mapa del móvil). */
    fun setTransit(on: Boolean) { transit = on; pushTransitVisibility() }

    private fun pushTransitVisibility() {
        val st = live ?: style ?: return
        val v = P.visibility(if (transit) Property.VISIBLE else Property.NONE)
        listOf("$TRANSIT-c", "$TRANSIT-l", "$TRANSIT-s", "$TRANSIT-t").forEach { st.getLayer(it)?.setProperties(v) }
    }

    // ---- Iconos ----

    /** Un mapa de bits pintado a la densidad de la pantalla y marcado con ella (MapLibre lo toma en dp). */
    private fun bitmap(sizeDp: Float, paint: (Canvas, Float) -> Unit): Bitmap {
        val px = (sizeDp * density).toInt().coerceAtLeast(1)
        val b = createBitmap(px, px)
        b.density = (density * 160).toInt()
        paint(Canvas(b), px / 2f)
        return b
    }

    /**
     * Tu posición. En el coche, en marcha, la flecha grande de los navegadores; en el móvil, el punto
     * con una flecha delante. Parado, el punto (gris si es una posición vieja).
     */
    private fun positionIcon(arrow: Boolean, color: Int): Bitmap = bitmap(if (arrow) (if (car) 52f else 48f) else if (car) 30f else 24f) { c, mid ->
        val d = density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.color = android.graphics.Color.WHITE; strokeJoin = Paint.Join.ROUND
            strokeWidth = (if (car) 3.5f else 2.5f) * d
        }
        val r = (if (car) 11f else 8f) * d
        when {
            arrow && car -> {
                val s = 15 * d
                val path = Path().apply {
                    moveTo(mid, mid - s * 1.5f); lineTo(mid + s, mid + s); lineTo(mid, mid + s * 0.45f); lineTo(mid - s, mid + s); close()
                }
                c.drawPath(path, fill); c.drawPath(path, ring)
            }
            arrow -> {
                // El punto y, delante, un triángulo hacia donde vas (como en el mapa de siempre).
                val s = 9 * d
                c.drawPath(Path().apply { moveTo(mid, mid - s * 2.1f); lineTo(mid + s * 0.9f, mid - s * 0.6f); lineTo(mid - s * 0.9f, mid - s * 0.6f); close() }, fill)
                c.drawCircle(mid, mid, r, fill); c.drawCircle(mid, mid, r, ring)
            }
            else -> { c.drawCircle(mid, mid, r, fill); c.drawCircle(mid, mid, r, ring) }
        }
    }

    private fun radarIconName(rd: Radar): String = when {
        rd.kind == RadarKind.RED_LIGHT -> "radar-semaforo"
        else -> "radar-${if (radarMarks.colorOf(rd) == RadarMarks.MOBILE_COLOR) "movil" else "fijo"}-${rd.maxspeed ?: "camara"}"
    }

    private fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

    private fun lineOf(pts: List<Point>): FeatureCollection =
        FeatureCollection.fromFeatures(if (pts.size < 2) emptyList() else listOf(Feature.fromGeometry(LineString.fromLngLats(pts))))

    // ---- Datos ----

    /** Los radares que se pueden enseñar, según los ajustes (al empezar y cuando cambian). */
    fun reloadRadars() {
        radars = visibleRadars(ctx)
        val st = live ?: return
        val icons = HashSet<String>()
        val features = ArrayList<Feature>()
        val stretches = ArrayList<Feature>()
        for (rd in radars) {
            if (rd.isStretch && rd.endLat != null && rd.endLon != null) {
                stretches += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(rd.lon, rd.lat), Point.fromLngLat(rd.endLon, rd.endLat))))
                    .apply { addStringProperty("color", hex(radarMarks.colorOf(rd))) }
            }
            // Los tramos de radar móvil son sólo la línea (son muchos y casi nunca hay radar).
            if (rd.kind == RadarKind.MOBILE_STRETCH) continue
            val name = radarIconName(rd)
            if (icons.add(name) && st.getImage(name) == null) st.addImage(name, bitmap(30f) { c, mid -> radarMarks.paintRadar(c, mid, mid, rd) })
            features += Feature.fromGeometry(Point.fromLngLat(rd.lon, rd.lat)).apply { addStringProperty("icon", name) }
        }
        source(RADARS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        source(STRETCH)?.setGeoJson(FeatureCollection.fromFeatures(stretches))
        radarsShown = null
        pushRadarVisibility()
    }

    fun setPosition(loc: Location?, fallback: Pair<Double, Double>?) {
        location = loc; this.fallback = fallback
        pushPosition(); pushTarget(); pushRadarVisibility()
    }

    private fun pushPosition() {
        val loc = location
        val fresh = loc?.takeIf { Gps.isFresh(it) }
        val at = loc?.let { it.latitude to it.longitude } ?: fallback
        val moving = fresh != null && fresh.hasBearing() && fresh.hasSpeed() && fresh.speed > 0.7f
        source(POSITION)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(at?.let { (lat, lon) ->
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("icon", when { moving -> ICON_ARROW; fresh != null -> ICON_DOT; else -> ICON_STALE })
                addNumberProperty("bearing", if (moving) fresh!!.bearing else 0f)
            }
        })))
        // El círculo de precisión, con posición de ahora y en el móvil (en el coche estorba).
        val acc = fresh?.takeIf { !car && it.hasAccuracy() && it.accuracy > 8f }
        source(ACCURACY)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(acc?.let { circle(it.latitude, it.longitude, it.accuracy.toDouble()) })))
    }

    /** Un círculo de [radiusM] alrededor de un punto, como polígono de 48 lados. */
    private fun circle(lat: Double, lon: Double, radiusM: Double): Feature {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)))
        val ring = (0..48).map { i -> val a = 2 * Math.PI * i / 48; Point.fromLngLat(lon + dLon * cos(a), lat + dLat * sin(a)) }
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }

    fun setFollow(st: FollowState?, loc: Location?) {
        following = st; location = loc ?: location
        pushFollow(); pushRadarVisibility()
    }

    /** La ruta o el track que se sigue, partido por donde vas (sólo se rehace al pasar de tramo). */
    private fun pushFollow() {
        val fol = following
        val route = fol?.route
        val fix = fol?.fix
        val split = if (fix == null || fix.status == FollowStatus.APPROACHING) 0 else fix.match.segment + 1
        val key = route?.let { System.identityHashCode(it) to split }
        if (key != lastRouteKey) {
            lastRouteKey = key
            if (route == null) {
                source(DONE)?.setGeoJson(lineOf(emptyList()))
                source(AHEAD)?.setGeoJson(lineOf(emptyList()))
            } else {
                val all = route.points.map { Point.fromLngLat(it.lon, it.lat) }
                val here = fix?.takeIf { split > 0 }?.let { Point.fromLngLat(it.match.lon, it.match.lat) }
                source(DONE)?.setGeoJson(lineOf(if (here == null) emptyList() else all.take(split) + here))
                source(AHEAD)?.setGeoJson(lineOf(if (here == null) all else listOf(here) + all.drop(split)))
            }
        }
        val fresh = location?.takeIf { Gps.isFresh(it) }
        val away = fix != null && fresh != null && fix.status in setOf(FollowStatus.OFF_TRACK, FollowStatus.APPROACHING)
        source(GAP)?.setGeoJson(lineOf(if (away) listOf(Point.fromLngLat(fresh!!.longitude, fresh.latitude), Point.fromLngLat(fix!!.match.lon, fix.match.lat)) else emptyList()))
    }

    fun setRecording(pts: List<TrackPoint>) { recording = pts; pushTracks() }
    fun setOpened(pts: List<TrackPoint>) { opened = pts; pushTracks() }

    private fun pushTracks() {
        source(RECORDING)?.setGeoJson(lineOf(recording.map { Point.fromLngLat(it.lon, it.lat) }))
        source(OPENED)?.setGeoJson(lineOf(opened.map { Point.fromLngLat(it.lon, it.lat) }))
    }

    fun setPoints(points: List<Waypoint>, target: Waypoint?) { this.points = points; this.target = target; pushPoints(); pushTarget() }

    private fun pushPoints() {
        source(POINTS)?.setGeoJson(FeatureCollection.fromFeatures(points.map { w ->
            val isTarget = w.id == target?.id
            Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat)).apply {
                addNumberProperty("id", w.id)
                addStringProperty("name", w.name)
                addStringProperty("color", hex((if (isTarget) MapColors.target else MapColors.waypoint).toArgb()))
                addNumberProperty("r", if (isTarget) 10 else 8)
            }
        }))
    }

    /** La línea recta (discontinua: no es una ruta) desde donde estás al destino. */
    private fun pushTarget() {
        val t = target
        val fresh = location?.takeIf { Gps.isFresh(it) }
        source(TARGET)?.setGeoJson(lineOf(if (t != null && fresh != null) listOf(Point.fromLngLat(fresh.longitude, fresh.latitude), Point.fromLngLat(t.lon, t.lat)) else emptyList()))
    }

    fun setPreview(st: RoutePreviewState?) { preview = st; pushPreview() }

    /** La ruta elegida en azul con borde y las demás claras debajo (cada una con su número, para tocarla). */
    private fun pushPreview() {
        val st = preview
        source(PREVIEW)?.setGeoJson(lineOf(st?.current?.route?.points.orEmpty().map { Point.fromLngLat(it.lon, it.lat) }))
        source(ALT)?.setGeoJson(FeatureCollection.fromFeatures(st?.options.orEmpty().mapIndexedNotNull { i, o ->
            if (i == st?.selected || o.route.points.size < 2) null
            else Feature.fromGeometry(LineString.fromLngLats(o.route.points.map { Point.fromLngLat(it.lon, it.lat) })).apply { addNumberProperty("i", i) }
        }))
    }

    /** Radares: sólo yendo en coche (a más de 20 km/h, dos minutos después, o con una ruta en coche). */
    private fun pushRadarVisibility() {
        val st = live ?: return
        radarMarks.update(location, following)
        val show = radarMarks.visible && radars.isNotEmpty()
        if (show == radarsShown) return
        radarsShown = show
        val v = P.visibility(if (show) Property.VISIBLE else Property.NONE)
        st.getLayer("$RADARS-l")?.setProperties(v)
        st.getLayer("$STRETCH-l")?.setProperties(v)
    }
}

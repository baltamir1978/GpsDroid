package es.altamirano.gps.car

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import es.altamirano.gps.core.MapStyle
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.data.RoutePreviewState
import es.altamirano.gps.map.GpsLayers
import es.altamirano.gps.map.MapStyles
import es.altamirano.gps.data.Tiles
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import kotlin.math.ln
import kotlin.math.round

/**
 * El mapa de la pantalla del coche, con MapLibre: de serie el **callejero vectorial del IGN** (los
 * nombres de las calles siguen derechos al girar con la marcha; con las imágenes del antiguo IGN Base
 * giraban y quedaban del revés: Bruno, 27-nov-2026) y, con el botón de capas, el topográfico, el
 * satélite y el híbrido (la ortofoto con los nombres del vectorial).
 *
 * Android Auto da una `Surface`; en ella se crea una pantalla virtual y, encima, un `Presentation`
 * con el `MapView` (en modo `TextureView`) y una vista ([Hud]) con la velocidad, la tarjeta de la
 * maniobra y la atribución. Lo de la app (ruta, puntos, radares, posición) son capas de [GpsLayers],
 * las mismas que en el móvil.
 *
 * Zoom: MapLibre cuenta con teselas de 512; [Prefs.carZoom] guarda el de siempre (teselas de 256), uno más.
 */
class CarMapRenderer(private val ctx: Context, private val dark: Boolean) : SurfaceCallback {
    private val prefs = Prefs(ctx)
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var hud: Hud? = null
    private var layers: GpsLayers? = null
    private var size = 0 to 0
    private var visible: Rect? = null
    private var density = 1f

    private var style: MapStyle = MapStyle.fromKey(prefs.carStyle.let { if (it == "vector") MapStyle.BASE.key else it })
    private var location: Location? = null
    private var points: List<Waypoint> = emptyList()
    private var target: Waypoint? = null
    private var track: List<TrackPoint> = emptyList()
    private var following: FollowState? = null
    private var preview: RoutePreviewState? = null
    private var panel: ManeuverOverlay.Panel? = null

    private var zoom = prefs.carZoom - 1
    private var bearing = 0.0

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val resumeFollow = Runnable { follow = true }

    /** Seguir la posición: se quita al mover el mapa (el modo mover del coche) y vuelve sola a los 15 s. */
    var follow = true
        set(v) {
            val changed = field != v; field = v
            main.removeCallbacks(resumeFollow)
            if (v) recenter(animate = false) else main.postDelayed(resumeFollow, RESUME_FOLLOW_MS)
            if (changed) onFollowChanged?.invoke()
        }
    var onFollowChanged: (() -> Unit)? = null

    fun userMovedMap() { follow = false; main.removeCallbacks(resumeFollow); main.postDelayed(resumeFollow, RESUME_FOLLOW_MS) }

    /** En el coche, el orden del botón de capas: el callejero primero. */
    private val carStyles = listOf(MapStyle.BASE, MapStyle.TOPO, MapStyle.ORTHO, MapStyle.HYBRID)

    /** Pasa al mapa siguiente; devuelve su nombre para el aviso. */
    fun nextStyle(): String {
        style = carStyles[(carStyles.indexOf(style) + 1) % carStyles.size]
        prefs.carStyle = style.key
        map?.let { m -> loadStyle(m) }
        return style.label
    }

    private fun loadStyle(m: MapLibreMap) {
        m.setMaxZoomPreference(style.viewMaxZoom - 1.0)
        MapStyles.apply(m, style) { st ->
            if (map !== m) return@apply
            layers?.install(st)
            redraw()
            recenter(animate = false)
        }
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        size = container.width to container.height
        density = container.dpi / 160f
        val vd = ctx.getSystemService(DisplayManager::class.java).createVirtualDisplay("IGN GPS", container.width, container.height,
            container.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = vd
        val p = Presentation(ctx, vd.display)
        Tiles.ensureMapLibre(ctx)
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
        layers = GpsLayers(ctx, density, car = true, dark = dark)
        mv.getMapAsync { m ->
            if (mapView !== mv) return@getMapAsync
            map = m
            // Los gestos los manda el coche (onScroll/onScale), no la pantalla virtual.
            m.uiSettings.setAllGesturesEnabled(false)
            m.setMinZoomPreference(MIN_ZOOM)
            loadStyle(m)
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

    fun zoomBy(delta: Double) = setZoom(round((map?.cameraPosition?.zoom ?: zoom) + delta))

    private fun setZoom(z: Double) {
        zoom = z.coerceIn(MIN_ZOOM, style.viewMaxZoom - 1.0)
        prefs.carZoom = zoom + 1
        val m = map ?: return
        if (follow) recenter(animate = true) else m.easeCamera(CameraUpdateFactory.zoomTo(zoom), 250)
    }

    fun radarsChanged() = layers?.reloadRadars()

    fun update(location: Location?, points: List<Waypoint>, target: Waypoint?, track: List<TrackPoint>, following: FollowState?) {
        this.location = location; this.points = points; this.target = target; this.track = track; this.following = following
        // La última posición con señal, guardada también desde el coche (cada 50 m: al aparcar queda casi
        // donde el coche): es la bola gris y el punto de salida de una ruta si al arrancar no hay señal.
        location?.takeIf { Gps.isFresh(it) }?.let { loc ->
            val last = prefs.last
            if (last == null || es.altamirano.gps.core.Geo.distanceM(last.first, last.second, loc.latitude, loc.longitude) > 50)
                prefs.last = loc.latitude to loc.longitude
        }
        redraw()
        if (follow) recenter(animate = true)
    }

    private var lastPoints: Any? = null
    private var lastTrackSize = -1

    private fun redraw() {
        val l = layers ?: return
        l.setPosition(location, prefs.last)
        l.setFollow(following, location)
        if (track.size != lastTrackSize) { lastTrackSize = track.size; l.setRecording(track) }
        val pk = points to target?.id
        if (pk != lastPoints) { lastPoints = pk; l.setPoints(points, target) }
        hud?.let { h ->
            val s = location?.takeIf { Gps.isFresh(it) && it.hasSpeed() }
            h.speed.speedKmh = s?.let { kotlin.math.round(it.speed * 3.6f).toInt() }
            h.speed.limitKmh = following?.radar?.maxspeed
            h.invalidate()
        }
    }

    /**
     * Las rutas posibles: la elegida en azul con borde y las demás claras debajo. Al aparecer (o al
     * cambiar de elegida) se encuadra y se deja de seguir; al acabar, se sigue otra vez.
     */
    fun showPreview(st: RoutePreviewState?) {
        val was = preview
        preview = st
        layers?.setPreview(st)
        val selected = st?.current?.route?.points.orEmpty()
        val m = map
        if (m != null && selected.size > 1 && (was == null || was.selected != st?.selected)) {
            main.removeCallbacks(resumeFollow)
            if (follow) { follow = false; main.removeCallbacks(resumeFollow) }
            val b = LatLngBounds.Builder().apply { selected.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            val r = visible ?: Rect(0, 0, size.first, size.second)
            val pad = (40 * density).toInt()
            bearing = 0.0
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, 0.0, 0.0, r.left + pad, r.top + pad, size.first - r.right + pad, size.second - r.bottom + pad), 600)
        }
        if (st == null && was != null) follow = true
    }

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

    fun setHeadingUp(on: Boolean) {
        prefs.carHeadingUp = on
        if (!on) bearing = 0.0
        recenter(animate = true)
    }

    fun setPanel(p: ManeuverOverlay.Panel?) {
        if (p == panel) return
        panel = p
        hud?.let { it.maneuver.panel = p; it.invalidate() }
    }

    fun release() {
        main.removeCallbacks(resumeFollow)
        mapView?.let { mv -> runCatching { mv.onPause(); mv.onStop(); mv.onDestroy() } }
        presentation?.dismiss()
        display?.release()
        mapView = null; map = null; hud = null; layers = null; presentation = null; display = null
        lastPoints = null; lastTrackSize = -1
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
            c.drawText("© IGN", a.right - 8f * d, a.bottom - 6f * d, text)
        }
    }

    private companion object {
        const val RESUME_FOLLOW_MS = 15_000L
        const val HEADING_UP_MIN_SPEED = 2f
        const val HEADING_UP_Y = 0.7f
        const val MIN_ZOOM = 4.0
    }
}

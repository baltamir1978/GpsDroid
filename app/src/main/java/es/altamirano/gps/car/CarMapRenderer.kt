package es.altamirano.gps.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import es.altamirano.gps.core.MapStyle
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.ui.MapScene
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.ln

/**
 * Pinta el mapa en la pantalla del coche. Android Auto da una `Surface`; en ella se crea una pantalla
 * virtual y, encima, un `Presentation` con un `MapView` de osmdroid normal y corriente: así el coche
 * usa las mismas teselas (las descargadas también), capas y overlays que el móvil, sin reescribir el
 * pintado. Todo en el hilo principal, como las vistas.
 */
class CarMapRenderer(private val car: CarContext) : SurfaceCallback {
    private val prefs = Prefs(car)
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var map: MapView? = null
    private var scene: MapScene? = null
    private var size = 0 to 0
    private var visible: Rect? = null

    /** Lo último que llegó, para pintarlo también en un mapa recién creado. */
    private var location: Location? = null
    private var points: List<Waypoint> = emptyList()
    private var target: Waypoint? = null
    private var track: List<TrackPoint> = emptyList()
    private var following: FollowState? = null
    private var zoom = prefs.view?.third?.coerceAtLeast(13.0) ?: 15.0
    private var speed: SpeedOverlay? = null

    var style: MapStyle = prefs.style
        private set

    /**
     * Seguir la posición (el coche en el centro de lo que se ve): se quita al arrastrar el mapa y
     * vuelve sola a los [RESUME_FOLLOW_MS] sin tocarlo, como en los navegadores. [onFollowChanged]
     * avisa para cambiar el botón.
     */
    var follow = true
        set(v) {
            val changed = field != v; field = v
            main.removeCallbacks(resumeFollow)
            if (v) recenter() else main.postDelayed(resumeFollow, RESUME_FOLLOW_MS)
            if (changed) onFollowChanged?.invoke()
        }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val resumeFollow = Runnable { follow = true }

    /** Al tocar el mapa (arrastrar, el modo de mover del coche): deja de seguir y vuelve a contar los segundos. */
    fun userMovedMap() { follow = false; main.removeCallbacks(resumeFollow); main.postDelayed(resumeFollow, RESUME_FOLLOW_MS) }
    var onFollowChanged: (() -> Unit)? = null

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        size = container.width to container.height
        val vd = car.getSystemService(DisplayManager::class.java).createVirtualDisplay("IGN GPS", container.width, container.height,
            container.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = vd
        val p = Presentation(car, vd.display)
        val ctx = p.context
        val density = container.dpi / 160f
        val sc = MapScene(ctx, density)
        val m = MapView(ctx).apply {
            isTilesScaledToDpi = true
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            sc.install(this)
            overlays += ScaleBarOverlay(this).apply { setScaleBarOffset((16 * density).toInt(), (16 * density).toInt()) }
            overlays += SpeedOverlay(density).also { speed = it; it.area = visible }
            controller.setZoom(zoom)
            val start = location ?: prefs.last?.let { l -> Location("").apply { latitude = l.first; longitude = l.second } }
            controller.setCenter(start?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(40.2, -3.7))
        }
        sc.setRadars(car)
        sc.applyStyle(m, style)
        sc.setOnline(m, prefs.online)
        sc.setDim(m, car.isDarkMode)
        p.setContentView(m)
        p.show()
        m.onResume()
        presentation = p; map = m; scene = sc
        visible?.let { applyVisibleArea(it) }
        redraw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = visibleArea
        speed?.area = visibleArea
        applyVisibleArea(visibleArea)
    }

    /** El centro del mapa (donde estás, si se sigue) en el centro de lo que no tapan las tarjetas del coche. */
    private fun applyVisibleArea(r: Rect) {
        val m = map ?: return
        m.setMapCenterOffset(r.centerX() - size.first / 2, r.centerY() - size.second / 2)
        recenter()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val m = map ?: return
        userMovedMap()
        m.scrollBy(distanceX.toInt(), distanceY.toInt())
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val m = map ?: return
        // Doble toque: el coche manda un factor fijo (2); pellizco: el cambio de escala, a niveles de zoom.
        setZoom(m.zoomLevelDouble + ln(scaleFactor.toDouble()) / ln(2.0))
    }

    fun zoomBy(delta: Double) { val m = map ?: return; setZoom(m.zoomLevelDouble + delta) }

    private fun setZoom(z: Double) {
        val m = map ?: return
        zoom = z.coerceIn(m.minZoomLevel, m.maxZoomLevel)
        m.controller.setZoom(zoom)
    }

    fun nextStyle(): MapStyle {
        style = style.next()
        prefs.style = style
        val m = map; val sc = scene
        if (m != null && sc != null) sc.applyStyle(m, style)
        return style
    }

    private var preview: es.altamirano.gps.data.RoutePreviewState? = null

    /**
     * Las rutas posibles en el mapa del coche. Al aparecer, se encuadra la elegida y se deja de seguir
     * la posición mientras se elige; al acabar (empezar o volver), se sigue otra vez.
     */
    fun showPreview(st: es.altamirano.gps.data.RoutePreviewState?) {
        val was = preview
        preview = st
        val m = map; val sc = scene
        if (m != null && sc != null) {
            sc.setPreview(m, st) { es.altamirano.gps.data.RoutePreview.select(it) }
            val pts = st?.current?.route?.points
            if (pts != null && pts.size > 1 && (was == null || was.selected != st.selected)) {
                main.removeCallbacks(resumeFollow)
                if (follow) { follow = false; main.removeCallbacks(resumeFollow) }
                m.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lon) }).increaseByScale(1.3f), false)
            }
            m.invalidate()
        }
        if (st == null && was != null) follow = true
    }

    /** Los ajustes de radares han cambiado (desde el móvil o el coche). */
    fun radarsChanged() { scene?.setRadars(car); map?.invalidate() }

    fun update(location: Location?, points: List<Waypoint>, target: Waypoint?, track: List<TrackPoint>, following: FollowState?) {
        this.location = location; this.points = points; this.target = target; this.track = track; this.following = following
        redraw()
        if (follow) recenter()
    }

    private fun redraw() {
        val m = map ?: return
        val sc = scene ?: return
        sc.position.location = location
        sc.position.fallback = prefs.last
        sc.points.points = points
        sc.points.target = target
        sc.points.from = location?.takeIf { Gps.isFresh(it) }?.let { it.latitude to it.longitude }
        sc.setTrack(sc.recording, track)
        sc.setFollow(following, location)
        sc.radars.update(location, following)
        // La velocidad del GPS (en m/s) en km/h; sin señal, nada.
        speed?.let { s ->
            val loc = location?.takeIf { Gps.isFresh(it) && it.hasSpeed() }
            s.speedKmh = loc?.let { kotlin.math.round(it.speed * 3.6f).toInt() }
            s.limitKmh = following?.radar?.maxspeed
        }
        m.invalidate()
    }

    private fun recenter() {
        val m = map ?: return
        val loc = location?.takeIf { Gps.isFresh(it) } ?: return
        // En el acto y sin animar: con una posición por segundo, la animación iba siempre por detrás
        // y el coche no quedaba en el centro yendo deprisa.
        m.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
    }

    fun release() {
        main.removeCallbacks(resumeFollow)
        speed = null
        map?.let { m -> m.onPause(); scene?.release(m) }
        presentation?.dismiss()
        display?.release()
        map = null; scene = null; presentation = null; display = null
    }

    private companion object { const val RESUME_FOLLOW_MS = 15_000L }
}

package es.altamirano.gps.car

import android.graphics.Rect
import android.location.Location
import androidx.car.app.CarContext
import androidx.car.app.SurfaceContainer
import es.altamirano.gps.core.MapStyle
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.data.RoutePreviewState

/**
 * El mapa de la pantalla del coche: el vectorial ([VectorCarMap], de serie) o uno de los raster
 * ([OsmCarMap]: topográfico, satélite, híbrido, IGN Base, con lo descargado). El botón de capas va
 * por los cinco; al pasar del vectorial a un raster o al revés se suelta un motor y se monta el otro
 * sobre la misma `Surface`, con lo último que se sabía (posición, ruta, tarjeta…).
 */
class CarMapRenderer(private val car: CarContext) : CarMap {
    private val prefs = Prefs(car)
    private var key = prefs.carStyle.takeIf { k -> k == VECTOR || MapStyle.entries.any { it.key == k } } ?: VECTOR
    private var engine: CarMap = create(key)

    private var container: SurfaceContainer? = null
    private var visible: Rect? = null
    private var last: (() -> Unit)? = null
    private var preview: RoutePreviewState? = null
    private var panel: ManeuverOverlay.Panel? = null

    private fun create(k: String): CarMap =
        if (k == VECTOR) VectorCarMap(car, car.isDarkMode) else OsmCarMap(car, car.isDarkMode, MapStyle.fromKey(k))

    /** Pasa al mapa siguiente; devuelve su nombre para el aviso. */
    fun nextStyle(): String {
        val keys = listOf(VECTOR) + MapStyle.entries.map { it.key }
        val next = keys[(keys.indexOf(key) + 1) % keys.size]
        val old = engine
        key = next
        prefs.carStyle = next
        if (old is OsmCarMap && next != VECTOR) old.applyStyle(MapStyle.fromKey(next))
        else {
            old.release()
            engine = create(next).also { e ->
                e.onFollowChanged = onFollowChanged
                container?.let { e.onSurfaceAvailable(it) }
                visible?.let { e.onVisibleAreaChanged(it) }
                e.showPreview(preview)
                e.setPanel(panel)
                last?.invoke()
            }
        }
        return if (next == VECTOR) "Callejero (vectorial)" else MapStyle.fromKey(next).label
    }

    override var follow: Boolean
        get() = engine.follow
        set(v) { engine.follow = v }
    override var onFollowChanged: (() -> Unit)? = null
        set(v) { field = v; engine.onFollowChanged = v }

    override fun onSurfaceAvailable(container: SurfaceContainer) { this.container = container; engine.onSurfaceAvailable(container) }
    override fun onSurfaceDestroyed(container: SurfaceContainer) { this.container = null; engine.onSurfaceDestroyed(container) }
    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = visibleArea; engine.onVisibleAreaChanged(visibleArea) }
    override fun onStableAreaChanged(stableArea: Rect) = engine.onStableAreaChanged(stableArea)
    override fun onScroll(distanceX: Float, distanceY: Float) = engine.onScroll(distanceX, distanceY)
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = engine.onScale(focusX, focusY, scaleFactor)

    override fun userMovedMap() = engine.userMovedMap()
    override fun zoomBy(delta: Double) = engine.zoomBy(delta)
    override fun showPreview(st: RoutePreviewState?) { preview = st; engine.showPreview(st) }
    override fun radarsChanged() = engine.radarsChanged()
    override fun update(location: Location?, points: List<Waypoint>, target: Waypoint?, track: List<TrackPoint>, following: FollowState?) {
        last = { engine.update(location, points, target, track, following) }
        engine.update(location, points, target, track, following)
    }
    override fun setHeadingUp(on: Boolean) = engine.setHeadingUp(on)
    override fun setPanel(p: ManeuverOverlay.Panel?) { panel = p; engine.setPanel(p) }
    override fun release() = engine.release()

    private companion object { const val VECTOR = "vector" }
}

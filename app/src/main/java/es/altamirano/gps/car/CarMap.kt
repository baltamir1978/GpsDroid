package es.altamirano.gps.car

import android.location.Location
import androidx.car.app.SurfaceCallback
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.RoutePreviewState

/**
 * Lo que la pantalla del coche le pide al mapa. Hay dos: [VectorCarMap] (el Mapa Base vectorial del
 * IGN con MapLibre: los rótulos siguen derechos al girar) y [OsmCarMap] (las teselas raster de
 * siempre con osmdroid, para el topográfico, la ortofoto y lo descargado). [CarMapRenderer] cambia de
 * uno a otro con el botón de capas.
 */
interface CarMap : SurfaceCallback {
    /** Seguir la posición; se quita al mover el mapa y vuelve sola a los 15 s. */
    var follow: Boolean
    var onFollowChanged: (() -> Unit)?
    fun userMovedMap()
    fun zoomBy(delta: Double)
    fun showPreview(st: RoutePreviewState?)
    fun radarsChanged()
    fun update(location: Location?, points: List<Waypoint>, target: Waypoint?, track: List<TrackPoint>, following: FollowState?)
    fun setHeadingUp(on: Boolean)
    fun setPanel(p: ManeuverOverlay.Panel?)
    fun release()
}

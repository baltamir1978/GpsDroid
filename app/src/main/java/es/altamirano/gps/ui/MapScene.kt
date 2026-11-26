package es.altamirano.gps.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import es.altamirano.gps.core.MapLayer
import es.altamirano.gps.core.MapStyle
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.core.FollowRoute
import es.altamirano.gps.core.FollowStatus
import es.altamirano.gps.data.Tiles
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.hypot

/**
 * Lo que se pinta encima de las teselas, igual en el móvil y en el coche: rótulos del híbrido, track
 * abierto, track que se graba, puntos guardados (con la línea al destino) y la posición.
 */
class MapScene(context: Context, density: Float) {
    // Los rótulos del híbrido: una segunda capa de teselas (PNG transparente) sobre la ortofoto,
    // debajo de los tracks y la posición. Mientras carga no pinta nada (de serie, una cuadrícula gris
    // que taparía la foto).
    val labels = TilesOverlay(MapTileProviderBasic(context, Tiles.sources.getValue(MapLayer.LABELS)), context).apply {
        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
        loadingLineColor = android.graphics.Color.TRANSPARENT
    }
    val opened = trackLine(MapColors.opened.toArgb(), 5f * density)
    val recording = trackLine(MapColors.recording.toArgb(), 4f * density)
    // El track que se sigue: lo que queda, fuerte; lo andado, claro; y, si estás fuera, una línea
    // discontinua hasta el punto más cercano del track (en línea recta: por dónde volver).
    val followDone = trackLine(MapColors.followDone.toArgb(), 6f * density)
    val followAhead = trackLine(MapColors.follow.toArgb(), 6f * density)
    val followGap = trackLine(MapColors.offTrack.toArgb(), 3f * density).apply {
        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
    }
    val points = WaypointsOverlay(density)
    val position = PositionOverlay(density)

    fun install(map: MapView) {
        map.overlays += opened
        map.overlays += followDone
        map.overlays += followAhead
        map.overlays += followGap
        map.overlays += recording
        map.overlays += points
        map.overlays += position
    }

    fun applyStyle(map: MapView, style: MapStyle) {
        map.setTileSource(Tiles.sources.getValue(style.base))
        map.maxZoomLevel = style.maxZoom.toDouble()
        if (map.zoomLevelDouble > style.maxZoom) map.controller.setZoom(style.maxZoom.toDouble())
        val wantLabels = style.overlay == MapLayer.LABELS
        if (wantLabels && labels !in map.overlays) map.overlays.add(0, labels)
        if (!wantLabels) map.overlays.remove(labels)
        map.invalidate()
    }

    fun setOnline(map: MapView, online: Boolean) { map.setUseDataConnection(online); labels.setUseDataConnection(online) }

    /** De noche (en el coche) las teselas se oscurecen: un mapa blanco deslumbra. */
    fun setDim(map: MapView, dim: Boolean) {
        val f = if (dim) ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.55f, 0.55f, 0.6f, 1f) }) else null
        map.overlayManager.tilesOverlay.setColorFilter(f)
        labels.setColorFilter(f)
    }

    fun setTrack(line: Polyline, pts: List<TrackPoint>) = line.setPoints(pts.map { GeoPoint(it.lat, it.lon) })

    private var followed: FollowRoute? = null
    private var followedSplit = -1

    /** El track que se sigue, partido por donde vas. Sólo se rehace al pasar de tramo o cambiar de track. */
    fun setFollow(st: FollowState?, location: Location?) {
        val route = st?.route
        val fix = st?.fix
        if (route == null) {
            followed = null; followedSplit = -1
            followDone.setPoints(emptyList()); followAhead.setPoints(emptyList()); followGap.setPoints(emptyList())
            return
        }
        // Mientras no has llegado al track, todo está por andar.
        val split = if (fix == null || fix.status == FollowStatus.APPROACHING) 0 else fix.match.segment + 1
        if (route !== followed || split != followedSplit) {
            followed = route; followedSplit = split
            val all = route.points.map { GeoPoint(it.lat, it.lon) }
            val here = fix?.takeIf { split > 0 }?.let { GeoPoint(it.match.lat, it.match.lon) }
            followDone.setPoints(if (here == null) emptyList() else all.take(split) + here)
            followAhead.setPoints(if (here == null) all else listOf(here) + all.drop(split))
        }
        val loc = location?.takeIf { Gps.isFresh(it) }
        val away = fix != null && loc != null && fix.status in setOf(FollowStatus.OFF_TRACK, FollowStatus.APPROACHING)
        followGap.setPoints(if (away) listOf(GeoPoint(loc!!.latitude, loc.longitude), GeoPoint(fix!!.match.lat, fix.match.lon)) else emptyList())
    }

    /** Si no está en el mapa, `MapView.onDetach` no lo suelta. */
    fun release(map: MapView) {
        if (labels !in map.overlays) labels.onDetach(map)
        map.onDetach()
    }
}

fun trackLine(color: Int, width: Float) = Polyline().apply {
    outlinePaint.color = color
    outlinePaint.strokeWidth = width
    outlinePaint.strokeCap = Paint.Cap.ROUND
    outlinePaint.strokeJoin = Paint.Join.ROUND
    outlinePaint.isAntiAlias = true
    // Sin globo al tocarla.
    infoWindow = null
}

/** Tu posición: el círculo de precisión, el punto y, si te mueves, una flecha hacia donde vas. */
class PositionOverlay(private val density: Float) : Overlay() {
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

/**
 * Los puntos guardados: una chincheta con su nombre debajo. El destino va en otro color y, si hay
 * posición, con una línea discontinua desde ella (en línea recta: no es una ruta). Tocar uno llama a
 * [onTap]; mantener pulsado el mapa fuera de ellos, a [onLongPress].
 */
class WaypointsOverlay(private val density: Float) : Overlay() {
    var points: List<Waypoint> = emptyList()
    var target: Waypoint? = null
    var from: Pair<Double, Double>? = null
    var onTap: ((Waypoint) -> Unit)? = null
    var onLongPress: ((GeoPoint) -> Unit)? = null

    private val pin = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val textHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true; style = Paint.Style.STROKE; color = android.graphics.Color.WHITE
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val pt = android.graphics.Point()
    private val pt2 = android.graphics.Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val t = target
        val f = from
        if (t != null && f != null) {
            proj.toPixels(GeoPoint(f.first, f.second), pt)
            proj.toPixels(GeoPoint(t.lat, t.lon), pt2)
            line.color = MapColors.target.toArgb()
            line.strokeWidth = 3f * density
            line.pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
            c.drawLine(pt.x.toFloat(), pt.y.toFloat(), pt2.x.toFloat(), pt2.y.toFloat(), line)
        }
        text.textSize = 12f * density
        textHalo.textSize = 12f * density
        textHalo.strokeWidth = 3f * density
        // El destino el último, para que quede encima.
        for (w in points.sortedBy { it.id == t?.id }) {
            proj.toPixels(GeoPoint(w.lat, w.lon), pt)
            val x = pt.x.toFloat(); val y = pt.y.toFloat()
            val isTarget = w.id == t?.id
            val r = (if (isTarget) 10f else 8f) * density
            // La punta de la chincheta en el sitio exacto.
            path.reset()
            path.moveTo(x, y)
            path.lineTo(x - r * 0.75f, y - r * 1.4f)
            path.lineTo(x + r * 0.75f, y - r * 1.4f)
            path.close()
            pin.color = if (isTarget) MapColors.target.toArgb() else MapColors.waypoint.toArgb()
            c.drawPath(path, pin)
            c.drawCircle(x, y - r * 2f, r, pin)
            pinRing.strokeWidth = 2f * density
            c.drawCircle(x, y - r * 2f, r, pinRing)
            c.drawCircle(x, y - r * 2f, r * 0.38f, dot)
            text.color = pin.color
            c.drawText(w.name, x, y + 14f * density, textHalo)
            c.drawText(w.name, x, y + 14f * density, text)
        }
    }

    /** El punto bajo el dedo (la cabeza de la chincheta), o null. */
    fun hit(mapView: MapView, x: Float, y: Float): Waypoint? {
        val proj = mapView.projection
        val r = 22f * density
        return points.map { w -> proj.toPixels(GeoPoint(w.lat, w.lon), pt); w to hypot(pt.x - x, pt.y - 16f * density - y) }
            .filter { it.second < r }.minByOrNull { it.second }?.first
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val w = hit(mapView, e.x, e.y) ?: return false
        onTap?.invoke(w)
        return true
    }

    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        val cb = onLongPress ?: return false
        hit(mapView, e.x, e.y)?.let { w -> onTap?.invoke(w); return true }
        val g = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        cb(GeoPoint(g.latitude, g.longitude))
        return true
    }
}

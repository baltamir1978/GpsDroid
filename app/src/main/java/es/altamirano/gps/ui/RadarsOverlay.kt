package es.altamirano.gps.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.os.SystemClock
import es.altamirano.gps.core.Radar
import es.altamirano.gps.core.RadarKind
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Gps
import es.altamirano.gps.core.TravelMode
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Los radares en el mapa, como una señal: círculo blanco con borde rojo (naranja los móviles) y el
 * límite dentro, o una cámara si no se sabe; los tramos, además, con una línea discontinua de
 * principio a fin. Sólo yendo en coche: a más de 20 km/h (y dos minutos después, para que no
 * parpadeen en un semáforo) o con una ruta en coche. Sólo desde z11: más lejos serían una nube.
 */
class RadarsOverlay(private val density: Float) : Overlay() {
    /** Los radares que se pueden enseñar (ya filtrados por las clases elegidas y la fecha). */
    var radars: List<Radar> = emptyList()
    private var visibleUntil = 0L
    var visible = false
        private set

    /** Con cada posición: decide si se enseñan. */
    fun update(loc: Location?, following: FollowState?) {
        val now = SystemClock.elapsedRealtime()
        val fast = loc != null && Gps.isFresh(loc) && loc.hasSpeed() && loc.speed * 3.6f > SHOW_KMH
        if (fast) visibleUntil = now + KEEP_MS
        visible = following?.nav?.mode == TravelMode.CAR || now < visibleUntil
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFF212121.toInt() }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF212121.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pt = android.graphics.Point()
    private val pt2 = android.graphics.Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !visible || mapView.zoomLevelDouble < MIN_ZOOM || radars.isEmpty()) return
        val box = mapView.boundingBox
        val mLat = (box.latNorth - box.latSouth) * 0.1; val mLon = (box.lonEast - box.lonWest) * 0.1
        val proj = mapView.projection
        line.strokeWidth = 3f * density
        line.pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        for (rd in radars) {
            val inBox = rd.lat in box.latSouth - mLat..box.latNorth + mLat && rd.lon in box.lonWest - mLon..box.lonEast + mLon
            val endInBox = rd.endLat != null && rd.endLon != null &&
                rd.endLat in box.latSouth - mLat..box.latNorth + mLat && rd.endLon in box.lonWest - mLon..box.lonEast + mLon
            if (!inBox && !endInBox) continue
            val color = colorOf(rd)
            proj.toPixels(GeoPoint(rd.lat, rd.lon), pt)
            if (rd.isStretch && rd.endLat != null && rd.endLon != null) {
                proj.toPixels(GeoPoint(rd.endLat, rd.endLon), pt2)
                line.color = color
                c.drawLine(pt.x.toFloat(), pt.y.toFloat(), pt2.x.toFloat(), pt2.y.toFloat(), line)
            }
            // Los tramos de radar móvil son sólo la línea (son muchos y casi nunca hay radar).
            if (rd.kind == RadarKind.MOBILE_STRETCH || !inBox) continue
            val x = pt.x.toFloat(); val y = pt.y.toFloat()
            // Derecha aunque el mapa esté girado (el coche, con la dirección arriba).
            c.save(); c.rotate(-mapView.mapOrientation, x, y)
            paintRadar(c, x, y, rd)
            c.restore()
        }
    }

    /** El color del radar: naranja los móviles, rojo los demás. */
    fun colorOf(rd: Radar) = if (rd.kind == RadarKind.MOBILE || rd.kind == RadarKind.MOBILE_STRETCH) 0xFFEF6C00.toInt() else 0xFFD32F2F.toInt()

    /**
     * Una señal de radar (o el semáforo) centrada en ([x], [y]), sin girar. También la usa el mapa
     * vectorial del coche para hacer sus iconos. Cabe en un cuadrado de 28 dp.
     */
    fun paintRadar(c: Canvas, x: Float, y: Float, rd: Radar) {
        val r = 11f * density
        val color = colorOf(rd)
        if (rd.kind == RadarKind.RED_LIGHT) { drawTrafficLight(c, x, y); return }
        fill.color = android.graphics.Color.WHITE
        ring.color = color
        ring.strokeWidth = 3.5f * density
        c.drawCircle(x, y, r, fill)
        c.drawCircle(x, y, r - 1.5f * density, ring)
        val limit = rd.maxspeed
        if (limit != null) {
            text.textSize = (if (limit >= 100) 9f else 10.5f) * density
            c.drawText(limit.toString(), x, y + text.textSize * 0.36f, text)
        } else {
            // Una cámara: cuerpo y objetivo.
            c.drawRoundRect(x - 5f * density, y - 3.5f * density, x + 5f * density, y + 3.5f * density, 1.5f * density, 1.5f * density, glyph)
            fill.color = android.graphics.Color.WHITE
            c.drawCircle(x, y, 2f * density, fill)
        }
    }

    private val housing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263238.toInt() }
    private val lamp = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Las cámaras de semáforo: un semáforo pequeño (rojo, ámbar y verde) sobre una placa blanca. */
    private fun drawTrafficLight(c: Canvas, x: Float, y: Float) {
        val d = density
        c.drawRoundRect(x - 8f * d, y - 13f * d, x + 8f * d, y + 13f * d, 4f * d, 4f * d, fill)
        ring.color = 0xFF263238.toInt(); ring.strokeWidth = 1.5f * d
        c.drawRoundRect(x - 8f * d, y - 13f * d, x + 8f * d, y + 13f * d, 4f * d, 4f * d, ring)
        c.drawRoundRect(x - 5f * d, y - 10.5f * d, x + 5f * d, y + 10.5f * d, 2.5f * d, 2.5f * d, housing)
        listOf(0xFFE53935.toInt() to -6.5f, 0xFFFFB300.toInt() to 0f, 0xFF43A047.toInt() to 6.5f).forEach { (col, dy) ->
            lamp.color = col
            c.drawCircle(x, y + dy * d, 2.6f * d, lamp)
        }
    }

    private companion object {
        const val SHOW_KMH = 20f
        const val KEEP_MS = 120_000L
        const val MIN_ZOOM = 11.0
    }
}

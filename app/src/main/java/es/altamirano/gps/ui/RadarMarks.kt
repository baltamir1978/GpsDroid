package es.altamirano.gps.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.os.SystemClock
import es.altamirano.gps.core.Radar
import es.altamirano.gps.core.RadarKind
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Gps
import es.altamirano.gps.core.TravelMode

/**
 * Los radares en el mapa, como una señal: círculo blanco con borde rojo (naranja los móviles) y el
 * límite dentro, o una cámara si no se sabe (los tramos, además, con una línea discontinua: la pinta
 * `GpsLayers`). Aquí se decide cuándo se ven —sólo yendo en coche: a más de 20 km/h (y dos minutos
 * después, para que no parpadeen en un semáforo) o con una ruta en coche— y se dibuja cada señal para
 * hacer su icono del mapa.
 */
class RadarMarks(private val density: Float) {
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

    /** El color del radar: naranja los móviles, rojo los demás. */
    fun colorOf(rd: Radar) = if (rd.kind == RadarKind.MOBILE || rd.kind == RadarKind.MOBILE_STRETCH) MOBILE_COLOR else FIXED_COLOR

    /**
     * Una señal de radar (o el semáforo) centrada en ([x], [y]), sin girar: con ella se hacen los
     * iconos del mapa. Cabe en un cuadrado de 28 dp.
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

    companion object {
        const val MOBILE_COLOR = 0xFFEF6C00.toInt()
        const val FIXED_COLOR = 0xFFD32F2F.toInt()
        private const val SHOW_KMH = 20f
        private const val KEEP_MS = 120_000L
    }
}

/** Los radares que se pueden enseñar, según los ajustes (clases elegidas, avisos encendidos y la fecha de hoy). */
fun visibleRadars(context: android.content.Context): List<Radar> {
    val s = es.altamirano.gps.data.Radars.settings(context)
    val today = java.time.LocalDate.now().toString()
    return if (!s.enabled) emptyList()
        else es.altamirano.gps.data.Radars.list(context).filter { it.kind in s.kinds && it.validOn(today) }
}

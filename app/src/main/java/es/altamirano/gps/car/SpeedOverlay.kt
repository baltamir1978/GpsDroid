package es.altamirano.gps.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * La velocidad, en un círculo pequeño en la esquina de abajo a la izquierda de lo que se ve del mapa
 * (lo que no tapan las tarjetas del coche). Blanco con el número; rojo si hay un radar cerca con
 * límite conocido y vas por encima.
 */
class SpeedOverlay(private val density: Float) : Overlay() {
    var speedKmh: Int? = null
    var limitKmh: Int? = null
    /** Lo visible del mapa en el coche (sin las tarjetas); null = todo. */
    var area: Rect? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        speedKmh ?: return
        // En la esquina de la pantalla, no del mapa: sin el giro del mapa (como la escala de osmdroid).
        val pj = mapView.projection
        pj.save(c, false, false)
        try { paint(c, mapView.width, mapView.height) } finally { pj.restore(c, false) }
    }

    /** Pinta la velocidad en un lienzo sin girar de [w]×[h] (también el del mapa vectorial). */
    fun paint(c: Canvas, w: Int, h: Int) {
        val v = speedKmh ?: return
        val over = limitKmh?.let { v > it } == true
        val r = 30f * density
        val a = area ?: Rect(0, 0, w, h)
        // Abajo a la izquierda, separado del borde; encima de la escala de osmdroid no hay nada ahí.
        val cx = a.left + 16f * density + r
        val cy = a.bottom - 16f * density - r
        fill.color = if (over) 0xFFC62828.toInt() else 0xF2FFFFFF.toInt()
        ring.color = if (over) 0xFFFFFFFF.toInt() else 0xFF37474F.toInt()
        ring.strokeWidth = 3f * density
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        big.color = if (over) 0xFFFFFFFF.toInt() else 0xFF212121.toInt()
        big.textSize = 22f * density
        small.color = big.color
        small.textSize = 9f * density
        c.drawText(v.toString(), cx, cy + 4f * density, big)
        c.drawText("km/h", cx, cy + 16f * density, small)
    }
}

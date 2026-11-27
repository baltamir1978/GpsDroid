package es.altamirano.gps.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * La tarjeta de la maniobra (o del destino) pintada en el propio mapa del coche, arriba a la
 * izquierda de lo visible: pequeña, oscura y semitransparente. Antes era la tarjeta de Android Auto
 * (`setNavigationInfo`), que no deja elegir tamaño ni transparencia y dejaba una esquina negra
 * encima de la esquina redondeada del mapa (Bruno, 27-nov-2026).
 */
class ManeuverOverlay(private val density: Float) : Overlay() {
    /** Lo que se enseña; null = nada. */
    data class Panel(val icon: Bitmap?, val big: String, val line: String?, val small: String?)

    var panel: Panel? = null
    /** Lo visible del mapa en el coche (sin los botones); null = todo. */
    var area: Rect? = null

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND }
    private val bigP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val lineP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCFD8DC.toInt() }
    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private val dst = RectF()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        panel ?: return
        // En la esquina de la pantalla, sin el giro del mapa.
        val pj = mapView.projection
        pj.save(c, false, false)
        try { paint(c, mapView.width, mapView.height) } finally { pj.restore(c, false) }
    }

    /** Pinta la tarjeta en un lienzo sin girar de [w]×[h] (también el del mapa vectorial). */
    fun paint(c: Canvas, w: Int, h: Int) {
        val p = panel ?: return
        val d = density
        val a = area ?: Rect(0, 0, w, h)
        val pad = 10f * d
        val iconS = if (p.icon != null) 46f * d else 0f
        val gap = if (p.icon != null) 10f * d else 0f
        bigP.textSize = 24f * d; lineP.textSize = 15f * d; smallP.textSize = 13f * d
        // Como mucho 260 dp de ancho o el 45 % de lo visible: que tape lo menos posible.
        val maxW = minOf(260f * d, a.width() * 0.45f)
        val textW = maxW - 2 * pad - iconS - gap
        val big = TextUtils.ellipsize(p.big, bigP, textW, TextUtils.TruncateAt.END).toString()
        val line = p.line?.let { TextUtils.ellipsize(it, lineP, textW, TextUtils.TruncateAt.END).toString() }
        val small = p.small?.let { TextUtils.ellipsize(it, smallP, textW, TextUtils.TruncateAt.END).toString() }
        val w = listOfNotNull(bigP.measureText(big), line?.let { lineP.measureText(it) }, small?.let { smallP.measureText(it) }).max()
        var h = bigP.textSize * 1.15f
        if (line != null) h += lineP.textSize * 1.3f
        if (small != null) h += smallP.textSize * 1.35f
        val contentH = maxOf(h, iconS)
        // Separada del borde: la esquina del mapa en el coche es redondeada.
        val left = a.left + 14f * d; val top = a.top + 14f * d
        box.set(left, top, left + 2 * pad + iconS + gap + w, top + 2 * pad + contentH)
        c.drawRoundRect(box, 14f * d, 14f * d, bg)
        p.icon?.let {
            val iy = top + pad + (contentH - iconS) / 2
            dst.set(left + pad, iy, left + pad + iconS, iy + iconS)
            c.drawBitmap(it, null, dst, iconP)
        }
        val tx = left + pad + iconS + gap
        var y = top + pad + (contentH - h) / 2 + bigP.textSize * 0.95f
        c.drawText(big, tx, y, bigP)
        if (line != null) { y += lineP.textSize * 1.3f; c.drawText(line, tx, y, lineP) }
        if (small != null) { y += smallP.textSize * 1.35f; c.drawText(small, tx, y, smallP) }
    }

    companion object {
        /** Gris azulado oscuro al 75 %: se ve el mapa debajo y el texto blanco se lee con sol. */
        const val BACKGROUND = 0xBF1E2A33.toInt()
    }
}

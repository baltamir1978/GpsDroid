package es.altamirano.gps.data

import es.altamirano.gps.core.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorTest {
    /** La franja del camino: un cuadrado cada medio margen, también en tramos largos sin puntos. */
    @Test fun pasilloDelCamino() {
        // 10 km hacia el norte con sólo dos puntos: tienen que salir cuadrados por el medio.
        val pts = listOf(TrackPoint(40.0, -3.7, null), TrackPoint(40.0 + 10_000 / 111_320.0, -3.7, null))
        val g = Tiles.corridor(pts, 500.0)
        assertEquals(41, g.polygons().size)
        // Cada cuadrado, ±500 m: 1 km de lado en latitud.
        val ring = g.polygons()[0].coordinates()[0]
        assertEquals(1000.0 / 111_320.0, ring.maxOf { it.latitude() } - ring.minOf { it.latitude() }, 1e-9)
        assertTrue(ring.first() == ring.last())
    }
}

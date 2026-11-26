package es.altamirano.gps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Radares de la DGT: leer su DATEX II y avisar de los que hay en la ruta. */
class RadarTest {
    @Test fun leeElDatexDeLaDgt() {
        // Un tramo y una cabina sacados tal cual del fichero de la DGT (noviembre de 2026).
        val xml = javaClass.classLoader!!.getResource("radares-muestra.xml")!!.readText()
        val list = RadarList.parseDatex(xml)
        assertEquals(2, list.size)
        val tramo = list.first { it.section }
        assertEquals("Z-40", tramo.road)
        assertEquals(41.6088, tramo.lat, 1e-4)      // el principio es el «from»
        assertEquals(41.6192, tramo.endLat!!, 1e-4)
        val cabina = list.first { !it.section }
        assertEquals("A-2", cabina.road)
        assertEquals(41.30326, cabina.lat, 1e-5)
        // Ida y vuelta por el formato que se guarda en el móvil (coordenadas con 6 decimales).
        val back = RadarList.fromCsv(RadarList.toCsv(list))
        assertEquals(list.map { it.section to it.road }, back.map { it.section to it.road })
        back.zip(list).forEach { (b, l) -> assertEquals(l.lat, b.lat, 1e-6); assertEquals(l.endLat ?: 0.0, b.endLat ?: 0.0, 1e-6) }
    }

    private val lat0 = 40.5
    private val lon0 = -3.7
    private val mLat = 111_195.0
    private val mLon = 111_195.0 * cos(Math.toRadians(lat0))
    private fun at(n: Double, e: Double = 0.0) = TrackPoint(lat0 + n / mLat, lon0 + e / mLon)

    @Test fun avisaDeLosRadaresDeLaRuta() {
        // 10 km al norte; un radar fijo a 3 km, otro a 300 m al este (otra carretera) y un tramo de 6 a 8 km.
        val route = FollowRoute((0..100).map { at(it * 100.0) })
        val radars = listOf(
            Radar(false, at(3000.0).lat, at(3000.0).lon, "A-1"),
            Radar(false, at(5000.0, 300.0).lat, at(5000.0, 300.0).lon, "M-40"),
            Radar(true, at(6000.0).lat, at(6000.0).lon, "A-1", at(8000.0).lat, at(8000.0).lon),
            // El mismo tramo en sentido contrario: no es de esta ruta.
            Radar(true, at(8000.0, 10.0).lat, at(8000.0, 10.0).lon, "A-1", at(6000.0, 10.0).lat, at(6000.0, 10.0).lon),
        )
        val g = RadarGuide(route, radars)
        assertEquals(2, g.count)
        assertNull(g.update(2000.0).first)
        val (say, show) = g.update(2510.0)
        assertEquals("Radar a 500 metros", say!!.spoken())
        assertEquals("Radar a 490 m · A-1", show!!.line())
        // Una sola vez por radar, aunque se siga viendo.
        val again = g.update(2700.0)
        assertNull(again.first)
        assertNotNull(again.second)
        assertNull(g.update(3200.0).second)
        // El tramo: aviso antes y al entrar; dentro se enseña hasta el final.
        assertTrue(g.update(5600.0).first!!.spoken().startsWith("Tramo de radar de velocidad media a 400"))
        assertEquals("Entras en un tramo de radar de velocidad media", g.update(6050.0).first!!.spoken())
        assertEquals("En tramo de radar de velocidad media · A-1", g.update(7500.0).second!!.line())
        assertNull(g.update(8100.0).second)
    }
}

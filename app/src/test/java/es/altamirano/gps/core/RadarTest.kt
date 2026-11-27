package es.altamirano.gps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.cos

/** Radares: leer el GeoJSON de Radares Anunciados y el DATEX de la DGT, y avisar de los de la ruta. */
class RadarTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun reconoceLaListaDeLa0100() {
        assertTrue(RadarList.isLegacyCsv("F;"))
        assertTrue(RadarList.isLegacyCsv("T;"))
        // La de ahora empieza por la clase: no se puede confundir (hasta la 0.20.0 se borraba).
        val nueva = RadarList.toCsv(listOf(Radar(RadarKind.FIXED, 40.0, -3.0, "M-30")))
        assertFalse(RadarList.isLegacyCsv(nueva.take(2)))
        assertFalse(RadarList.isLegacyCsv(null))
        assertFalse(RadarList.isLegacyCsv(""))
    }

    @Test fun leeElFeedDeRadaresAnunciados() {
        // Uno de cada clase sacado tal cual del feed (noviembre de 2026).
        val list = RadarList.parseFeed(resource("radares-feed-muestra.geojson"))
        // Fuera: los extremos «-from» del tramo (va la línea), las multas antiguas y las notas sin confirmar.
        assertEquals(listOf(RadarKind.FIXED, RadarKind.SECTION, RadarKind.MOBILE_STRETCH, RadarKind.SECTION, RadarKind.SECTION,
            RadarKind.FIXED, RadarKind.MOBILE).sorted(), list.map { it.kind }.sorted())
        val dgtTramo = list.first { it.kind == RadarKind.SECTION && it.road == "Z-40" }
        assertEquals(41.6088, dgtTramo.lat, 1e-4)
        assertEquals(41.6192, dgtTramo.endLat!!, 1e-4)
        val osm = list.first { it.kind == RadarKind.SECTION && it.bearing != null }
        assertNotNull(osm.maxspeed)
        val movil = list.first { it.kind == RadarKind.MOBILE }
        assertEquals("2026-11-25", movil.validFrom)
        assertTrue(movil.validOn("2026-11-25"))
        assertTrue(!movil.validOn("2026-11-26"))
        // El nombre de la carretera sale del nombre si no viene aparte.
        assertEquals("A-2", list.first { it.kind == RadarKind.FIXED && it.maxspeed == null }.road)
        // Ida y vuelta por el formato que se guarda en el móvil.
        val back = RadarList.fromCsv(RadarList.toCsv(list))
        assertEquals(list.map { Triple(it.kind, it.road, it.maxspeed) }, back.map { Triple(it.kind, it.road, it.maxspeed) })
        assertEquals(list.map { it.validTo }, back.map { it.validTo })
    }

    @Test fun leeElDatexDeLaDgtYLaListaVieja() {
        val list = RadarList.parseDatex(resource("radares-muestra.xml"))
        assertEquals(2, list.size)
        val tramo = list.first { it.kind == RadarKind.SECTION }
        assertEquals("Z-40", tramo.road)
        assertEquals(41.6088, tramo.lat, 1e-4)      // el principio es el «from»
        assertEquals("A-2", list.first { it.kind == RadarKind.FIXED }.road)
        // La copia guardada por la 0.10.0 («F»/«T» y seis campos) se sigue leyendo.
        val old = RadarList.fromCsv("F;41.303260;-1.944880;;;A-2\nT;41.608800;-0.915697;41.619200;-0.949600;Z-40")
        assertEquals(listOf(RadarKind.FIXED, RadarKind.SECTION), old.map { it.kind })
    }

    @Test fun leeLasCamarasDeSemaforoDeOpenStreetMap() {
        // Una relación «enforcement=traffic_signals» con sus nodos y un nodo suelto, tal cual de Overpass.
        val list = RadarList.parseOverpassRedLights(resource("semaforos-muestra.json"))
        assertEquals(2, list.size)
        assertTrue(list.all { it.kind == RadarKind.RED_LIGHT })
        // La de la relación trae el sentido (de «from» al semáforo).
        assertNotNull(list.first().bearing)
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
            Radar(RadarKind.FIXED, at(3000.0).lat, at(3000.0).lon, "A-1", maxspeed = 100),
            Radar(RadarKind.FIXED, at(5000.0, 300.0).lat, at(5000.0, 300.0).lon, "M-40"),
            Radar(RadarKind.SECTION, at(6000.0).lat, at(6000.0).lon, "A-1", at(8000.0).lat, at(8000.0).lon),
            // El mismo tramo en sentido contrario: no es de esta ruta.
            Radar(RadarKind.SECTION, at(8000.0, 10.0).lat, at(8000.0, 10.0).lon, "A-1", at(6000.0, 10.0).lat, at(6000.0, 10.0).lon),
        )
        val g = RadarGuide(route, radars)
        assertEquals(2, g.count)
        assertNull(g.update(2000.0).first)
        val (say, show) = g.update(2510.0)
        assertEquals("Radar a 500 metros, límite 100", say!!.spoken())
        assertEquals("Radar a 490 m · 100 km/h · A-1", show!!.line())
        val again = g.update(2700.0)
        assertNull(again.first)
        assertNotNull(again.second)
        assertNull(g.update(3200.0).second)
        assertTrue(g.update(5600.0).first!!.spoken().startsWith("Tramo de radar a 400"))
        assertEquals("Entras en un tramo de radar", g.update(6050.0).first!!.spoken())
        assertEquals("En tramo de radar · A-1", g.update(7500.0).second!!.line())
        assertNull(g.update(8100.0).second)
    }

    @Test fun distanciaClasesFechaYSentido() {
        val route = FollowRoute((0..100).map { at(it * 100.0) })
        val radars = listOf(
            Radar(RadarKind.FIXED, at(3000.0).lat, at(3000.0).lon, "A-1"),
            // Vigila a los que van hacia el sur (rumbo 180): no avisa yendo al norte.
            Radar(RadarKind.FIXED, at(4000.0).lat, at(4000.0).lon, "A-1", bearing = 180.0),
            Radar(RadarKind.FIXED, at(4500.0).lat, at(4500.0).lon, "A-1", bearing = 5.0),
            Radar(RadarKind.MOBILE, at(5000.0).lat, at(5000.0).lon, "A-1", validFrom = "2026-11-25", validTo = "2026-11-25"),
            // Los tramos de radar móvil valen en los dos sentidos.
            Radar(RadarKind.MOBILE_STRETCH, at(9000.0).lat, at(9000.0).lon, "A-1", at(7000.0).lat, at(7000.0).lon),
        )
        val all = setOf(RadarKind.FIXED, RadarKind.SECTION, RadarKind.MOBILE, RadarKind.MOBILE_STRETCH)
        assertEquals(4, RadarGuide(route, radars, kinds = all, today = "2026-11-25").count)
        assertEquals(3, RadarGuide(route, radars, kinds = all, today = "2026-11-26").count)
        assertEquals(2, RadarGuide(route, radars, today = "2026-11-26").count)   // sin tramos de móvil
        // A 1 km avisa antes.
        val far = RadarGuide(route, radars, warnM = 1000.0)
        assertNotNull(far.update(2010.0).first)
    }

    @Test fun camaraDeSemaforoA200Metros() {
        val route = FollowRoute((0..100).map { at(it * 100.0) })
        val g = RadarGuide(route, listOf(Radar(RadarKind.RED_LIGHT, at(3000.0).lat, at(3000.0).lon, "", bearing = 0.0)), warnM = 500.0)
        assertNull(g.update(2600.0).first)                       // a 400 m: todavía no
        assertEquals("Cámara de semáforo a 190 metros", g.update(2810.0).first!!.spoken())
    }
}

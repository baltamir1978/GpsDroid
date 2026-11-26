package es.altamirano.gps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos

/** Rutas de BRouter: leer su GeoJSON, las indicaciones en español y cuándo decirlas. */
class RouteTest {
    private val lat0 = 40.8
    private val lon0 = -3.9
    private val mLat = 111_195.0
    private val mLon = 111_195.0 * cos(Math.toRadians(lat0))
    private fun lat(n: Double) = lat0 + n / mLat
    private fun lon(e: Double) = lon0 + e / mLon

    /** 500 m al norte, giro a la derecha y 300 m al este; en BRouter, una rotonda al final. */
    private val json = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "properties": {
                "creator": "BRouter-1.7.8",
                "name": "brouter_trekking_0",
                "track-length": "800",
                "total-time": "640",
                "voicehints": [
                  [5,5,0,300.0,88],
                  [8,13,2,0.0,40]
                ],
                "messages": [
                  ["Longitude", "Latitude", "Elevation"],
                  ["-3900000", "40800000", "1000"]
                ],
                "times": [0,80,160]
              },
              "geometry": {
                "type": "LineString",
                "coordinates": [
                  ${(0..5).joinToString(",\n") { "[%.6f, %.6f, %d]".format(java.util.Locale.ROOT, lon(0.0), lat(it * 100.0), 1000 + it * 5) }},
                  ${(1..3).joinToString(",\n") { "[%.6f, %.6f, 1025]".format(java.util.Locale.ROOT, lon(it * 100.0), lat(500.0)) }}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test fun leeElGeoJsonDeBRouter() {
        val r = BRouterJson.parse(json)!!
        assertEquals(9, r.points.size)
        assertEquals(lat(500.0), r.points[5].lat, 1e-6)
        assertEquals(1025.0, r.points[5].ele!!, 0.01)
        assertEquals(640L, r.totalS)
        assertEquals(2, r.maneuvers.size)
        assertEquals(Maneuver.Kind.RIGHT, r.maneuvers[0].kind)
        assertEquals(Maneuver.Kind.ROUNDABOUT, r.maneuvers[1].kind)
        assertEquals("en la rotonda, toma la 2.ª salida", r.maneuvers[1].phrase())
        // Un error de BRouter no es una ruta.
        assertNull(BRouterJson.parse("datafile W5_N40.rd5 not found"))
        assertNull(BRouterJson.parse("""{"type": "FeatureCollection", "features": []}"""))
    }

    @Test fun avisaCadaGiroUnaVezPorEtapa() {
        val r = BRouterJson.parse(json)!!
        val g = RouteGuide(r, TravelMode.FOOT.announceM)   // 120, 40, 12 m
        assertNull(g.announce(0.0))
        assertEquals(500.0, g.next(0.0)!!.distanceM, 2.0)
        assertNull(g.announce(350.0))   // a 150 m: todavía no
        assertEquals("En 120 metros, gira a la derecha", g.announce(385.0))
        assertNull(g.announce(390.0))
        assertEquals("En 40 metros, gira a la derecha", g.announce(461.0))
        // Saltar de cerca a ya: sólo «ya».
        assertEquals("Gira a la derecha", g.announce(495.0))
        assertNull(g.announce(496.0))
        // Pasado el giro, lo siguiente es la rotonda.
        val n = g.next(520.0)!!
        assertEquals(Maneuver.Kind.ROUNDABOUT, n.maneuver.kind)
        assertEquals(280.0, n.distanceM, 3.0)
    }

    @Test fun dosGirosSeguidosSeDicenJuntos() {
        // Un giro a la izquierda y, 40 m después, otro a la derecha.
        val pts = (0..10).map { TrackPoint(lat(it * 20.0), lon0) }
        val g = RouteGuide(PlannedRoute(pts, listOf(Maneuver(5, Maneuver.Kind.LEFT), Maneuver(7, Maneuver.Kind.RIGHT)), null),
            TravelMode.FOOT.announceM)
        assertEquals("En 100 metros, gira a la izquierda y luego gira a la derecha", g.announce(0.0))
        assertEquals(Maneuver.Kind.RIGHT, g.next(110.0)!!.maneuver.kind)
        assertNull(g.next(150.0))
        assertNotNull(g.next(0.0)!!.then)
    }

    @Test fun seguirRectoNoSeAnuncia() {
        val pts = (0..10).map { TrackPoint(lat(it * 20.0), lon0) }
        val g = RouteGuide(PlannedRoute(pts, listOf(Maneuver(3, Maneuver.Kind.STRAIGHT), Maneuver(8, Maneuver.Kind.LEFT)), null),
            TravelMode.FOOT.announceM)
        assertEquals(Maneuver.Kind.LEFT, g.next(0.0)!!.maneuver.kind)
        assertEquals("Gira a la izquierda", g.announce(155.0))
    }

    @Test fun cuadrosDeDatos() {
        assertEquals("W5_N40", RouteTiles.nameAt(40.4168, -3.7038))   // Madrid
        assertEquals("E0_N40", RouteTiles.nameAt(41.39, 2.17))         // Barcelona
        assertEquals("W10_N40", RouteTiles.nameAt(43.36, -8.41))       // A Coruña
        assertEquals("W20_N25", RouteTiles.nameAt(28.1, -15.4))        // Las Palmas
        assertEquals("E10_S5", RouteTiles.nameAt(-0.5, 10.2))
        assertEquals("W5_N40", RouteTiles.nameAt(40.0, -5.0))          // esquina: el de su suroeste
        // Madrid → Sierra: un cuadro. Madrid → Barcelona: dos.
        assertEquals(listOf("W5_N40"), RouteTiles.between(40.4168 to -3.7038, 40.79 to -3.98))
        assertEquals(listOf("W5_N40", "E0_N40"), RouteTiles.between(40.4168 to -3.7038, 41.39 to 2.17))
    }

    @Test fun distanciasComoSeDicen() {
        assertEquals("10 metros", RouteGuide.spokenDistance(4.0))
        assertEquals("40 metros", RouteGuide.spokenDistance(38.0))
        assertEquals("160 metros", RouteGuide.spokenDistance(158.0))
        assertEquals("250 metros", RouteGuide.spokenDistance(260.0))
        assertEquals("1 kilómetro", RouteGuide.spokenDistance(990.0))
        assertEquals("1,5 kilómetros", RouteGuide.spokenDistance(1460.0))
        assertEquals("2 kilómetros", RouteGuide.spokenDistance(2010.0))
        assertEquals(TravelMode.CAR, TravelMode.fromKey("CAR"))
        assertEquals(TravelMode.FOOT, TravelMode.fromKey(null))
    }
}

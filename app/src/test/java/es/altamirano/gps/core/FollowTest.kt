package es.altamirano.gps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Seguir un track: dónde caes, cuánto queda, salirse y volver, llegar. */
class FollowTest {
    private val lat0 = 40.8
    private val lon0 = -3.9
    private val mLat = 111_195.0
    private val mLon = 111_195.0 * cos(Math.toRadians(lat0))

    /** Un punto a [north] y [east] metros del origen. */
    private fun at(north: Double, east: Double = 0.0, ele: Double? = null) = TrackPoint(lat0 + north / mLat, lon0 + east / mLon, ele)

    /** 1 km hacia el norte, un punto cada 100 m, subiendo 10 m en cada uno. */
    private val straight = (0..10).map { at(it * 100.0, ele = 1000.0 + it * 10) }

    @Test fun dondeCaesYCuantoQueda() {
        val r = FollowRoute(straight)
        assertEquals(1000.0, r.totalM, 2.0)
        val m = r.locate(at(450.0, 20.0).lat, at(450.0, 20.0).lon)!!
        assertEquals(20.0, m.offM, 1.0)
        assertEquals(450.0, m.alongM, 2.0)
        assertEquals(4, m.segment)
        assertEquals(1045.0, r.elevationAt(450.0)!!, 0.5)

        val f = TrackFollower(r)
        f.update(at(0.0).lat, at(0.0).lon, 5.0, 0.0)
        assertEquals(FollowStatus.ON_TRACK, f.status)
        f.update(at(450.0).lat, at(450.0).lon, 5.0, 450.0)
        val fix = f.last!!
        assertEquals(550.0, fix.remainingM, 3.0)
        // Del punto siguiente (500 m) al final: 50 m de subida y nada de bajada.
        assertEquals(50.0, fix.remainingAscentM!!, 0.1)
        assertEquals(0.0, fix.remainingDescentM!!, 0.1)
        // A 1 m/s, lo que queda son ~550 s.
        assertEquals(550.0, fix.remainingS!!.toDouble(), 10.0)
    }

    @Test fun salirseYVolverAvisanUnaVez() {
        val f = TrackFollower(FollowRoute(straight))
        assertNull(f.update(at(100.0).lat, at(100.0).lon, 5.0, 0.0))
        // Un solo salto lejos (ruido del GPS) no avisa; el segundo sí.
        assertNull(f.update(at(200.0, 70.0).lat, at(200.0, 70.0).lon, 5.0, 10.0))
        assertEquals(FollowEvent.LEFT_TRACK, f.update(at(210.0, 75.0).lat, at(210.0, 75.0).lon, 5.0, 20.0))
        assertEquals(FollowStatus.OFF_TRACK, f.status)
        // Volver queda justo al oeste, a 75 m (no a un punto anterior del mismo tramo recto).
        assertEquals("O", Direction.cardinal(f.last!!.bearingToTrack))
        assertEquals(75.0, f.last!!.match.offM, 1.0)
        assertEquals(210.0, f.last!!.match.alongM, 1.0)
        assertNull(f.update(at(220.0, 60.0).lat, at(220.0, 60.0).lon, 5.0, 30.0))
        assertEquals(FollowEvent.BACK_ON_TRACK, f.update(at(230.0, 5.0).lat, at(230.0, 5.0).lon, 5.0, 40.0))
        assertEquals(FollowStatus.ON_TRACK, f.status)
    }

    @Test fun lejosAlEmpezarNoEsSalirse() {
        val f = TrackFollower(FollowRoute(straight))
        assertNull(f.update(at(-500.0).lat, at(-500.0).lon, 5.0, 0.0))
        assertNull(f.update(at(-490.0).lat, at(-490.0).lon, 5.0, 10.0))
        assertEquals(FollowStatus.APPROACHING, f.status)
        assertEquals("N", Direction.cardinal(f.last!!.bearingToTrack))
        assertEquals(490.0, f.last!!.match.offM, 3.0)
        // Las posiciones malas no cuentan.
        assertNull(f.update(at(0.0).lat, at(0.0).lon, 80.0, 20.0))
        assertEquals(FollowStatus.APPROACHING, f.status)
        f.update(at(0.0).lat, at(0.0).lon, 5.0, 30.0)
        assertEquals(FollowStatus.ON_TRACK, f.status)
    }

    @Test fun idaYVueltaNoSaltaAlOtroLado() {
        // 1 km al norte y vuelta por el mismo camino.
        val track = (0..10).map { at(it * 100.0) } + (9 downTo 0).map { at(it * 100.0) }
        val f = TrackFollower(FollowRoute(track))
        var t = 0.0
        // A la ida, a 300 m: llevas 300, no 1700.
        for (n in 0..6) { f.update(at(n * 50.0).lat, at(n * 50.0).lon, 5.0, t); t += 50 }
        assertEquals(300.0, f.last!!.match.alongM, 3.0)
        assertNull(f.last!!.remainingAscentM)
        // Un poco apartado del camino, se cae en el punto de enfrente, no en el de donde ibas.
        f.update(at(320.0, 30.0).lat, at(320.0, 30.0).lon, 5.0, t)
        assertEquals(320.0, f.last!!.match.alongM, 1.0)
        assertEquals(30.0, f.last!!.match.offM, 1.0)
        // Hasta la vuelta y de nuevo a 300 m: ahora llevas 1700.
        for (n in 7..20) { f.update(at(n * 50.0).lat, at(n * 50.0).lon, 5.0, t); t += 50 }
        for (n in 19 downTo 6) { f.update(at(n * 50.0).lat, at(n * 50.0).lon, 5.0, t); t += 50 }
        assertEquals(1700.0, f.last!!.match.alongM, 3.0)
        assertEquals(300.0, f.last!!.remainingM, 3.0)
    }

    @Test fun circularLlegaAlFinalNoAlEmpezar() {
        // Un cuadrado de 400 m de lado que empieza y acaba en el mismo sitio.
        val track = listOf(at(0.0), at(400.0), at(400.0, 400.0), at(0.0, 400.0), at(0.0))
        val f = TrackFollower(FollowRoute(track))
        assertNull(f.update(at(0.0).lat, at(0.0).lon, 5.0, 0.0))
        assertEquals(FollowStatus.ON_TRACK, f.status)
        assertEquals(0.0, f.last!!.match.alongM, 1.0)
        var t = 0.0
        val walk = (1..8).map { at(it * 50.0) } + (1..8).map { at(400.0, it * 50.0) } +
            (7 downTo 0).map { at(it * 50.0, 400.0) } + (7 downTo 1).map { at(0.0, it * 50.0) }
        var arrived: FollowEvent? = null
        for (p in walk) { t += 50; f.update(p.lat, p.lon, 5.0, t)?.let { arrived = it } }
        assertNull(arrived)
        assertEquals(FollowEvent.ARRIVED, f.update(at(0.0, 10.0).lat, at(0.0, 10.0).lon, 5.0, t + 50))
        assertEquals(FollowStatus.ARRIVED, f.status)
        assertEquals(0.0, f.last!!.remainingM, 0.01)
    }

    @Test fun alRevesYAltitudRelativa() {
        val r = FollowRoute(straight).reversed()
        assertEquals(0.0, r.remainingAscentFrom(0), 0.1)
        assertEquals(100.0, r.remainingDescentFrom(0), 0.1)
        assertNotNull(r.locate(at(1000.0).lat, at(1000.0).lon))
        assertEquals(0.0, r.locate(at(1000.0).lat, at(1000.0).lon)!!.alongM, 1.0)

        assertEquals("230 m más arriba", Altitude.relative(1230.0, 1000.0))
        assertEquals("1.085 m más abajo", Altitude.relative(100.0, 1185.0))
        assertEquals("a tu altura", Altitude.relative(1004.0, 1000.0))
        assertNull(Altitude.relative(null, 1000.0))
        assertEquals("1.245 m", Altitude.format(1245.4))
        assertTrue(Altitude.relative(1000.0, null) == null)
    }
}

package es.altamirano.gps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las mismas comprobaciones que las pruebas de ZeroGPS (map_tiles, offline_zone, gps_gpx, track_recorder). */
class CoreTest {
    @Test fun mercatorIdaYVuelta() {
        for (z in listOf(5, 12, 17)) {
            val lat = 40.7856; val lon = -3.9925
            assertEquals(lon, MapTiles.tileXToLon(MapTiles.lonToTileX(lon, z), z), 1e-6)
            assertEquals(lat, MapTiles.tileYToLat(MapTiles.latToTileY(lat, z), z), 1e-4)
        }
        val z = 10
        assertEquals(0, MapTiles.tileAt(0.0, -180.0, z).x)
        assertEquals((1 shl z) - 1, MapTiles.tileAt(0.0, 179.999, z).x)
        assertTrue(MapTiles.tileAt(60.0, 0.0, z).y < MapTiles.tileAt(-60.0, 0.0, z).y)
        val m = MapTiles.metersPerPixel(40.4, 17)
        assertTrue(m > 0.8 && m < 1.0)
    }

    @Test fun urlsDeLasCapas() {
        val topo = MapLayer.TOPO.tileUrl(12, 2005, 1538)
        assertTrue(topo.contains("mapa-raster?") && topo.contains("layer=MTN"))
        assertTrue(topo.contains("tilematrix=12") && topo.contains("tilecol=2005") && topo.contains("tilerow=1538"))
        assertTrue(MapLayer.ORTHO.tileUrl(14, 1, 2).contains("pnoa-ma?") && MapLayer.ORTHO.tileUrl(14, 1, 2).contains("layer=OI.OrthoimageCoverage"))
        assertTrue(MapLayer.BASE.tileUrl(14, 1, 2).contains("layer=IGNBaseTodo"))
        assertEquals(MapLayer.ORTHO, MapLayer.fromKey("pnoa"))
        assertEquals(MapLayer.TOPO, MapLayer.fromKey("nada"))
        assertEquals(MapLayer.TOPO, MapLayer.BASE.next())
    }

    @Test fun zonas() {
        val b = OfflineZone.bboxAround(40.0, -3.7, 10.0)
        assertEquals(20.0 / 111.32, b.maxLat - b.minLat, 1e-6)
        assertTrue(b.maxLon - b.minLon > b.maxLat - b.minLat)

        val p = OfflineZone.plan(b, 10, 14, MapLayer.TOPO)
        assertEquals(p.perZoom.values.sum(), p.tiles)
        assertTrue(p.bytes > p.tiles * 20 * 1024)
        assertTrue(p.perZoom.getValue(14) > 2 * p.perZoom.getValue(13))
        val q = OfflineZone.plan(b, 1, 30, MapLayer.TOPO)
        assertEquals(OfflineZone.ZOOM_MIN, q.zmin); assertEquals(17, q.zmax)
        assertEquals(19, OfflineZone.plan(b, 1, 30, MapLayer.ORTHO).zmax)
        val t15 = OfflineZone.plan(b, 15, 15, MapLayer.TOPO)
        val o15 = OfflineZone.plan(b, 15, 15, MapLayer.ORTHO)
        val b15 = OfflineZone.plan(b, 15, 15, MapLayer.BASE)
        assertTrue(t15.tiles == o15.tiles && o15.tiles == b15.tiles)
        assertTrue(t15.bytes > o15.bytes && o15.bytes > b15.bytes)
        for ((_, box) in OfflineZone.PRESETS) {
            val pp = OfflineZone.plan(box, 5, 14, MapLayer.TOPO)
            assertTrue(pp.tiles in 1 until OfflineZone.MAX_TILES)
        }
        // Lo que pidió Bruno en ZeroGPS: los tres mapas a 5 km, de z5 al máximo, caben en el tope.
        val (tiles, _) = OfflineZone.plan(OfflineZone.bboxAround(40.78, -4.0, 5.0), 5, 19, MapLayer.entries)
        assertTrue(tiles in 30_000 until OfflineZone.MAX_TILES)
    }

    @Test fun formatos() {
        assertEquals("0", OfflineZone.formatCount(0))
        assertEquals("999", OfflineZone.formatCount(999))
        assertEquals("12.345", OfflineZone.formatCount(12345))
        assertEquals("1.234.567", OfflineZone.formatCount(1234567))
        assertEquals("512 KB", OfflineZone.formatBytes(512 * 1024))
        assertEquals("3,3 MB", OfflineZone.formatBytes(3 * 1024 * 1024 + 300 * 1024))
        assertEquals("250 MB", OfflineZone.formatBytes(250L * 1024 * 1024))
        assertEquals("1,5 GB", OfflineZone.formatBytes(1536L * 1024 * 1024))
        assertEquals("12:05", Geo.formatDuration(725))
        assertEquals("1:02:05", Geo.formatDuration(3725))
        assertEquals("850 m", Geo.formatDistance(850.0))
        assertEquals("12,3 km", Geo.formatDistance(12_340.0))
        assertEquals("40,41680° N  3,70380° O", Geo.formatLatLon(40.4168, -3.7038))
    }

    @Test fun gpxIdaYVuelta() {
        val t = Track("Peñalara & <Laguna>", listOf(
            TrackPoint(40.85, -3.95, 1820.0, "2026-11-22T10:00:00Z"),
            TrackPoint(40.851, -3.951, null, null),
        ))
        val back = Gpx.parse(Gpx.write(t))
        assertNotNull(back)
        assertEquals(t.name, back!!.name)
        assertEquals(2, back.points.size)
        assertEquals(40.85, back.points[0].lat, 1e-7)
        assertEquals(1820.0, back.points[0].ele!!, 1e-6)
        assertEquals("2026-11-22T10:00:00Z", back.points[0].time)
        assertNull(back.points[1].ele)
        assertNull(Gpx.parse("<html></html>"))
    }

    @Test fun gpxDeOtrasApps() {
        // Comillas simples, lon antes que lat, puntos sin cuerpo, dos segmentos y un waypoint con nombre.
        val text = """<?xml version='1.0'?><gpx version='1.1'><wpt lat='1' lon='2'><name>Fuente</name></wpt>
            <trk><trkseg><trkpt lon='-3.5' lat='40.5'/><trkpt lat="40.6" lon="-3.6"><ele>700</ele></trkpt></trkseg>
            <trkseg><trkpt lat="40.7" lon="-3.7"></trkpt></trkseg></trk></gpx>"""
        val t = Gpx.parse(text, "fichero")!!
        assertEquals(3, t.points.size)
        assertEquals(-3.5, t.points[0].lon, 1e-9)
        assertEquals(700.0, t.points[1].ele!!, 1e-9)
        assertEquals("Fuente", t.name)  // el primer <name> antes del primer punto… el del waypoint
        // Una ruta sin track.
        val r = Gpx.parse("""<gpx><rte><rtept lat="1" lon="2"/><rtept lat="1.1" lon="2.1"/></rte></gpx>""", "ruta")!!
        assertEquals(2, r.points.size); assertEquals("ruta", r.name)
    }

    @Test fun grabador() {
        val r = TrackRecorder()
        val base = TrackPoint(40.0, -3.0)
        assertTrue(r.offer(base, 5.0, 0.0))
        // Parado (1 m): no entra, ni a los 60 s (ruido).
        assertTrue(!r.offer(TrackPoint(40.000009, -3.0), 5.0, 60.0))
        // Mala precisión: fuera aunque se mueva.
        assertTrue(!r.offer(TrackPoint(40.001, -3.0), 80.0, 61.0))
        // 11 m: entra.
        assertTrue(r.offer(TrackPoint(40.0001, -3.0), 5.0, 62.0))
        assertEquals(11.1, r.lengthM, 0.2)
        // 3 m, al poco: no; a los 30 s, sí (despacio pero moviéndose).
        assertTrue(!r.offer(TrackPoint(40.000127, -3.0), 5.0, 70.0))
        assertTrue(r.offer(TrackPoint(40.000127, -3.0), 5.0, 93.0))
        assertEquals(3, r.track.size)
        r.reset(); assertEquals(0, r.track.size); assertEquals(0.0, r.lengthM, 0.0)
    }

    @Test fun desnivel() {
        val pts = listOf(700.0, 701.0, 699.0, 705.0, 704.0, 710.0, 702.0, 708.0).map { TrackPoint(0.0, 0.0, it) }
        // 700→705 (+5: bajar a 699 no llega al umbral), 705→710 (+5), 702→708 (+6).
        assertEquals(16.0, Geo.ascentM(pts), 1e-9)
    }
}

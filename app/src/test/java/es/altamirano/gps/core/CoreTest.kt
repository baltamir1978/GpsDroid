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

    @Test fun zoomConLaRuedaFijaElCursor() {
        // El ancla debe quedar a la misma distancia en píxeles del centro antes y después.
        val (cLat, cLon) = 40.4168 to -3.7038
        val (aLat, aLon) = 40.45 to -3.65
        for (d in listOf(0.5, -0.5, 1.0, -2.0)) {
            val z = 12.0
            val (nLat, nLon) = MapTiles.zoomAnchoredCenter(cLat, cLon, aLat, aLon, d)
            val k0 = Math.pow(2.0, z); val k1 = Math.pow(2.0, z + d)
            assertEquals((MapTiles.lonToTileX(aLon, 0) - MapTiles.lonToTileX(cLon, 0)) * k0,
                (MapTiles.lonToTileX(aLon, 0) - MapTiles.lonToTileX(nLon, 0)) * k1, 1e-6)
            assertEquals((MapTiles.latToTileY(aLat, 0) - MapTiles.latToTileY(cLat, 0)) * k0,
                (MapTiles.latToTileY(aLat, 0) - MapTiles.latToTileY(nLat, 0)) * k1, 1e-6)
        }
        // Con el ancla en el centro, el centro no se mueve.
        val (lat, lon) = MapTiles.zoomAnchoredCenter(cLat, cLon, cLat, cLon, 1.0)
        assertEquals(cLat, lat, 1e-9); assertEquals(cLon, lon, 1e-9)
    }

    @Test fun urlsDeLasCapas() {
        val topo = MapLayer.TOPO.tileUrl(12, 2005, 1538)
        assertTrue(topo.contains("mapa-raster?") && topo.contains("layer=MTN"))
        assertTrue(topo.contains("tilematrix=12") && topo.contains("tilecol=2005") && topo.contains("tilerow=1538"))
        assertTrue(MapLayer.ORTHO.tileUrl(14, 1, 2).contains("pnoa-ma?") && MapLayer.ORTHO.tileUrl(14, 1, 2).contains("layer=OI.OrthoimageCoverage"))
        assertTrue(MapLayer.ORTHO.tileUrl(14, 1, 2).contains("format=image/jpeg"))
        // La plantilla para MapLibre deja {z}/{x}/{y} en su sitio.
        val t = MapLayer.TOPO.urlTemplate()
        assertTrue(t.contains("tilematrix={z}") && t.contains("tilerow={y}") && t.contains("tilecol={x}"))
        assertTrue(!MapLayer.VECTOR.raster && MapLayer.TOPO.raster)
    }

    @Test fun estilosDelMapa() {
        // Las claves de la 0.2 (capas) siguen abriendo el mismo mapa.
        assertEquals(MapStyle.ORTHO, MapStyle.fromKey("pnoa"))
        assertEquals(MapStyle.TOPO, MapStyle.fromKey("nada"))
        assertEquals(MapStyle.HYBRID, MapStyle.ORTHO.next())
        assertEquals(MapStyle.TOPO, MapStyle.BASE.next())
        // El híbrido: la ortofoto con los nombres del vectorial; «base» (el antiguo IGN Base) es ahora el callejero vectorial.
        assertEquals(listOf(MapLayer.ORTHO, MapLayer.VECTOR), MapStyle.HYBRID.layers)
        assertEquals(19, MapStyle.HYBRID.maxZoom)
        assertEquals(MapStyle.BASE, MapStyle.fromKey("base"))
        assertEquals(listOf(MapLayer.VECTOR), MapStyle.BASE.layers)
        // Satélite + Híbrido: la ortofoto se baja una vez.
        assertEquals(listOf(MapLayer.ORTHO, MapLayer.VECTOR), MapStyle.layersOf(listOf(MapStyle.ORTHO, MapStyle.HYBRID)))
        assertEquals(MapLayer.entries.toList(), MapStyle.layersOf(MapStyle.entries).sortedBy { it.ordinal })
    }

    @Test fun fichaDeUnTrack() {
        // 1 km hacia el norte en 10 puntos (0,001° ≈ 111 m), subiendo 10 m por punto, uno cada 60 s,
        // con una parada de 10 minutos a mitad y vuelta a bajar 20 m al final.
        val pts = ArrayList<TrackPoint>()
        var t = java.time.Instant.parse("2026-11-24T08:00:00Z")
        for (i in 0..9) {
            pts += TrackPoint(40.0 + i * 0.001, -3.7, 1000.0 + i * 10, t.toString())
            t = t.plusSeconds(if (i == 4) 600 else 60)
        }
        pts += TrackPoint(40.0 + 9 * 0.001, -3.7005, 1070.0, t.toString())
        val s = TrackStats.of(pts)
        assertEquals(1001.0 + 42.6, s.distanceM, 5.0)
        assertEquals(9 * 60L + 600, s.durationS)
        assertEquals(9 * 60L, s.movingS)             // la parada no cuenta
        assertEquals(90.0, s.ascentM, 0.01)
        assertEquals(20.0, s.descentM, 0.01)
        assertEquals(1000.0, s.minEleM!!, 0.0)
        assertEquals(1090.0, s.maxEleM!!, 0.0)
        assertEquals(1070.0, s.endEleM!!, 0.0)
        assertEquals(pts.size, s.profile.size)
        assertEquals(s.distanceM, s.profile.last().distanceM, 1e-9)
        assertEquals(1043.0 / 540 * 3.6, s.movingSpeedKmh!!, 0.2)

        // Sin horas ni altitud (una ruta importada): lo que no se sabe sale null.
        val r = TrackStats.of(listOf(TrackPoint(40.0, -3.7), TrackPoint(40.01, -3.7)))
        assertNull(r.durationS); assertNull(r.movingS); assertNull(r.maxEleM); assertNull(r.movingSpeedKmh)
        assertTrue(r.profile.isEmpty())
    }

    @Test fun desnivelSinRuido() {
        // ±2 m de ruido en un llano no suma nada con umbral de 3 m.
        val noisy = (0 until 200).map { if (it % 2 == 0) 501.0 else 499.0 }
        val (up, down) = TrackStats.climb(noisy, 3.0)
        assertEquals(0.0, up, 0.0); assertEquals(0.0, down, 0.0)
        assertEquals(100.0 to 50.0, TrackStats.climb(listOf(0.0, 50.0, 100.0, 75.0, 50.0), 3.0))
    }

    @Test fun perfilReducidoConservaLaCima() {
        val pts = (0..5000).map { ProfilePoint(it * 2.0, if (it == 3333) 2500.0 else 1000.0 + it * 0.1) }
        val d = TrackStats.downsample(pts, 400)
        assertTrue(d.size <= 402)
        assertEquals(pts.first(), d.first()); assertEquals(pts.last(), d.last())
        assertTrue(d.any { it.eleM == 2500.0 })
        assertTrue(d.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
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
        val v15 = OfflineZone.plan(b, 15, 15, MapLayer.VECTOR)
        assertTrue(t15.tiles == o15.tiles && o15.tiles == v15.tiles)
        // El vectorial pesa más que las imágenes a ese zoom (medido en Madrid).
        assertTrue(v15.bytes > t15.bytes && t15.bytes > o15.bytes)
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

    @Test fun rumboYPuntoCardinal() {
        // Del Retiro a la Puerta del Sol (al oeste, un poco al norte) y al revés.
        val b = Direction.bearingDeg(40.4153, -3.6845, 40.4169, -3.7035)
        assertTrue(b in 270.0..285.0)
        assertEquals("O", Direction.cardinal(b))
        assertEquals("E", Direction.cardinal(Direction.bearingDeg(40.4169, -3.7035, 40.4153, -3.6845)))
        assertEquals(0.0, Direction.bearingDeg(40.0, -3.0, 41.0, -3.0), 1e-9)
        assertEquals("N", Direction.cardinal(359.0))
        assertEquals("NO", Direction.cardinal(320.0))
        assertEquals("SO", Direction.cardinal(225.0))
        assertEquals("suroeste", Direction.cardinalName(225.0))
        assertEquals("850 m al NE · 45°", Direction.describe(850.0, 45.2))
    }

    @Test fun aQueMano() {
        assertEquals("de frente", Direction.relative(10.0, 350.0))
        assertEquals("a tu derecha", Direction.relative(90.0, 0.0))
        assertEquals("a tu izquierda", Direction.relative(0.0, 90.0))
        assertEquals("delante a la izquierda", Direction.relative(320.0, 10.0))
        assertEquals("detrás a la derecha", Direction.relative(140.0, 0.0))
        assertEquals("detrás", Direction.relative(180.0, 0.0))
        assertEquals(-20.0, Direction.relativeDeg(350.0, 10.0), 1e-9)
    }

    @Test fun puntosEnGpx() {
        val pts = listOf(Waypoint(1, "Fuente & <pozo>", 40.7856, -3.9925, 1850.4, "2026-11-24T10:00:00Z"), Waypoint(2, "Coche", 40.8, -3.95))
        val back = WaypointGpx.parse(WaypointGpx.write(pts))
        assertEquals(2, back.size)
        assertEquals("Fuente & <pozo>", back[0].name)
        assertEquals(40.7856, back[0].lat, 1e-7)
        assertEquals(1850.4, back[0].ele!!, 0.05)
        assertEquals("2026-11-24T10:00:00Z", back[0].time)
        assertNull(back[1].ele)
        // De otras apps: comillas simples, sin nombre, CDATA, y un track que no cuenta.
        val other = "<gpx><wpt lon='-3.1' lat='40.1'/><wpt lat=\"40.2\" lon=\"-3.2\"><name><![CDATA[Refugio]]></name></wpt>" +
            "<trk><trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg></trk></gpx>"
        val o = WaypointGpx.parse(other, firstId = 10)
        assertEquals(listOf("Punto 1", "Refugio"), o.map { it.name })
        assertEquals(listOf(10L, 11L), o.map { it.id })
        assertEquals(40.1, o[0].lat, 1e-9)
        assertEquals("Punto 2", WaypointGpx.nextName(listOf(Waypoint(1, "Punto 1", 0.0, 0.0), Waypoint(2, "Punto 3", 0.0, 0.0))))
    }
}

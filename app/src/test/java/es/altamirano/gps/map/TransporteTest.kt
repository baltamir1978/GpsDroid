package es.altamirano.gps.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El GeoJSON de la capa de transporte (`Tools/transporte_madrid.py`): lo que las capas de
 * [GpsLayers] esperan de cada elemento. Si el CRTM cambia algo, el script lo rompería en silencio.
 */
class TransporteTest {
    @Test fun datosCompletos() {
        val json = JSONObject(File("src/main/assets/transporte_madrid.geojson").readText())
        val features = json.getJSONArray("features")
        val lines = HashMap<String, MutableSet<String>>()
        val stations = HashMap<String, Int>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val mode = p.getString("mode")
            when (p.getString("k")) {
                "l" -> {
                    assertEquals("LineString", f.getJSONObject("geometry").getString("type"))
                    assertTrue(p.getString("color").matches(Regex("#[0-9A-F]{6}")))
                    lines.getOrPut(mode) { HashSet() } += p.getString("line")
                }
                "s" -> {
                    assertEquals("Point", f.getJSONObject("geometry").getString("type"))
                    assertTrue(p.getString("name").isNotBlank())
                    assertTrue(p.getString("lines").isNotBlank())
                    stations.merge(mode, 1, Int::plus)
                }
                else -> error("tipo desconocido")
            }
        }
        assertEquals(13, lines["metro"]?.size)      // L1-L12 y el Ramal
        assertEquals(4, lines["ligero"]?.size)      // ML1-ML4
        assertEquals(9, lines["cercanias"]?.size)   // C-1 a C-10 (no hay C-6)
        assertTrue(stations.getValue("metro") > 230)
        assertTrue(stations.getValue("ligero") > 50)
        assertTrue(stations.getValue("cercanias") > 90)
    }
}

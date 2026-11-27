package es.altamirano.gps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun leeLaRespuestaDePhoton() {
        // Respuesta real de Photon a «mercadona segovia» (27-nov-2026).
        val list = PlaceSearch.parsePhoton(resource("photon-muestra.json"))
        assertTrue(list.isNotEmpty())
        val first = list.first()
        assertEquals("Mercadona", first.name)
        assertTrue(first.detail, first.detail.contains("Segovia"))
        assertEquals(40.93, first.lat, 0.05)
        assertEquals(-4.10, first.lon, 0.05)
    }

    @Test fun sinNombreUsaLaCalleYQuitaRepetidos() {
        val json = """{"features":[
            {"geometry":{"coordinates":[-4.0045,40.8990]},"properties":{"street":"Calle de la Botica","housenumber":"7","city":"Real Sitio de San Ildefonso"}},
            {"geometry":{"coordinates":[-4.0046,40.8991]},"properties":{"street":"Calle de la Botica","housenumber":"7","city":"Real Sitio de San Ildefonso"}},
            {"geometry":{"coordinates":[-3.7,40.4]},"properties":{}},
            {"properties":{"name":"Sin sitio"}}
        ]}"""
        val list = PlaceSearch.parsePhoton(json)
        assertEquals(1, list.size)
        assertEquals("Calle de la Botica 7", list[0].name)
        assertEquals("Real Sitio de San Ildefonso", list[0].detail)
        assertTrue(list[0].toWaypoint().id < 0)
    }

    @Test fun urlConPosicion() {
        val u = PlaceSearch.url(" botica 7 ", 40.9, -4.0)
        assertEquals("https://photon.komoot.io/api/?q=botica+7&limit=12&lat=40.90000&lon=-4.00000", u)
    }
}

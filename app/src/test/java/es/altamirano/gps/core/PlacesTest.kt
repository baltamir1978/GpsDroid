package es.altamirano.gps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PlacesTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun direccionesConNumero() {
        assertTrue(PlaceSearch.looksLikeAddress("alonso cano 56"))
        assertTrue(PlaceSearch.looksLikeAddress("calle mayor 3b, madrid"))
        assertTrue(PlaceSearch.looksLikeAddress("12 de octubre 4"))
        assertFalse(PlaceSearch.looksLikeAddress("mercadona segovia"))
        assertFalse(PlaceSearch.looksLikeAddress("m30"))           // una carretera, no un portal
        assertFalse(PlaceSearch.looksLikeAddress("a-6"))
    }

    @Test fun leeLosPortalesDeCartoCiudad() {
        // Respuesta real de CartoCiudad a «alonso cano 58» (27-nov-2026): cuatro portales en España.
        val sol = 40.4168 to -3.7038
        val list = PlaceSearch.parseCartoCiudad(resource("cartociudad-muestra.json"), sol.first, sol.second)
        assertEquals(4, list.size)
        // Desde Madrid, el de Madrid primero.
        assertEquals("Calle Alonso Cano 58", list[0].name)
        assertEquals("Madrid, 28003", list[0].detail)
        assertEquals(40.44262, list[0].lat, 1e-4)
        assertEquals("Plaza Alonso Cano 58", PlaceSearch.parseCartoCiudad(resource("cartociudad-muestra.json"))[0].name)
        assertTrue(PlaceSearch.parseCartoCiudad("no es json").isEmpty())
        assertEquals("Calle de la Reina 3", PlaceSearch.titleCase("CALLE DE LA REINA 3"))
        // Lo de Photon que cae encima de un portal no se repite; lo demás va detrás.
        val calle = Place("Calle de Alonso Cano", "Madrid", 40.4429, -3.6974)
        val portal = list[0]
        val encima = Place("Alonso Cano 58", "", portal.lat + 0.0001, portal.lon)
        assertEquals(listOf(portal, calle), PlaceSearch.merge(listOf(portal), listOf(encima, calle)))
    }

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

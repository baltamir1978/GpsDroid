package es.altamirano.gps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los iconos del estilo del CNIG y sus categorías (lista de capas guardada en `cnig-capas.txt`). */
class MapPoisTest {
    private val layers: List<Pair<Char, String>> = javaClass.classLoader!!.getResource("cnig-capas.txt")!!.readText()
        .lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it[0] to it.substring(2) }

    @Test fun todosLosIconosTienenCategoria() {
        val sin = layers.filter { it.first == 'I' && PoiCategory.of(it.second) == null }.map { it.second }
        assertTrue("Iconos sin categoría: $sin", sin.isEmpty())
    }

    @Test fun loQueNoEsUnPuntoNoSeToca() {
        // Calles, pueblos, ríos, relleno de zonas y el rótulo genérico de instalaciones.
        val tocadas = layers.filter { it.first == '-' }.mapNotNull { (_, id) -> PoiCategory.of(id)?.let { id } }
        assertTrue("Capas que no son símbolos con categoría: $tocadas", tocadas.isEmpty())
        assertNull(PoiCategory.of("servicio_instalacion_pto-texto"))
        assertNull(PoiCategory.of("nombre_hidrografia_pto-embalse"))
        assertNull(PoiCategory.of("espacio_natural_protegido_pol-texto"))
        assertNull(PoiCategory.of("autopista_lin"))
    }

    @Test fun prefijosExactos() {
        // «parque» no se lleva por delante los parques temáticos ni los tecnológicos.
        assertEquals(PoiCategory.PARK, PoiCategory.of("servicio_instalacion_pto-simbolo-parque"))
        assertEquals(PoiCategory.THEME_PARK, PoiCategory.of("servicio_instalacion_pto-simbolo-parque_tematico_ocio"))
        assertEquals(PoiCategory.INDUSTRIAL, PoiCategory.of("servicio_instalacion_pto-simbolo-parque_tecnologico"))
        assertEquals(PoiCategory.THEME_PARK, PoiCategory.of("servicio_instalacion_pto-simbolo-recinto_ferial "))
        assertEquals(PoiCategory.CHURCH, PoiCategory.of("servicio_instalacion_pto-simbolo-instalacion_religiosa-ermita"))
        assertEquals(PoiCategory.ANTENNA, PoiCategory.of("servicio_instalacion_pto-simbolo-antena"))
        assertEquals(PoiCategory.HEALTH_CARE, PoiCategory.of("servicio_instalacion_pto-simbolo-instalacion_sanitaria"))
        assertEquals(PoiCategory.TRAIN, PoiCategory.of("estacion_ferrocarril_pto-simbolo"))
        assertEquals(PoiCategory.CAMPING, PoiCategory.of("servicio_instalacion_pto-simbolo-camping-texto"))
    }

    @Test fun guardarYLeer() {
        assertEquals(PoiCategory.DEFAULT, PoiCategory.parse(null))
        assertTrue(PoiCategory.DEFAULT.containsAll(setOf(PoiCategory.HEALTH_CARE, PoiCategory.CHURCH)))
        assertTrue(PoiCategory.ANTENNA !in PoiCategory.DEFAULT)
        assertEquals(emptySet<PoiCategory>(), PoiCategory.parse(""))
        val s = setOf(PoiCategory.CHURCH, PoiCategory.HEALTH_CARE)
        assertEquals(s, PoiCategory.parse(PoiCategory.format(s)))
        assertEquals(setOf(PoiCategory.CHURCH), PoiCategory.parse("religion,desconocida"))
        assertEquals(PoiCategory.entries.size, PoiCategory.entries.map { it.key }.distinct().size)
    }
}

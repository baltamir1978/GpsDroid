package es.altamirano.gps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    @Test fun erroresDeBRouterEnCastellano() {
        assertTrue(Routing.message("datafile W5_N40.rd5 not found").startsWith("Faltan los datos de esta zona"))
        assertTrue(Routing.message("from-position not mapped in existing datafile").startsWith("Faltan") ||
            Routing.message("from-position not mapped in existing datafile").startsWith("No hay caminos"))
        assertEquals("No hay caminos cerca del inicio o del destino", Routing.message("to-position not mapped"))
        // JSON tal cual: no se toca.
        assertEquals("{\"a\":1}", Routing.decompress(" {\"a\":1} "))
    }
}

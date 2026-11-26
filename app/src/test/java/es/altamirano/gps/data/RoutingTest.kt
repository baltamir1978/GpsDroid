package es.altamirano.gps.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingTest {
    @Test fun erroresDelMotorEnCastellano() {
        assertEquals("Faltan los datos de rutas de esta zona", Routing.message("datafile W5_N40.rd5 not found"))
        assertEquals("No hay caminos cerca del inicio o del destino", Routing.message("to-position not mapped in existing datafile"))
        assertEquals("La ruta tarda demasiado en calcularse: prueba con un destino más cerca", Routing.message("operation killed by thread-priority-watchdog after 60 seconds"))
    }
}

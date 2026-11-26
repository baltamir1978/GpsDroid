package es.altamirano.gps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevationTest {
    @Test fun leeLaRespuestaDeOpenMeteo() {
        assertEquals(listOf(812.0, 2428.5), Elevation.parse("""{"elevation":[812.0,2428.5]}"""))
        assertEquals(listOf(0.0, null), Elevation.parse("""{"elevation": [0.0, null]}"""))
        assertEquals(emptyList<Double?>(), Elevation.parse("""{"elevation":[]}"""))
        assertNull(Elevation.parse("""{"error":true,"reason":"Latitude must be in range"}"""))
    }
}

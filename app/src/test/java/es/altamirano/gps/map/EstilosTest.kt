package es.altamirano.gps.map

import es.altamirano.gps.core.MapLayer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Los estilos de las imágenes publicados en el repositorio (`estilos/`, los que pide el descargador
 * de MapLibre por HTTP) tienen que ser iguales a los que usa la app para pintar: si no, las teselas
 * descargadas tendrían otra URL y no servirían. Con `ESCRIBIR_ESTILOS=1` se reescriben.
 */
class EstilosTest {
    @Test fun estilosPublicadosAlDia() {
        val dir = File("../estilos")
        for (l in MapLayer.entries.filter { it.raster }) {
            val f = File(dir, "${l.key}.json")
            val json = MapStyles.rasterStyleJson(l)
            if (System.getenv("ESCRIBIR_ESTILOS") != null) { dir.mkdirs(); f.writeText(json) }
            assertEquals("estilos/${l.key}.json no coincide: ESCRIBIR_ESTILOS=1 ./gradlew testDebugUnitTest y súbelo", json, f.readText())
        }
    }
}

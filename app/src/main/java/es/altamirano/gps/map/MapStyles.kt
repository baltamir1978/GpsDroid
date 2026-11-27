package es.altamirano.gps.map

import android.content.Context
import es.altamirano.gps.core.MapLayer
import es.altamirano.gps.core.MapStyle
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.File

/**
 * Los estilos de MapLibre de cada mapa (móvil y coche):
 * - Callejero: el estilo vectorial del CNIG tal cual.
 * - Topográfico y Satélite: un estilo mínimo con la capa raster del IGN (WMTS con {z}/{x}/{y}) y las
 *   letras del vectorial (para los nombres de los puntos). Se escribe en un fichero para poder usar su
 *   `file://` también en las descargas (una región de MapLibre se define con la URL de un estilo).
 * - Híbrido: el vectorial con sólo sus rótulos e iconos y la ortofoto debajo (nombres derechos al girar).
 */
object MapStyles {
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.filesDir, "estilos").apply { mkdirs() }
        // Se reescriben siempre: si cambia la plantilla en una versión nueva, que se use.
        MapLayer.entries.filter { it.raster }.forEach { File(dir, "${it.key}.json").writeText(rasterStyleJson(it)) }
    }

    /** La URL del estilo de una capa: el del CNIG para el vectorial, el fichero propio para las imágenes. */
    fun uriOf(layer: MapLayer): String = if (layer.raster) "file://" + File(dir, "${layer.key}.json").absolutePath else MapLayer.VECTOR_STYLE_URL

    internal fun rasterStyleJson(layer: MapLayer): String {
        val id = "ign-${layer.key}"
        // Mientras carga, un fondo neutro (el blanco de serie deslumbra en el coche de noche).
        val bg = if (layer == MapLayer.ORTHO) "#5a6250" else "#ece9df"
        return """
        {"version": 8, "name": "${layer.label}", "glyphs": "${MapLayer.GLYPHS_URL}",
         "sources": {"$id": {"type": "raster", "tiles": ["${layer.urlTemplate()}"], "tileSize": 256,
                             "minzoom": 0, "maxzoom": ${layer.maxZoom}, "attribution": "${MapLayer.ATTRIBUTION}"}},
         "layers": [{"id": "fondo", "type": "background", "paint": {"background-color": "$bg"}},
                    {"id": "$id", "type": "raster", "source": "$id"}]}
        """.trimIndent()
    }

    /** Pone [style] en el mapa y llama a [onLoaded] con el estilo ya listo para añadirle las capas de la app. */
    fun apply(map: MapLibreMap, style: MapStyle, onLoaded: (Style) -> Unit) {
        when (style) {
            MapStyle.HYBRID -> map.setStyle(Style.Builder().fromUri(MapLayer.VECTOR_STYLE_URL)) { st ->
                // Del vectorial, sólo rótulos e iconos; debajo de todo, la ortofoto.
                // Los textos con borde blanco marcado: sobre la foto, con el del estilo (pensado para el mapa claro) no se leían.
                st.layers.forEach {
                    if (it !is SymbolLayer) it.setProperties(PropertyFactory.visibility(Property.NONE))
                    else it.setProperties(PropertyFactory.textHaloColor(android.graphics.Color.WHITE), PropertyFactory.textHaloWidth(1.6f))
                }
                val ortho = MapLayer.ORTHO
                st.addSource(RasterSource("ign-${ortho.key}", TileSet("2.1.0", ortho.urlTemplate()).apply {
                    setMaxZoom(ortho.maxZoom.toFloat()); attribution = MapLayer.ATTRIBUTION
                }, 256))
                st.addLayerAt(RasterLayer("ign-${ortho.key}", "ign-${ortho.key}"), 0)
                onLoaded(st)
            }
            else -> map.setStyle(Style.Builder().fromUri(uriOf(style.base)), onLoaded)
        }
    }
}

package es.altamirano.gps.map

import android.content.Context
import es.altamirano.gps.core.MapLayer
import es.altamirano.gps.core.MapStyle
import es.altamirano.gps.core.PoiCategory
import es.altamirano.gps.data.Prefs
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
 * En los dos con el vectorial, los iconos de puntos sólo de las categorías elegidas en «Puntos» ([Prefs.pois]).
 */
object MapStyles {
    private lateinit var dir: File
    private lateinit var prefs: Prefs

    fun init(context: Context) {
        prefs = Prefs(context)
        dir = File(context.filesDir, "estilos").apply { mkdirs() }
        // Se reescriben siempre: si cambia la plantilla en una versión nueva, que se use.
        MapLayer.entries.filter { it.raster }.forEach { File(dir, "${it.key}.json").writeText(rasterStyleJson(it)) }
    }

    /** La URL del estilo de una capa para pintarla: el del CNIG para el vectorial, el fichero propio para las imágenes. */
    fun uriOf(layer: MapLayer): String = if (layer.raster) "file://" + File(dir, "${layer.key}.json").absolutePath else MapLayer.VECTOR_STYLE_URL

    /**
     * La URL del estilo para las descargas: el descargador de MapLibre sólo pide los estilos por HTTP
     * (con `file://` se quedaba en «0 de 1»: «Unable to parse resourceUrl»). Los de las imágenes están
     * publicados en el repositorio (`estilos/<capa>.json`, iguales a [rasterStyleJson]: lo vigila
     * `EstilosTest`); las teselas se guardan por su URL, así que sirven al pintar con el fichero local.
     */
    fun downloadUriOf(layer: MapLayer): String = if (layer.raster) "$PUBLISHED/${layer.key}.json" else MapLayer.VECTOR_STYLE_URL

    private const val PUBLISHED = "https://raw.githubusercontent.com/baltamir1978/GpsDroid/main/estilos"

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
    fun apply(map: MapLibreMap, style: MapStyle, layers: GpsLayers?, onLoaded: (Style) -> Unit) {
        layers?.detach()
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
                filterPois(st)
                onLoaded(st)
            }
            else -> map.setStyle(Style.Builder().fromUri(uriOf(style.base))) { st ->
                if (!style.base.raster) filterPois(st)
                onLoaded(st)
            }
        }
    }

    /**
     * Oculta los iconos de las categorías apagadas. Sólo se ocultan (las teselas son las mismas: lo
     * descargado sigue valiendo); las capas que el CNIG ya trae ocultas no se encienden.
     */
    private fun filterPois(st: Style) {
        val on = prefs.pois
        st.layers.forEach { l ->
            val c = PoiCategory.of(l.id) ?: return@forEach
            if (c !in on) l.setProperties(PropertyFactory.visibility(Property.NONE))
        }
    }
}

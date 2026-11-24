package es.altamirano.gps.core

/**
 * Las series de teselas del IGN, todas por WMTS con el conjunto `GoogleMapsCompatible` (comprobado
 * en sus GetCapabilities el 20-nov-2026 para ZeroGPS y el 24-nov-2026 para los rótulos). Licencia
 * CC-BY 4.0: la atribución tiene que verse en el mapa. Cada una va a la caché por separado.
 *
 * [maxZoom]: por encima no hay más detalle (el MTN 1:25.000 no da más de sí a partir de z17, la
 * ortofoto PNOA de 25 cm/px llega a z19, el Base sigue nítido hasta z18); el mapa amplía la última.
 *
 * [LABELS] no es un mapa: son los rótulos de `IGNBaseOrto` (nombres, calles, carreteras) en PNG con
 * transparencia, para pintarlos encima de la ortofoto en el estilo [MapStyle.HYBRID]. ZeroGPS no lo
 * tiene porque allí haría falta libpng; osmdroid pinta PNG sin más.
 */
enum class MapLayer(val key: String, val label: String, val service: String, val wmtsLayer: String, val maxZoom: Int, val png: Boolean = false) {
    TOPO("topo", "Topográfico", "mapa-raster", "MTN", 17),
    ORTHO("pnoa", "Satélite", "pnoa-ma", "OI.OrthoimageCoverage", 19),
    BASE("base", "IGN Base", "ign-base", "IGNBaseTodo", 18),
    LABELS("rotulos", "Rótulos del híbrido", "ign-base", "IGNBaseOrto", 19, png = true);

    /** URL GetTile del IGN (fila = y, columna = x). */
    fun tileUrl(z: Int, x: Int, y: Int) =
        "https://www.ign.es/wmts/$service?service=WMTS&request=GetTile&version=1.0.0" +
            "&layer=$wmtsLayer&style=default&format=image/${if (png) "png" else "jpeg"}&tilematrixset=GoogleMapsCompatible" +
            "&tilematrix=$z&tilerow=$y&tilecol=$x"

    /**
     * Bytes por tesela de media, medidos en Guadarrama el 20-nov-2026 (ZeroGPS) con un ~15 % de
     * margen. La ortofoto pesa más o menos igual a todos los zooms; el Base cada vez menos (más
     * zonas lisas); el topográfico es el más pesado. Los rótulos (24-nov-2026, Guadarrama y Madrid
     * centro): mucho texto de lejos, casi nada de cerca en el monte (662 bytes vacía), ~10 KB en
     * una ciudad a z17.
     */
    fun estimatedTileBytes(z: Int): Long = 1024L * when (this) {
        ORTHO -> if (z <= 12) 12 else 19
        BASE -> if (z <= 14) 11 else if (z <= 16) 7 else 4
        TOPO -> if (z <= 12) 28 else if (z <= 15) 26 else if (z == 16) 22 else 14
        LABELS -> if (z <= 12) 20 else if (z <= 14) 14 else if (z <= 16) 7 else 4
    }

    companion object {
        const val ATTRIBUTION = "© Instituto Geográfico Nacional de España"
    }
}

/**
 * Lo que se elige en el mapa: una capa de fondo y, en el híbrido, los rótulos encima. El híbrido usa
 * las mismas teselas de ortofoto que «Satélite» (lo ya guardado de una vale para la otra).
 */
enum class MapStyle(val key: String, val label: String, val base: MapLayer, val overlay: MapLayer? = null) {
    TOPO("topo", "Topográfico", MapLayer.TOPO),
    ORTHO("pnoa", "Satélite", MapLayer.ORTHO),
    HYBRID("hibrido", "Híbrido", MapLayer.ORTHO, MapLayer.LABELS),
    BASE("base", "IGN Base", MapLayer.BASE);

    val maxZoom: Int get() = base.maxZoom

    /** Lo que hay que bajar para verlo sin cobertura. */
    val layers: List<MapLayer> get() = listOfNotNull(base, overlay)

    fun next(): MapStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Las claves de los estilos coinciden con las de las capas de la 0.2: lo guardado sigue valiendo. */
        fun fromKey(key: String?): MapStyle = entries.firstOrNull { it.key == key } ?: TOPO

        /** Las capas de varios estilos, sin repetir (Satélite + Híbrido = ortofoto una vez + rótulos). */
        fun layersOf(styles: List<MapStyle>): List<MapLayer> = styles.flatMap { it.layers }.distinct()
    }
}

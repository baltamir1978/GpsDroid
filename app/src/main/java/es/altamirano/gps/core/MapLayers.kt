package es.altamirano.gps.core

/**
 * Los mapas del IGN, todos por WMTS con el conjunto `GoogleMapsCompatible` y en JPEG (comprobado en
 * sus GetCapabilities el 20-nov-2026, para ZeroGPS). Licencia CC-BY 4.0: la atribución tiene que
 * verse en el mapa.
 *
 * [maxZoom]: por encima no hay más detalle (el MTN 1:25.000 no da más de sí a partir de z17, la
 * ortofoto PNOA de 25 cm/px llega a z19, el Base sigue nítido hasta z18); el mapa amplía la última.
 */
enum class MapLayer(val key: String, val label: String, val service: String, val wmtsLayer: String, val maxZoom: Int) {
    TOPO("topo", "Topográfico", "mapa-raster", "MTN", 17),
    ORTHO("pnoa", "Satélite", "pnoa-ma", "OI.OrthoimageCoverage", 19),
    BASE("base", "IGN Base", "ign-base", "IGNBaseTodo", 18);

    fun next(): MapLayer = entries[(ordinal + 1) % entries.size]

    /** URL GetTile del IGN (fila = y, columna = x). */
    fun tileUrl(z: Int, x: Int, y: Int) =
        "https://www.ign.es/wmts/$service?service=WMTS&request=GetTile&version=1.0.0" +
            "&layer=$wmtsLayer&style=default&format=image/jpeg&tilematrixset=GoogleMapsCompatible" +
            "&tilematrix=$z&tilerow=$y&tilecol=$x"

    /**
     * Bytes por tesela de media, medidos en Guadarrama el 20-nov-2026 (ZeroGPS) con un ~15 % de
     * margen. La ortofoto pesa más o menos igual a todos los zooms; el Base cada vez menos (más
     * zonas lisas); el topográfico es el más pesado.
     */
    fun estimatedTileBytes(z: Int): Long = 1024L * when (this) {
        ORTHO -> if (z <= 12) 12 else 19
        BASE -> if (z <= 14) 11 else if (z <= 16) 7 else 4
        TOPO -> if (z <= 12) 28 else if (z <= 15) 26 else if (z == 16) 22 else 14
    }

    companion object {
        const val ATTRIBUTION = "© Instituto Geográfico Nacional de España"
        fun fromKey(key: String?): MapLayer = entries.firstOrNull { it.key == key } ?: TOPO
    }
}

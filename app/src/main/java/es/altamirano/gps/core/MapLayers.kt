package es.altamirano.gps.core

/**
 * Las capas del IGN que se pintan y se descargan. Licencia CC-BY 4.0: la atribución tiene que verse.
 *
 * - [TOPO] y [ORTHO]: imágenes (raster) por WMTS con el conjunto `GoogleMapsCompatible` (comprobado
 *   en sus GetCapabilities el 20-nov-2026 para ZeroGPS). Tienen información que el vectorial no trae:
 *   el MTN 1:25.000 (curvas, caminos, toponimia de monte) y la ortofoto PNOA.
 * - [VECTOR]: el **Mapa Base vectorial** del CNIG (desde la 0.27.0, en lugar del «IGN Base» raster):
 *   lo dibuja el móvil, así que los rótulos siguen derechos aunque el mapa gire (en el coche) y se ven
 *   nítidos a cualquier zoom. También pone los nombres del híbrido encima de la ortofoto.
 *
 * [maxZoom]: hasta dónde hay datos (el MTN no da más de sí a partir de z17, la ortofoto de 25 cm/px
 * llega a z19, el vectorial trae datos hasta z17 y por encima se amplía sin perder nitidez). Los zooms
 * son los de las teselas de 256 px (los de siempre); MapLibre cuenta uno menos para la misma escala.
 */
enum class MapLayer(val key: String, val label: String, val maxZoom: Int, private val service: String = "", private val wmtsLayer: String = "") {
    TOPO("topo", "Topográfico", 17, "mapa-raster", "MTN"),
    ORTHO("pnoa", "Satélite", 19, "pnoa-ma", "OI.OrthoimageCoverage"),
    VECTOR("base", "Callejero", 17);

    val raster: Boolean get() = this != VECTOR

    /** URL GetTile del IGN (fila = y, columna = x). */
    fun tileUrl(z: Int, x: Int, y: Int) = urlTemplate().replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")

    /** La plantilla de URL de las teselas raster, con {z}/{x}/{y} como la quiere MapLibre. */
    fun urlTemplate(): String {
        require(raster) { "El vectorial va por su estilo" }
        return "https://www.ign.es/wmts/$service?service=WMTS&request=GetTile&version=1.0.0" +
            "&layer=$wmtsLayer&style=default&format=image/jpeg&tilematrixset=GoogleMapsCompatible" +
            "&tilematrix={z}&tilerow={y}&tilecol={x}"
    }

    /**
     * Bytes por tesela de media con algo de margen. Raster medidos en Guadarrama el 20-nov-2026
     * (ZeroGPS). El vectorial, el 27-nov-2026 en Madrid y Guadarrama (sin comprimir; el servidor no
     * manda gzip): pesa mucho de lejos (cada tesela lleva toda una región) y en ciudad ~100 KB a
     * z14-15; a z17 casi nada (5-17 KB). Ojo: más que el topográfico, no menos. Con margen por las letras y
     * los iconos del estilo, que van en cada descarga: 1 km en Madrid de z5 a z17 fueron 15 MB.
     */
    fun estimatedTileBytes(z: Int): Long = 1024L * when (this) {
        ORTHO -> if (z <= 12) 12 else 19
        TOPO -> if (z <= 12) 28 else if (z <= 15) 26 else if (z == 16) 22 else 14
        VECTOR -> if (z <= 10) 600 else if (z <= 12) 400 else if (z <= 15) 160 else if (z == 16) 80 else 25
    }

    companion object {
        const val ATTRIBUTION = "© Instituto Geográfico Nacional de España"
        /** El estilo del Mapa Base vectorial (CNIG), en color; sin clave. Comprobado el 27-nov-2026. */
        const val VECTOR_STYLE_URL = "https://vt-mapabase.idee.es/files/styles/mapaBase_scn_color1_CNIG.json"
        /** Las letras del vectorial: también para los nombres de los puntos sobre el topográfico o la ortofoto. */
        const val GLYPHS_URL = "https://vt-mapabase.idee.es/files/styles/fonts/pbf/{fontstack}/{range}.pbf"
    }
}

/**
 * Lo que se elige en el mapa: una capa de fondo y, en el híbrido, los nombres del vectorial encima de
 * la ortofoto. El híbrido usa las mismas teselas de ortofoto que «Satélite» (lo guardado vale para los dos).
 */
enum class MapStyle(val key: String, val label: String, val base: MapLayer, val overlay: MapLayer? = null) {
    TOPO("topo", "Topográfico", MapLayer.TOPO),
    ORTHO("pnoa", "Satélite", MapLayer.ORTHO),
    HYBRID("hibrido", "Híbrido", MapLayer.ORTHO, MapLayer.VECTOR),
    /** La clave «base» es la del antiguo IGN Base raster: quien lo tenía elegido pasa al callejero vectorial. */
    BASE("base", "Callejero", MapLayer.VECTOR);

    val maxZoom: Int get() = base.maxZoom

    /** Hasta dónde deja acercar el mapa: el vectorial se dibuja nítido a cualquier zoom; las imágenes, un nivel ampliadas. */
    val viewMaxZoom: Int get() = if (base == MapLayer.VECTOR) 20 else base.maxZoom + 1

    /** Lo que hay que bajar para verlo sin cobertura. */
    val layers: List<MapLayer> get() = listOfNotNull(base, overlay)

    fun next(): MapStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Las claves de los estilos coinciden con las de las capas de la 0.2: lo guardado sigue valiendo. */
        fun fromKey(key: String?): MapStyle = entries.firstOrNull { it.key == key } ?: TOPO

        /** Las capas de varios estilos, sin repetir (Satélite + Híbrido = ortofoto una vez + vectorial). */
        fun layersOf(styles: List<MapStyle>): List<MapLayer> = styles.flatMap { it.layers }.distinct()
    }
}

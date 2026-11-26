package es.altamirano.gps.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** Rectángulo geográfico, en grados. */
data class BBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun normalized() = BBox(minOf(minLat, maxLat), minOf(minLon, maxLon), maxOf(minLat, maxLat), maxOf(minLon, maxLon))
    val centerLat get() = (minLat + maxLat) / 2
    val centerLon get() = (minLon + maxLon) / 2
}

/** Lo que cuesta bajar una zona: teselas y bytes estimados, en total y por zoom. */
data class ZonePlan(val zmin: Int, val zmax: Int, val tiles: Long, val bytes: Long, val perZoom: Map<Int, Long>)

/**
 * Descarga de zonas para usar el mapa sin cobertura. Traducido de `offline_zone` de ZeroGPS: las
 * mismas zonas ya hechas, los mismos topes y el mismo formato de números.
 */
object OfflineZone {
    const val ZOOM_MIN = 5
    const val ZOOM_MAX = 19

    /** Tope por descarga: el IGN es un servicio público y la licencia no ampara volcarlo entero. */
    const val MAX_TILES = 100_000L

    private const val KM_PER_DEG_LAT = 111.32

    /** Zonas «completas» (montaña, sobre todo, que es donde no hay cobertura), con algo de margen. */
    val PRESETS: List<Pair<String, BBox>> = listOf(
        "Sierra de Guadarrama" to BBox(40.62, -4.30, 41.02, -3.65),
        "Sierra de Gredos" to BBox(40.12, -5.70, 40.45, -4.80),
        "Picos de Europa" to BBox(43.03, -5.15, 43.32, -4.50),
        "Sierra Nevada" to BBox(36.92, -3.65, 37.22, -2.80),
        "Pirineo central" to BBox(42.45, -0.60, 42.85, 0.90),
        "Sierra de Cazorla" to BBox(37.65, -3.15, 38.20, -2.55),
        "Montseny" to BBox(41.70, 2.25, 41.87, 2.55),
        "Madrid ciudad" to BBox(40.31, -3.84, 40.56, -3.52),
    )

    /** Cuadrado de [radiusKm] de semilado alrededor de un punto. */
    fun bboxAround(lat: Double, lon: Double, radiusKm: Double): BBox {
        val dLat = radiusKm / KM_PER_DEG_LAT
        val dLon = radiusKm / (KM_PER_DEG_LAT * max(0.01, cos(lat * PI / 180.0)))
        return BBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }

    fun countTiles(raw: BBox, z: Int): Long {
        val b = raw.normalized()
        // En Y, más al norte = menor índice: la esquina de arriba sale de la latitud máxima.
        val tl = MapTiles.tileAt(b.maxLat, b.minLon, z)
        val br = MapTiles.tileAt(b.minLat, b.maxLon, z)
        return (br.x - tl.x + 1).toLong() * (br.y - tl.y + 1).toLong()
    }

    /** Los zooms se recortan a [ZOOM_MIN, máximo de la capa]. */
    fun plan(box: BBox, zmin: Int, zmax: Int, layer: MapLayer): ZonePlan {
        val top = minOf(ZOOM_MAX, layer.maxZoom)
        val lo = zmin.coerceIn(ZOOM_MIN, top)
        val hi = zmax.coerceIn(lo, top)
        val per = (lo..hi).associateWith { countTiles(box, it) }
        return ZonePlan(lo, hi, per.values.sum(), per.entries.sumOf { (z, n) -> n * layer.estimatedTileBytes(z) }, per)
    }

    /** Varias capas a la vez («Todos», o el híbrido: ortofoto + rótulos): se suman. */
    fun plan(box: BBox, zmin: Int, zmax: Int, layers: List<MapLayer>): Pair<Long, Long> =
        layers.map { plan(box, zmin, zmax, it) }.let { p -> p.sumOf { it.tiles } to p.sumOf { it.bytes } }

    /** "850 KB", "12,3 MB", "1,4 GB" (coma decimal, como se escribe en España). */
    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        val s = when {
            gb >= 1.0 -> "%.1f GB".format(java.util.Locale.ROOT, gb)
            mb >= 10.0 -> "%.0f MB".format(java.util.Locale.ROOT, mb)
            mb >= 1.0 -> "%.1f MB".format(java.util.Locale.ROOT, mb)
            else -> "%.0f KB".format(java.util.Locale.ROOT, max(0.0, kb))
        }
        return s.replace('.', ',')
    }

    /** "12.345" (punto de millares). */
    fun formatCount(value: Long): String {
        val digits = kotlin.math.abs(value).toString().reversed().chunked(3).joinToString(".").reversed()
        return if (value < 0) "-$digits" else digits
    }
}

/**
 * Las teselas de un pasillo a lo largo de un camino (una ruta o un track): las que tocan una franja
 * de [marginM] metros a cada lado. Para guardarlas al empezar a seguirlo, por si más adelante no hay
 * cobertura. Se recorre el camino a pasos más cortos que una tesela y, en cada paso, se añade el
 * cuadrado de ±[marginM] (de sobra en las curvas, nunca de menos).
 */
object RouteCorridor {
    fun tiles(points: List<TrackPoint>, marginM: Double, z: Int): Set<MapTiles.Tile> {
        val out = LinkedHashSet<MapTiles.Tile>()
        if (points.isEmpty()) return out
        val lat0 = points[0].lat
        val tileM = MapTiles.metersPerPixel(lat0, z) * MapTiles.TILE_SIZE
        val step = minOf(marginM, tileM / 2).coerceAtLeast(10.0)
        fun addAround(lat: Double, lon: Double) {
            val dLat = marginM / 111_195.0
            val dLon = marginM / (111_195.0 * max(0.01, cos(lat * PI / 180.0)))
            val tl = MapTiles.tileAt(lat + dLat, lon - dLon, z)
            val br = MapTiles.tileAt(lat - dLat, lon + dLon, z)
            for (x in tl.x..br.x) for (y in tl.y..br.y) out += MapTiles.Tile(z, x, y)
        }
        addAround(points[0].lat, points[0].lon)
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val n = (Geo.distanceM(a.lat, a.lon, b.lat, b.lon) / step).toInt().coerceAtLeast(1)
            for (k in 1..n) addAround(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
        }
        return out
    }

    /**
     * Qué bajar: de [zmin] a [zmax] (sin pasar del máximo de cada capa) y, si son demasiadas (un viaje
     * largo en coche), quitando los zooms de más detalle hasta que quepan en [maxTiles].
     */
    fun plan(points: List<TrackPoint>, marginM: Double, zmin: Int, zmax: Int, layers: List<MapLayer>,
             maxTiles: Long = OfflineZone.MAX_TILES): List<Pair<MapLayer, Set<MapTiles.Tile>>> {
        var top = zmax
        while (true) {
            val plan = layers.flatMap { l -> (zmin..minOf(top, l.maxZoom)).map { z -> l to tiles(points, marginM, z) } }
            if (plan.sumOf { it.second.size.toLong() } <= maxTiles || top <= zmin) return plan
            top--
        }
    }
}

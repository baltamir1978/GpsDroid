package es.altamirano.gps.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Matemática Web Mercator del esquema «slippy map» (WMTS `GoogleMapsCompatible` del IGN): teselas
 * de 256 px, z/x/y. Traducida de `map_tiles` de ZeroGPS. osmdroid hace lo suyo para pintar; esto
 * sirve para contar las teselas de una descarga y estimar su tamaño.
 */
object MapTiles {
    const val TILE_SIZE = 256
    private const val MAX_LAT = 85.05112878

    data class Tile(val z: Int, val x: Int, val y: Int)

    private fun n(z: Int) = (1 shl z).toDouble()

    fun lonToTileX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * n(z)

    fun latToTileY(lat: Double, z: Int): Double {
        // Web Mercator se define hasta ~±85,05°: se recorta para no divergir en los polos.
        val r = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n(z)
    }

    fun tileXToLon(x: Double, z: Int) = x / n(z) * 360.0 - 180.0

    fun tileYToLat(y: Double, z: Int) = atan(sinh(PI * (1.0 - 2.0 * y / n(z)))) * 180.0 / PI

    fun tileAt(lat: Double, lon: Double, z: Int): Tile {
        val max = (1 shl z) - 1
        return Tile(z, floor(lonToTileX(lon, z)).toInt().coerceIn(0, max), floor(latToTileY(lat, z)).toInt().coerceIn(0, max))
    }

    /**
     * El centro nuevo al cambiar el zoom en [deltaZoom] niveles sin que se mueva de la pantalla el
     * punto [anchorLat], [anchorLon] (el que está bajo el cursor), como hace un mapa de escritorio
     * con la rueda. En coordenadas Mercator la distancia en pantalla al centro se multiplica por
     * 2^Δ, así que el centro se acerca al ancla en esa proporción. Devuelve lat, lon.
     */
    fun zoomAnchoredCenter(centerLat: Double, centerLon: Double, anchorLat: Double, anchorLon: Double, deltaZoom: Double): Pair<Double, Double> {
        val s = Math.pow(2.0, deltaZoom)
        val cx = lonToTileX(centerLon, 0); val cy = latToTileY(centerLat, 0)
        val ax = lonToTileX(anchorLon, 0); val ay = latToTileY(anchorLat, 0)
        return tileYToLat(ay - (ay - cy) / s, 0) to tileXToLon(ax - (ax - cx) / s, 0)
    }

    /** Metros que mide un píxel del mapa a esa latitud y zoom. */
    fun metersPerPixel(lat: Double, z: Int) = 40075016.686 * cos(lat * PI / 180.0) / (TILE_SIZE * n(z))
}

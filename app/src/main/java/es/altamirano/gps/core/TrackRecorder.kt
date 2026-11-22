package es.altamirano.gps.core

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6371008.8
    private fun rad(d: Double) = d * Math.PI / 180.0

    /** Distancia sobre la esfera (haversine), en metros. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun lengthM(points: List<TrackPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceM(a.lat, a.lon, b.lat, b.lon) }

    /** Lo que se sube (sólo los tramos de subida), con un umbral para que el ruido de la altitud no lo infle. */
    fun ascentM(points: List<TrackPoint>, threshold: Double = 3.0): Double {
        var total = 0.0
        var ref: Double? = null
        for (e in points.mapNotNull { it.ele }) {
            val r = ref
            if (r == null) ref = e
            else if (e - r >= threshold) { total += e - r; ref = e }
            else if (r - e >= threshold) ref = e
        }
        return total
    }

    /** "1:02:05" / "12:05". */
    fun formatDuration(seconds: Long): String {
        val t = max(0L, seconds)
        val h = t / 3600; val m = (t / 60) % 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** "850 m" / "12,3 km". */
    fun formatDistance(meters: Double): String =
        if (meters < 1000.0) "%.0f m".format(Locale.ROOT, max(0.0, meters))
        else "%.1f km".format(Locale.ROOT, meters / 1000.0).replace('.', ',')

    /** "40,41680° N  3,70380° O" */
    fun formatLatLon(lat: Double, lon: Double): String =
        ("%.5f° %s  %.5f° %s".format(Locale.ROOT, kotlin.math.abs(lat), if (lat >= 0) "N" else "S",
            kotlin.math.abs(lon), if (lon >= 0) "E" else "O")).replace('.', ',')
}

/**
 * Decide qué posiciones acaban en el track (lo mismo que ZeroGPS). Grabar cada segundo llenaría el
 * GPX de puntos repetidos: se guarda un punto cuando te has movido al menos [minDistanceM], o cada
 * [maxIntervalS] si te mueves despacio pero algo (más que [jitterM]), para que el ruido del GPS
 * parado no dibuje una maraña. En el móvil, además, se descartan las posiciones con poca precisión
 * (las primeras tras encender el GPS, o bajo techo), que en ZeroGPS no se pueden saber.
 */
class TrackRecorder(
    private val minDistanceM: Double = 5.0,
    private val maxIntervalS: Double = 30.0,
    private val maxAccuracyM: Double = 30.0,
    private val jitterM: Double = 2.0,
) {
    private val points = ArrayList<TrackPoint>()
    var lengthM = 0.0
        private set
    private var lastT = 0.0

    val track: List<TrackPoint> get() = points

    fun reset(start: List<TrackPoint> = emptyList()) {
        points.clear(); points.addAll(start)
        lengthM = Geo.lengthM(start); lastT = 0.0
    }

    /** [tSec]: segundos de un reloj monótono; [accuracyM]: null si no se sabe. true si el punto entró. */
    fun offer(p: TrackPoint, accuracyM: Double?, tSec: Double): Boolean {
        if (accuracyM != null && accuracyM > maxAccuracyM) return false
        val last = points.lastOrNull()
        if (last == null) {
            points += p; lastT = tSec; return true
        }
        val d = Geo.distanceM(last.lat, last.lon, p.lat, p.lon)
        val moved = d >= minDistanceM
        val slowButMoving = d > jitterM && tSec - lastT >= maxIntervalS
        if (!moved && !slowButMoving) return false
        points += p; lengthM += d; lastT = tSec
        return true
    }
}

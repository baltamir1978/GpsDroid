package es.altamirano.gps.core

import java.time.Instant
import kotlin.math.max

/** Un punto del perfil de altitud: a qué distancia del inicio y a qué altura. */
data class ProfilePoint(val distanceM: Double, val eleM: Double)

/**
 * Lo que se cuenta de un track en su ficha. Lo que el GPX no trae (horas, altitudes) sale null: un
 * track importado de una ruta no tiene horas, y algunos no tienen altitud.
 */
data class TrackStats(
    val distanceM: Double,
    val durationS: Long?,      // del primer al último punto con hora
    val movingS: Long?,        // sólo los tramos en los que te movías
    val ascentM: Double,
    val descentM: Double,
    val minEleM: Double?,
    val maxEleM: Double?,
    val startEleM: Double?,
    val endEleM: Double?,
    val profile: List<ProfilePoint>,
) {
    /** Velocidad media en movimiento, km/h (lo que interesa andando: las paradas no cuentan). */
    val movingSpeedKmh: Double? get() = movingS?.takeIf { it > 0 }?.let { distanceM / it * 3.6 }

    /** Ritmo en movimiento, segundos por km. */
    val paceSPerKm: Double? get() = movingS?.takeIf { it > 0 && distanceM > 50 }?.let { it / (distanceM / 1000.0) }

    companion object {
        /**
         * Por debajo de esta velocidad entre dos puntos, el tramo es una parada. El grabador no
         * guarda puntos estando quieto, así que una parada larga es un salto de tiempo con poca
         * distancia: con 0,5 m/s (1,8 km/h) cae fuera hasta una subida muy empinada a paso lento.
         */
        const val MOVING_MIN_SPEED_MS = 0.5

        /** Puntos del perfil como mucho: más no se ven en la pantalla del móvil y pintarlos cuesta. */
        const val PROFILE_MAX_POINTS = 400

        fun of(points: List<TrackPoint>, climbThresholdM: Double = 3.0): TrackStats {
            val times = points.map { p -> p.time?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() } }
            val firstT = times.firstOrNull { it != null }
            val lastT = times.lastOrNull { it != null }
            val duration = if (firstT != null && lastT != null && lastT > firstT) lastT - firstT else null

            var dist = 0.0
            var moving = 0L
            var anyPair = false
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) {
                val a = points[i - 1]; val b = points[i]
                val d = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
                dist += d
                cumulative[i] = dist
                val ta = times[i - 1]; val tb = times[i]
                if (ta != null && tb != null && tb > ta) {
                    anyPair = true
                    if (d / (tb - ta) >= MOVING_MIN_SPEED_MS) moving += tb - ta
                }
            }

            val eles = points.mapNotNull { it.ele }
            val (up, down) = climb(eles, climbThresholdM)
            val profile = points.indices.filter { points[it].ele != null }
                .map { ProfilePoint(cumulative[it], points[it].ele!!) }
                .let { downsample(it, PROFILE_MAX_POINTS) }

            return TrackStats(
                distanceM = dist,
                durationS = duration,
                movingS = if (anyPair) moving else null,
                ascentM = up, descentM = down,
                minEleM = eles.minOrNull(), maxEleM = eles.maxOrNull(),
                startEleM = eles.firstOrNull(), endEleM = eles.lastOrNull(),
                profile = profile,
            )
        }

        /**
         * Subida y bajada acumuladas con histéresis: un cambio sólo cuenta cuando pasa de [threshold]
         * desde el último punto de referencia, para que el ruido de la altitud del GPS (±5 m a
         * menudo) no sume cientos de metros falsos en un llano. Igual que [Geo.ascentM].
         */
        fun climb(eles: List<Double>, threshold: Double): Pair<Double, Double> {
            var up = 0.0; var down = 0.0
            var ref: Double? = null
            for (e in eles) {
                val r = ref
                if (r == null) ref = e
                else if (e - r >= threshold) { up += e - r; ref = e }
                else if (r - e >= threshold) { down += r - e; ref = e }
            }
            return up to down
        }

        /**
         * Reduce el perfil a [maxPoints] tramos iguales de distancia; en cada tramo se quedan el punto
         * más alto y el más bajo (en su orden), para que una cima o un collado no desaparezcan.
         */
        fun downsample(points: List<ProfilePoint>, maxPoints: Int): List<ProfilePoint> {
            if (points.size <= maxPoints || points.size < 3) return points
            val total = points.last().distanceM - points.first().distanceM
            if (total <= 0) return listOf(points.first(), points.last())
            val buckets = max(1, maxPoints / 2)
            val out = ArrayList<ProfilePoint>(maxPoints + 2)
            out += points.first()
            var i = 1
            for (b in 0 until buckets) {
                val end = points.first().distanceM + total * (b + 1) / buckets
                var lo: ProfilePoint? = null; var hi: ProfilePoint? = null
                var loIdx = -1; var hiIdx = -1
                while (i < points.size - 1 && points[i].distanceM <= end) {
                    val p = points[i]
                    if (lo == null || p.eleM < lo.eleM) { lo = p; loIdx = i }
                    if (hi == null || p.eleM > hi.eleM) { hi = p; hiIdx = i }
                    i++
                }
                if (lo != null && hi != null) {
                    if (loIdx == hiIdx) out += lo
                    else if (loIdx < hiIdx) { out += lo; out += hi } else { out += hi; out += lo }
                }
            }
            out += points.last()
            return out
        }

        /** "1,8 km/h"; "4:52 /km". */
        fun formatSpeed(kmh: Double): String = "%.1f km/h".format(java.util.Locale.ROOT, kmh).replace('.', ',')
        fun formatPace(sPerKm: Double): String = Geo.formatDuration(sPerKm.toLong()) + " /km"
    }
}

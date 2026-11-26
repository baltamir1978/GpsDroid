package es.altamirano.gps.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Un track preparado para seguirlo: distancias y desniveles acumulados desde el inicio, para saber
 * en cada posición cuánto queda sin recorrer la lista entera. El desnivel acumulado usa el mismo
 * umbral que [Geo.ascentM] (3 m), así lo que queda cuadra con la ficha del track.
 */
class FollowRoute(val points: List<TrackPoint>, climbThresholdM: Double = 3.0) {
    /** Distancia desde el inicio hasta cada punto. */
    val cumulative = DoubleArray(points.size)
    /** Subida y bajada acumuladas hasta cada punto (con histéresis, como la ficha). */
    private val cumAscent = DoubleArray(points.size)
    private val cumDescent = DoubleArray(points.size)
    val totalM: Double
    val hasElevation = points.any { it.ele != null }

    init {
        var d = 0.0
        for (i in 1 until points.size) {
            d += Geo.distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            cumulative[i] = d
        }
        totalM = d
        var up = 0.0; var down = 0.0
        var ref: Double? = null
        for (i in points.indices) {
            val e = points[i].ele
            if (e != null) {
                val r = ref
                if (r == null) ref = e
                else if (e - r >= climbThresholdM) { up += e - r; ref = e }
                else if (r - e >= climbThresholdM) { down += r - e; ref = e }
            }
            cumAscent[i] = up; cumDescent[i] = down
        }
    }

    fun reversed() = FollowRoute(points.reversed())

    /** Lo que queda de subida y bajada desde el punto [i] hasta el final. */
    fun remainingAscentFrom(i: Int) = cumAscent.last() - cumAscent[i]
    fun remainingDescentFrom(i: Int) = cumDescent.last() - cumDescent[i]

    /** La altitud del track a [alongM] del inicio (interpolada), o null si no la trae. */
    fun elevationAt(alongM: Double): Double? {
        if (!hasElevation) return null
        val i = segmentAt(alongM)
        val a = points[i].ele; val b = points.getOrNull(i + 1)?.ele
        if (a == null || b == null) return a ?: b
        val len = cumulative.getOrElse(i + 1) { cumulative[i] } - cumulative[i]
        val f = if (len > 0) ((alongM - cumulative[i]) / len).coerceIn(0.0, 1.0) else 0.0
        return a + (b - a) * f
    }

    private fun segmentAt(alongM: Double): Int {
        var lo = 0; var hi = points.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (cumulative[mid] <= alongM) lo = mid else hi = mid - 1 }
        return lo.coerceAtMost((points.size - 2).coerceAtLeast(0))
    }

    /**
     * Dónde caes sobre el track: el punto más cercano de sus tramos (proyección perpendicular) y a
     * qué distancia del inicio está. Con [hintM] (dónde ibas) sólo se mira cerca de ahí, para que en
     * un track de ida y vuelta, o con un tramo que pasa junto a otro, no se salte al otro lado; si
     * ahí no hay nada cerca, se mira el track entero.
     */
    fun locate(lat: Double, lon: Double, hintM: Double? = null): Match? {
        if (points.isEmpty()) return null
        if (points.size == 1) {
            val p = points[0]
            return Match(Geo.distanceM(lat, lon, p.lat, p.lon), 0.0, 0, p.lat, p.lon)
        }
        if (hintM != null) {
            val near = search(lat, lon, hintM - BACK_WINDOW_M, hintM + AHEAD_WINDOW_M, hintM)
            if (near != null && near.offM <= REJOIN_M) return near
        }
        return search(lat, lon, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
    }

    private fun search(lat: Double, lon: Double, fromM: Double, toM: Double, hintM: Double? = null): Match? {
        // Plano local alrededor de la posición: en tramos de un track el error es despreciable.
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        val found = ArrayList<Match>()
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < fromM || cumulative[i] > toM) continue
            val a = points[i]; val b = points[i + 1]
            val ax = (a.lon - lon) * kx; val ay = (a.lat - lat) * ky
            val bx = (b.lon - lon) * kx; val by = (b.lat - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx; val py = ay + t * dy
            val off = hypot(px, py)
            val segLen = cumulative[i + 1] - cumulative[i]
            found += Match(off, cumulative[i] + t * segLen, i, a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
        }
        val best = found.minByOrNull { it.offM } ?: return null
        if (hintM == null) {
            // Sin saber por dónde ibas, el más cercano; pero si el track pasa casi igual de cerca por
            // otro sitio bien distinto (un circular que acaba donde empieza, un ida y vuelta), el que
            // va antes: se sigue desde el principio. Sólo cuentan pasadas separadas: en un tramo recto
            // los puntos vecinos también están «casi igual de cerca» y no son otra pasada.
            return found.filter { it.offM <= best.offM + TIE_M && abs(it.alongM - best.alongM) > OTHER_PASS_M }
                .plus(best).minBy { it.alongM }
        }
        // Sabiéndolo, el más cercano castigando ir hacia atrás (más allá del ruido del GPS) y, muy
        // poco, alejarse de donde ibas: en la vuelta de un ida y vuelta gana el tramo de vuelta, que
        // está igual de cerca que el de ida, y en el giro no se vuelve al de ida.
        return found.minBy { m ->
            m.offM + (hintM - JITTER_BACK_M - m.alongM).coerceAtLeast(0.0) + AWAY_WEIGHT * abs(m.alongM - hintM)
        }
    }

    /** El punto del track más cercano: a qué distancia estás, cuánto llevas, en qué tramo y dónde. */
    data class Match(val offM: Double, val alongM: Double, val segment: Int, val lat: Double, val lon: Double)

    private companion object {
        const val BACK_WINDOW_M = 300.0
        const val AHEAD_WINDOW_M = 1500.0
        const val REJOIN_M = 60.0
        const val TIE_M = 20.0
        const val OTHER_PASS_M = 300.0
        const val JITTER_BACK_M = 30.0
        const val AWAY_WEIGHT = 0.05
    }
}

/** En qué situación estás respecto al track que sigues. */
enum class FollowStatus {
    /** Aún no has llegado al track (vas hacia él desde donde aparcaste, por ejemplo). */
    APPROACHING,
    ON_TRACK,
    OFF_TRACK,
    ARRIVED,
}

/** Lo que pasó con la última posición y merece un aviso (vibrar, sonar). */
enum class FollowEvent { LEFT_TRACK, BACK_ON_TRACK, ARRIVED }

/** Lo que se enseña mientras se sigue un track. */
data class FollowFix(
    val status: FollowStatus,
    val match: FollowRoute.Match,
    val remainingM: Double,
    val remainingAscentM: Double?,
    val remainingDescentM: Double?,
    /** Segundos que faltan al ritmo que llevas desde que entraste en el track; null hasta saberlo. */
    val remainingS: Long?,
    /** Hacia dónde queda el punto más cercano del track (para volver a él). */
    val bearingToTrack: Double,
)

/**
 * Sigue un track posición a posición. Para no avisar por el ruido del GPS, salirse pide
 * [offTrackM] (o más, si la posición viene con peor precisión) en dos posiciones seguidas y volver
 * baja de [backOnTrackM]; el aviso de salirse sólo salta si ya habías estado en el track. Las
 * posiciones con más de [maxAccuracyM] de error no cuentan.
 */
class TrackFollower(
    val route: FollowRoute,
    private val offTrackM: Double = 40.0,
    private val backOnTrackM: Double = 25.0,
    private val arriveM: Double = 30.0,
    private val maxAccuracyM: Double = 50.0,
) {
    var status = FollowStatus.APPROACHING
        private set
    var last: FollowFix? = null
        private set
    private var alongM: Double? = null
    private var farCount = 0
    private var started: Pair<Double, Double>? = null   // (segundos, metros recorridos) al entrar en el track

    /** [tSec]: segundos de un reloj monótono. Devuelve el aviso que toque, si alguno. */
    fun update(lat: Double, lon: Double, accuracyM: Double?, tSec: Double): FollowEvent? {
        if (accuracyM != null && accuracyM > maxAccuracyM) return null
        val m = route.locate(lat, lon, alongM) ?: return null
        val limit = maxOf(offTrackM, (accuracyM ?: 0.0).coerceAtMost(maxAccuracyM))
        var event: FollowEvent? = null
        val remaining = (route.totalM - m.alongM).coerceAtLeast(0.0)
        val end = route.points.last()
        // Junto al último punto y con el track ya andado (en un circular el final está junto a la
        // salida: al empezar, lo que queda es todo el track).
        val atEnd = (m.offM <= backOnTrackM && remaining <= arriveM) ||
            (Geo.distanceM(lat, lon, end.lat, end.lon) <= arriveM && remaining <= 3 * arriveM)

        when (status) {
            FollowStatus.ARRIVED -> {}
            FollowStatus.APPROACHING -> if (m.offM <= backOnTrackM) {
                status = FollowStatus.ON_TRACK; farCount = 0
                started = tSec to m.alongM
            }
            FollowStatus.ON_TRACK -> if (m.offM > limit) {
                if (++farCount >= 2) { status = FollowStatus.OFF_TRACK; event = FollowEvent.LEFT_TRACK }
            } else farCount = 0
            FollowStatus.OFF_TRACK -> if (m.offM <= backOnTrackM) {
                status = FollowStatus.ON_TRACK; farCount = 0; event = FollowEvent.BACK_ON_TRACK
            }
        }
        // Llegar sólo cuenta si se ha andado el track (no al empezar un circular junto al final).
        if (status != FollowStatus.ARRIVED && atEnd && (started != null || route.totalM <= arriveM)) {
            status = FollowStatus.ARRIVED; event = FollowEvent.ARRIVED
        }
        // Lejos del track no se recuerda por dónde ibas: al volver puede ser por otro sitio.
        alongM = if (m.offM <= REMEMBER_M) m.alongM else alongM

        val i = (m.segment + 1).coerceAtMost(route.points.size - 1)
        val s = started
        val remainingS = if (s != null && status != FollowStatus.ARRIVED) {
            val dt = tSec - s.first; val done = m.alongM - s.second
            if (dt >= MIN_ETA_S && done >= MIN_ETA_M) (remaining / (done / dt)).toLong() else null
        } else null
        last = FollowFix(status, m, if (status == FollowStatus.ARRIVED) 0.0 else remaining,
            if (route.hasElevation) route.remainingAscentFrom(i) else null,
            if (route.hasElevation) route.remainingDescentFrom(i) else null,
            remainingS, Direction.bearingDeg(lat, lon, m.lat, m.lon))
        return event
    }

    private companion object {
        const val REMEMBER_M = 60.0
        const val MIN_ETA_S = 120.0
        const val MIN_ETA_M = 200.0
    }
}

/** Textos de la altitud de un punto respecto a la tuya. */
object Altitude {
    /** "1.245 m" (con punto de millares). */
    fun format(m: Double): String = OfflineZone.formatCount(Math.round(m)) + " m"

    /**
     * "230 m más arriba", "85 m más abajo" o "a tu altura" (menos de 10 m: el GPS no afina más en
     * vertical). null si falta alguna de las dos altitudes.
     */
    fun relative(pointEle: Double?, myEle: Double?): String? {
        if (pointEle == null || myEle == null) return null
        val d = pointEle - myEle
        return when {
            abs(d) < 10 -> "a tu altura"
            d > 0 -> "${format(d)} más arriba"
            else -> "${format(-d)} más abajo"
        }
    }
}

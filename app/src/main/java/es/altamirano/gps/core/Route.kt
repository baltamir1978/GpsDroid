package es.altamirano.gps.core

import kotlin.math.roundToInt

/**
 * Los datos de rutas de BRouter van en cuadros de 5° × 5° (`segments4/W5_N40.rd5`: de 5° O a 0° y
 * de 40° N a 45° N, Madrid y media España central). Cada cuadro se nombra por su esquina suroeste.
 */
object RouteTiles {
    fun nameAt(lat: Double, lon: Double): String {
        val lon5 = Math.floorDiv(kotlin.math.floor(lon).toInt(), 5) * 5
        val lat5 = Math.floorDiv(kotlin.math.floor(lat).toInt(), 5) * 5
        return (if (lon5 < 0) "W${-lon5}" else "E$lon5") + "_" + (if (lat5 < 0) "S${-lat5}" else "N$lat5")
    }

    /**
     * Los cuadros que hacen falta para ir de un sitio a otro: los del rectángulo que los abarca, con
     * un margen (una ruta puede dar un rodeo por el cuadro de al lado).
     */
    fun between(a: Pair<Double, Double>, b: Pair<Double, Double>, marginDeg: Double = 0.1): List<String> {
        val lats = listOf(minOf(a.first, b.first) - marginDeg, maxOf(a.first, b.first) + marginDeg)
        val lons = listOf(minOf(a.second, b.second) - marginDeg, maxOf(a.second, b.second) + marginDeg)
        val out = LinkedHashSet<String>()
        var lat = lats[0]
        while (true) {
            var lon = lons[0]
            while (true) {
                out += nameAt(lat, lon)
                if (lon >= lons[1]) break
                lon = minOf(lon + 5.0, lons[1])
            }
            if (lat >= lats[1]) break
            lat = minOf(lat + 5.0, lats[1])
        }
        return out.toList()
    }
}

/** Cómo se va: cada modo con su perfil de BRouter y a qué distancias se avisa de un giro. */
enum class TravelMode(
    val label: String,
    /** Perfil de BRouter (`assets/brouter/<nombre>.brf`, de su `misc/profiles2`). */
    val profile: String,
    /** Avisos antes de cada giro: lejos, cerca y ya (metros). */
    val announceM: List<Double>,
) {
    FOOT("A pie", "hiking-mountain", listOf(120.0, 40.0, 12.0)),
    BIKE("En bici", "trekking", listOf(300.0, 80.0, 20.0)),
    CAR("En coche", "car-vario", listOf(800.0, 250.0, 50.0));

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.name == k } ?: FOOT
    }
}

/**
 * Una maniobra de la ruta, tal como la da BRouter (`voicehints`): en qué punto de la geometría,
 * qué hay que hacer ([Maneuver.Kind]), la salida si es una rotonda y el ángulo del giro.
 */
data class Maneuver(val index: Int, val kind: Kind, val exit: Int = 0, val angle: Int = 0) {
    /**
     * Los códigos de BRouter (`VoiceHint`; en JSON con `timode=2`, salir por la izquierda o la
     * derecha llegan como 17 y 18 en vez de «mantente»).
     */
    enum class Kind(val code: Int) {
        STRAIGHT(1), LEFT(2), SLIGHT_LEFT(3), SHARP_LEFT(4), RIGHT(5), SLIGHT_RIGHT(6), SHARP_RIGHT(7),
        KEEP_LEFT(8), KEEP_RIGHT(9), UTURN_LEFT(10), UTURN_RIGHT(11), OFF_ROUTE(12),
        ROUNDABOUT(13), ROUNDABOUT_LEFT(14), UTURN(15), BEELINE(16), EXIT_LEFT(17), EXIT_RIGHT(18), END(100);

        /** Hacia qué lado (para la flecha): −1 izquierda, 1 derecha, 0 recto. */
        val side: Int get() = when (this) {
            LEFT, SLIGHT_LEFT, SHARP_LEFT, KEEP_LEFT, UTURN_LEFT, EXIT_LEFT, UTURN, ROUNDABOUT_LEFT -> -1
            RIGHT, SLIGHT_RIGHT, SHARP_RIGHT, KEEP_RIGHT, UTURN_RIGHT, EXIT_RIGHT, ROUNDABOUT -> 1
            else -> 0
        }

        companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
    }

    /** "gira a la izquierda", "en la rotonda, toma la 2.ª salida" (en minúscula: va detrás de «En 200 m,»). */
    fun phrase(): String = when (kind) {
        Kind.STRAIGHT -> "sigue recto"
        Kind.LEFT -> "gira a la izquierda"
        Kind.SLIGHT_LEFT -> "gira ligeramente a la izquierda"
        Kind.SHARP_LEFT -> "gira muy cerrado a la izquierda"
        Kind.RIGHT -> "gira a la derecha"
        Kind.SLIGHT_RIGHT -> "gira ligeramente a la derecha"
        Kind.SHARP_RIGHT -> "gira muy cerrado a la derecha"
        Kind.KEEP_LEFT -> "mantente a la izquierda"
        Kind.KEEP_RIGHT -> "mantente a la derecha"
        Kind.UTURN_LEFT, Kind.UTURN_RIGHT, Kind.UTURN -> "da media vuelta"
        Kind.OFF_ROUTE -> "sal del camino"
        Kind.ROUNDABOUT, Kind.ROUNDABOUT_LEFT ->
            if (exit != 0) "en la rotonda, toma la ${kotlin.math.abs(exit)}.ª salida" else "entra en la rotonda"
        Kind.BEELINE -> "sigue campo a través"
        Kind.EXIT_LEFT -> "sal por la izquierda"
        Kind.EXIT_RIGHT -> "sal por la derecha"
        Kind.END -> "has llegado"
    }

    /** Con mayúscula, para la pantalla. */
    fun title(): String = phrase().replaceFirstChar { it.uppercase() }
}

/** Una ruta calculada: la geometría (con altitud si la trae), las maniobras y lo que dice BRouter de ella. */
data class PlannedRoute(
    val points: List<TrackPoint>,
    val maneuvers: List<Maneuver>,
    /** Segundos que calcula BRouter (a su ritmo según el perfil); null si no lo da. */
    val totalS: Long?,
    /** En coche, calculada dejando ir por pistas de tierra (la normal no llegaba cerca del destino). */
    val offroad: Boolean = false,
) {
    /** Lo que queda en línea recta desde el final de la ruta hasta (lat, lon): el tramo sin camino conocido. */
    fun gapToM(lat: Double, lon: Double): Double = points.last().let { Geo.distanceM(it.lat, it.lon, lat, lon) }
}

/**
 * La respuesta de BRouter en GeoJSON (`trackFormat=json`): coordenadas `[lon, lat, ele]` de la
 * línea y `voicehints` `[índice, código, salida, distancia al siguiente, ángulo]`. Sin librería
 * JSON: el formato lo escribe BRouter siempre igual. Cualquier otra cosa es un mensaje de error.
 */
object BRouterJson {
    private val number = """-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?"""

    fun parse(text: String): PlannedRoute? {
        val t = text.trim()
        if (!t.startsWith("{")) return null
        val geom = t.indexOf("\"LineString\"").takeIf { it >= 0 } ?: return null
        val coordsStart = t.indexOf("\"coordinates\"", geom).takeIf { it >= 0 } ?: return null
        val open = t.indexOf('[', coordsStart)
        val close = matching(t, open) ?: return null
        val points = Regex("""\[\s*($number)\s*,\s*($number)\s*(?:,\s*($number)\s*)?]""").findAll(t.substring(open + 1, close)).map { m ->
            TrackPoint(m.groupValues[2].toDouble(), m.groupValues[1].toDouble(), m.groupValues[3].toDoubleOrNull())
        }.toList()
        if (points.size < 2) return null

        val maneuvers = ArrayList<Maneuver>()
        val vh = t.indexOf("\"voicehints\"")
        if (vh >= 0) {
            val vOpen = t.indexOf('[', vh)
            val vClose = matching(t, vOpen)
            if (vClose != null) {
                Regex("""\[([^\[\]]*)]""").findAll(t.substring(vOpen + 1, vClose)).forEach { m ->
                    val f = m.groupValues[1].split(',').map { it.trim() }
                    val idx = f.getOrNull(0)?.toIntOrNull() ?: return@forEach
                    val kind = f.getOrNull(1)?.toIntOrNull()?.let { Maneuver.Kind.of(it) } ?: return@forEach
                    val exit = f.getOrNull(2)?.toIntOrNull() ?: 0
                    val angle = f.getOrNull(4)?.toDoubleOrNull()?.toInt() ?: 0
                    if (idx in points.indices) maneuvers += Maneuver(idx, kind, exit, angle)
                }
            }
        }
        val time = Regex(""""total-time"\s*:\s*"?(\d+)""").find(t)?.groupValues?.get(1)?.toLongOrNull()
        return PlannedRoute(points, maneuvers.sortedBy { it.index }, time?.takeIf { it > 0 })
    }

    /** El `]` que cierra el `[` de [open], o null. */
    private fun matching(s: String, open: Int): Int? {
        if (open < 0) return null
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) { '[' -> depth++; ']' -> if (--depth == 0) return i }
        }
        return null
    }
}

/** Lo que hay que hacer a continuación: la maniobra y a cuántos metros queda. */
data class NextManeuver(val maneuver: Maneuver, val distanceM: Double, val then: Maneuver?)

/**
 * Las indicaciones mientras se sigue una ruta: cuál es la siguiente maniobra y cuándo decirla. Cada
 * maniobra se anuncia como mucho tres veces ([TravelMode.announceM]: lejos, cerca y ya); si dos
 * maniobras van muy juntas, la segunda se dice con la primera («…y luego gira a la derecha»).
 */
class RouteGuide(route: PlannedRoute, private val announceM: List<Double>) {
    val follow = FollowRoute(route.points)
    // «Sigue recto» no se anuncia: BRouter lo pone en cada cruce que se pasa de largo (en una ruta
    // a pie por el centro de Madrid, 6 de 22 indicaciones) y sólo metería ruido.
    private val maneuvers = route.maneuvers.filter { it.kind != Maneuver.Kind.END && it.kind != Maneuver.Kind.STRAIGHT }
        .map { it to follow.cumulative[it.index] }
    /** Etapa ya dicha de cada maniobra (índice en [announceM]; más alto = más cerca). */
    private val spoken = HashMap<Int, Int>()

    /** La siguiente maniobra por delante de [alongM] (con 5 m de margen por el ruido del GPS). */
    fun next(alongM: Double): NextManeuver? {
        val i = maneuvers.indexOfFirst { it.second > alongM + 5 }
        if (i < 0) return null
        val (m, at) = maneuvers[i]
        val then = maneuvers.getOrNull(i + 1)?.takeIf { it.second - at <= THEN_M }?.first
        return NextManeuver(m, at - alongM, then)
    }

    /** Lo que hay que decir ahora, o null. Llamar con cada posición (estando en la ruta). */
    fun announce(alongM: Double): String? {
        val n = next(alongM) ?: return null
        // La etapa más cercana ya alcanzada: si se salta de lejos a ya, sólo se dice «ya».
        val stage = announceM.indices.lastOrNull { n.distanceM <= announceM[it] } ?: return null
        val key = n.maneuver.index
        if ((spoken[key] ?: -1) >= stage) return null
        spoken[key] = stage
        val now = stage == announceM.lastIndex
        val head = if (now) n.maneuver.title() else "En ${spokenDistance(n.distanceM)}, ${n.maneuver.phrase()}"
        return head + (n.then?.let { " y luego ${it.phrase()}" } ?: "")
    }

    companion object {
        const val THEN_M = 60.0

        /** "200 metros", "1,5 kilómetros": redondeado como se dice (de 10 en 10, de 50 en 50…). */
        fun spokenDistance(m: Double): String = when {
            m < 200 -> "${(m / 10).roundToInt().coerceAtLeast(1) * 10} metros"
            m < 1000 -> "${(m / 50).roundToInt() * 50} metros".let { if (it == "1000 metros") "1 kilómetro" else it }
            else -> {
                val km = (m / 100).roundToInt() / 10.0
                if (km == km.toInt().toDouble()) "${km.toInt()} ${if (km.toInt() == 1) "kilómetro" else "kilómetros"}"
                else "${km.toString().replace('.', ',')} kilómetros"
            }
        }
    }
}

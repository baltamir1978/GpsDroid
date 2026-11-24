package es.altamirano.gps.core

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Un punto guardado. [id] sólo vale dentro de la sesión (el GPX no lo lleva): sirve de clave en las listas. */
data class Waypoint(val id: Long, val name: String, val lat: Double, val lon: Double, val ele: Double? = null, val time: String? = null)

/** Hacia dónde queda un punto: rumbo, punto cardinal y, si se sabe hacia dónde miras o vas, a qué mano. */
object Direction {
    private fun rad(d: Double) = d * Math.PI / 180.0

    /** Rumbo inicial del círculo máximo de 1 a 2, en grados desde el norte (0 a 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dl = rad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return norm(Math.toDegrees(atan2(y, x)))
    }

    fun norm(deg: Double) = ((deg % 360.0) + 360.0) % 360.0

    private val cardinals = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    private val cardinalNames = listOf("norte", "noreste", "este", "sureste", "sur", "suroeste", "oeste", "noroeste")

    private fun sector(deg: Double) = ((norm(deg) + 22.5) / 45.0).toInt() % 8

    /** "NE", con la O de oeste. */
    fun cardinal(deg: Double) = cardinals[sector(deg)]

    /** "noreste" (para decirlo de viva voz o en letra grande). */
    fun cardinalName(deg: Double) = cardinalNames[sector(deg)]

    /** Ángulo del punto respecto a hacia dónde miras: de −180 (a tu izquierda, detrás) a 180. */
    fun relativeDeg(bearing: Double, heading: Double): Double {
        val d = norm(bearing - heading)
        return if (d > 180) d - 360 else d
    }

    /** "de frente", "a tu derecha", "detrás a la izquierda"… en ocho sectores. */
    fun relative(bearing: Double, heading: Double): String {
        val r = relativeDeg(bearing, heading)
        val a = kotlin.math.abs(r)
        val side = if (r > 0) "derecha" else "izquierda"
        return when {
            a < 22.5 -> "de frente"
            a < 67.5 -> "delante a la $side"
            a < 112.5 -> "a tu $side"
            a < 157.5 -> "detrás a la $side"
            else -> "detrás"
        }
    }

    /** "1,2 km al NE · 47°". */
    fun describe(distanceM: Double, bearing: Double): String =
        "${Geo.formatDistance(distanceM)} al ${cardinal(bearing)} · ${bearing.roundToInt() % 360}°"
}

/** Los puntos en GPX (`<wpt>`), como los guardan y leen las demás apps de mapas. */
object WaypointGpx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun write(points: List<Waypoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GpsDroid\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in points) {
            append("  <wpt lat=\"%.7f\" lon=\"%.7f\">\n".format(Locale.ROOT, w.lat, w.lon))
            w.ele?.let { append("    <ele>%.1f</ele>\n".format(Locale.ROOT, it)) }
            w.time?.let { append("    <time>").append(it).append("</time>\n") }
            append("    <name>").append(esc(w.name)).append("</name>\n")
            append("  </wpt>\n")
        }
        append("</gpx>\n")
    }

    private val wptRe = Regex("""<wpt\b([^>]*?)(/>|>(.*?)</wpt>)""", RegexOption.DOT_MATCHES_ALL)
    private fun attr(attrs: String, name: String) =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(attrs)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()
    private fun elem(body: String, name: String) =
        Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim()

    /** Los `<wpt>` de un GPX cualquiera; los que no traen nombre salen como «Punto N». [firstId]: el id del primero. */
    fun parse(text: String, firstId: Long = 1): List<Waypoint> = wptRe.findAll(text).mapNotNull { m ->
        val lat = attr(m.groupValues[1], "lat") ?: return@mapNotNull null
        val lon = attr(m.groupValues[1], "lon") ?: return@mapNotNull null
        val body = m.groupValues[3]
        val name = elem(body, "name")?.let { unesc(it.removePrefix("<![CDATA[").removeSuffix("]]>")) }?.trim()
        Waypoint(0, name.orEmpty(), lat, lon, elem(body, "ele")?.toDoubleOrNull(), elem(body, "time"))
    }.toList().mapIndexed { i, w -> w.copy(id = firstId + i, name = w.name.ifBlank { "Punto ${i + 1}" }) }

    /** «Punto N» con el primer N libre, para no tener que escribir nada al guardar deprisa. */
    fun nextName(existing: List<Waypoint>): String {
        val used = existing.mapNotNull { Regex("""Punto (\d+)""").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        return "Punto " + generateSequence(1) { it + 1 }.first { it !in used }
    }
}

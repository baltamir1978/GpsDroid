package es.altamirano.gps.core

import java.util.Locale

data class TrackPoint(val lat: Double, val lon: Double, val ele: Double? = null, val time: String? = null)

data class Track(val name: String, val points: List<TrackPoint>)

/**
 * GPX 1.1 con un único `<trk>` y un `<trkseg>` (lo que escribe ZeroGPS, para que los tracks pasen
 * de un aparato a otro tal cual). El lector es tolerante y no es un parser XML completo: junta los
 * `<trkpt>` de todos los segmentos (y los `<rtept>` si no hay ninguno, para abrir rutas), con
 * comillas simples o dobles.
 */
object Gpx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun write(track: Track): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GpsDroid\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk>\n")
        if (track.name.isNotEmpty()) append("    <name>").append(esc(track.name)).append("</name>\n")
        append("    <trkseg>\n")
        for (p in track.points) {
            append("      <trkpt lat=\"%.7f\" lon=\"%.7f\">\n".format(Locale.ROOT, p.lat, p.lon))
            p.ele?.let { append("        <ele>%.1f</ele>\n".format(Locale.ROOT, it)) }
            p.time?.let { append("        <time>").append(it).append("</time>\n") }
            append("      </trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private val pointRe = Regex("""<(trkpt|rtept)\b([^>]*?)(/>|>(.*?)</\1>)""", RegexOption.DOT_MATCHES_ALL)
    private fun attr(attrs: String, name: String) =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(attrs)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()
    private fun elem(body: String, name: String) =
        Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim()

    /** null si no es un GPX. El nombre: el primer `<name>` antes del primer punto, o [fallbackName]. */
    fun parse(text: String, fallbackName: String = ""): Track? {
        if (!text.contains("<gpx")) return null
        val matches = pointRe.findAll(text).toList()
        val kind = if (matches.any { it.groupValues[1] == "trkpt" }) "trkpt" else "rtept"
        val points = matches.filter { it.groupValues[1] == kind }.mapNotNull { m ->
            val lat = attr(m.groupValues[2], "lat") ?: return@mapNotNull null
            val lon = attr(m.groupValues[2], "lon") ?: return@mapNotNull null
            val body = m.groupValues[4]
            TrackPoint(lat, lon, elem(body, "ele")?.toDoubleOrNull(), elem(body, "time"))
        }
        val firstPoint = matches.firstOrNull()?.range?.first ?: text.length
        val namePos = text.indexOf("<name>")
        val name = if (namePos in 0 until firstPoint) elem(text.substring(namePos), "name")?.let(::unesc) else null
        return Track(name?.takeIf { it.isNotBlank() } ?: fallbackName, points)
    }
}

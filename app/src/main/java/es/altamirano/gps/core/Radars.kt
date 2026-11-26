package es.altamirano.gps.core

import java.util.Locale

/**
 * Un radar de la DGT: fijo (una cabina) o de tramo (velocidad media: de [lat],[lon] a [endLat],[endLon]).
 * La DGT no publica ni el sentido ni el límite, sólo dónde está y en qué carretera.
 */
data class Radar(val section: Boolean, val lat: Double, val lon: Double, val road: String,
                 val endLat: Double? = null, val endLon: Double? = null)

/**
 * La lista oficial de la DGT (Punto de Acceso Nacional, DATEX II 1.0:
 * `nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml`): cabinas cinemómetro
 * (`Point`) y cinemómetros de velocidad media (`Linear`, de `from` a `to`). Sin Cataluña ni el País
 * Vasco, que tienen su propio servicio de tráfico. Sin librería XML: el formato es siempre el mismo.
 */
object RadarList {
    private val locRe = Regex("""<_0:predefinedLocation id="GUID_(CABINACINEMOMETRO|CVM)_[^"]*">(.*?)</_0:predefinedLocationName>(.*?)(?=<_0:predefinedLocation id="GUID_|</_0:predefinedLocationSet>)""",
        RegexOption.DOT_MATCHES_ALL)
    private val coordRe = Regex("""<_0:latitude>(-?[\d.]+)</_0:latitude>\s*<_0:longitude>(-?[\d.]+)</_0:longitude>""")
    private val roadRe = Regex("""<_0:roadNumber>([^<]+)</_0:roadNumber>""")
    private val linkRe = Regex("""<_0:value>([^<]+)</_0:value>\s*</_0:descriptor>\s*<_0:tpegDescriptorType>linkName""")

    fun parseDatex(xml: String): List<Radar> = locRe.findAll(xml).mapNotNull { m ->
        val body = m.groupValues[3]
        val coords = coordRe.findAll(body).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        val road = roadRe.find(body)?.groupValues?.get(1) ?: linkRe.find(body)?.groupValues?.get(1) ?: ""
        if (m.groupValues[1] == "CVM") {
            // En los tramos, `to` va antes que `from` en el XML: el principio es el segundo punto.
            if (coords.size < 2) null else Radar(true, coords[1].first, coords[1].second, road, coords[0].first, coords[0].second)
        } else coords.firstOrNull()?.let { Radar(false, it.first, it.second, road) }
    }.toList()

    /** Una línea por radar: `F|T;lat;lon;latFin;lonFin;carretera` (lo que se guarda en el móvil). */
    fun toCsv(list: List<Radar>): String = list.joinToString("\n") { r ->
        listOf(if (r.section) "T" else "F", "%.6f".format(Locale.ROOT, r.lat), "%.6f".format(Locale.ROOT, r.lon),
            r.endLat?.let { "%.6f".format(Locale.ROOT, it) } ?: "", r.endLon?.let { "%.6f".format(Locale.ROOT, it) } ?: "",
            r.road.replace(";", ",")).joinToString(";")
    }

    fun fromCsv(text: String): List<Radar> = text.lineSequence().mapNotNull { line ->
        val f = line.split(';')
        if (f.size < 6) return@mapNotNull null
        val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
        Radar(f[0] == "T", lat, lon, f[5], f[3].toDoubleOrNull(), f[4].toDoubleOrNull())
    }.toList()
}

/** Lo que se avisa de un radar. */
data class RadarAlert(val section: Boolean, val distanceM: Double, val road: String, val inside: Boolean = false) {
    /** «Radar a 500 metros», «Tramo de radar de velocidad media a 400 metros», «Entras en un tramo de radar». */
    fun spoken(): String = when {
        inside -> "Entras en un tramo de radar de velocidad media"
        section -> "Tramo de radar de velocidad media a ${RouteGuide.spokenDistance(distanceM)}"
        else -> "Radar a ${RouteGuide.spokenDistance(distanceM)}"
    }

    /** Para la píldora: «Radar a 350 m · A-2», «Tramo de radar · A-6». */
    fun line(): String = when {
        inside -> "En tramo de radar de velocidad media" + road.let { if (it.isNotBlank()) " · $it" else "" }
        else -> (if (section) "Tramo de radar" else "Radar") + " a ${Geo.formatDistance(distanceM)}" + road.let { if (it.isNotBlank()) " · $it" else "" }
    }
}

/**
 * Los radares de una ruta en coche: se sitúa cada uno sobre la ruta (si queda a más de [onRouteM]
 * no es de esta carretera y no cuenta) y, según avanzas, se dice el que viene: una vez al entrar en
 * [warnM] y, en los tramos, otra al entrar en el tramo; dentro del tramo la píldora lo recuerda.
 */
class RadarGuide(route: FollowRoute, radars: List<Radar>, private val warnM: Double = 500.0, onRouteM: Double = 40.0) {
    private data class OnRoute(val radar: Radar, val startM: Double, val endM: Double)

    private val ahead: List<OnRoute>
    private val warned = HashSet<Int>()
    private val entered = HashSet<Int>()

    init {
        // Sólo los que están cerca de algún tramo de la ruta (el rectángulo que la abarca, con margen).
        val pts = route.points
        val minLat = pts.minOf { it.lat } - 0.01; val maxLat = pts.maxOf { it.lat } + 0.01
        val minLon = pts.minOf { it.lon } - 0.01; val maxLon = pts.maxOf { it.lon } + 0.01
        ahead = radars.filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }.mapNotNull { r ->
            val a = route.locate(r.lat, r.lon) ?: return@mapNotNull null
            if (a.offM > onRouteM) return@mapNotNull null
            if (!r.section) return@mapNotNull OnRoute(r, a.alongM, a.alongM)
            val b = route.locate(r.endLat ?: return@mapNotNull null, r.endLon ?: return@mapNotNull null) ?: return@mapNotNull null
            // Un tramo cuenta si la ruta lo recorre entero y en su sentido (el final, después del principio).
            if (b.offM > onRouteM || b.alongM <= a.alongM) null else OnRoute(r, a.alongM, b.alongM)
        }.sortedBy { it.startM }
    }

    /** Cuántos radares hay en la ruta. */
    val count: Int get() = ahead.size

    /**
     * Con lo que llevas recorrido: el aviso que toca decir ahora (o null) y lo que se enseña (el radar
     * que viene a menos de [warnM], o el tramo en el que estás).
     */
    fun update(alongM: Double): Pair<RadarAlert?, RadarAlert?> {
        var say: RadarAlert? = null
        var show: RadarAlert? = null
        ahead.forEachIndexed { i, o ->
            val inside = o.radar.section && alongM >= o.startM && alongM <= o.endM
            if (inside) {
                val a = RadarAlert(true, 0.0, o.radar.road, inside = true)
                if (entered.add(i)) say = a
                if (show == null) show = a
                return@forEachIndexed
            }
            val d = o.startM - alongM
            if (d in 0.0..warnM) {
                val a = RadarAlert(o.radar.section, d, o.radar.road)
                if (warned.add(i) && say == null) say = a
                if (show == null) show = a
            }
        }
        return say to show
    }
}

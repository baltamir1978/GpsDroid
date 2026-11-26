package es.altamirano.gps.core

import java.util.Locale

/** Qué clase de radar es (lo que se puede activar o no en los ajustes). */
enum class RadarKind(val label: String) {
    /** Fijo (cabina, poste) o en remolque. */
    FIXED("Radar"),
    /** Tramo de velocidad media (de un punto a otro; algunos sólo traen el principio). */
    SECTION("Tramo de radar"),
    /** Radar móvil anunciado para un día concreto (León, Murcia, Donostia…). */
    MOBILE("Radar móvil"),
    /** Tramo en el que la DGT puede poner radares móviles (no quiere decir que haya uno hoy). */
    MOBILE_STRETCH("Tramo de radar móvil"),
    /** Cámara de semáforo en rojo (de los ayuntamientos; datos de OpenStreetMap). */
    RED_LIGHT("Cámara de semáforo"),
}

/**
 * Un radar: dónde, de qué clase, en qué carretera y, si se sabe, el límite, el sentido (rumbo en
 * grados de los coches que controla) y los días en que está (los móviles anunciados).
 */
data class Radar(
    val kind: RadarKind, val lat: Double, val lon: Double, val road: String,
    val endLat: Double? = null, val endLon: Double? = null,
    val maxspeed: Int? = null, val bearing: Double? = null,
    val validFrom: String? = null, val validTo: String? = null,
) {
    val isStretch: Boolean get() = kind == RadarKind.SECTION || kind == RadarKind.MOBILE_STRETCH

    /** Vale el día [isoDate] (yyyy-MM-dd): los móviles anunciados sólo su día; los demás, siempre. */
    fun validOn(isoDate: String): Boolean =
        (validFrom == null || isoDate >= validFrom) && (validTo == null || isoDate <= validTo)
}

/**
 * Las listas de radares. La principal es el GeoJSON de «Radares Anunciados»
 * (`geiserx.github.io/radares-anunciados-ha/feed.geojson`, ODbL): junta la DGT, el Servei Català de
 * Trànsit, Euskadi, ayuntamientos y OpenStreetMap, sin repetidos, cada 6 horas. La de respaldo es la
 * oficial de la DGT (Punto de Acceso Nacional, DATEX II 1.0), sin Cataluña ni el País Vasco.
 */
object RadarList {
    /** El GeoJSON de Radares Anunciados. Fuera lo que no sirve para avisar (multas antiguas, notas sin confirmar). */
    fun parseFeed(json: String): List<Radar> {
        val features = org.json.JSONObject(json).getJSONArray("features")
        val out = ArrayList<Radar>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val p = f.getJSONObject("properties")
            if (!p.optBoolean("active", true)) continue
            val g = f.optJSONObject("geometry") ?: continue
            val id = f.optString("id")
            val source = p.optString("source")
            val kind = when (p.optString("kind")) {
                "fixed", "trailer" -> RadarKind.FIXED
                // Los tramos con dos extremos vienen como «stretch» (la línea) y además como puntos
                // «-from»/«-to»: se usa la línea. Los que sólo traen un punto (Cataluña), ese punto.
                "section" -> if (id.endsWith("-from") || id.endsWith("-to")) continue else RadarKind.SECTION
                "stretch" -> if (source == "dgt_invive") RadarKind.MOBILE_STRETCH else RadarKind.SECTION
                "mobile_announced" -> RadarKind.MOBILE
                else -> continue
            }
            val coords = g.getJSONArray("coordinates")
            val (start, end) = when (g.optString("type")) {
                "Point" -> (coords.getDouble(1) to coords.getDouble(0)) to null
                "LineString" -> {
                    if (coords.length() < 2) continue
                    val a = coords.getJSONArray(0); val b = coords.getJSONArray(coords.length() - 1)
                    (a.getDouble(1) to a.getDouble(0)) to (b.getDouble(1) to b.getDouble(0))
                }
                else -> continue
            }
            out += Radar(kind, start.first, start.second, p.optString("road").ifBlank { roadFromName(p.optString("name")) },
                end?.first, end?.second,
                p.optInt("maxspeed", 0).takeIf { it > 0 },
                // El sentido sólo sirve si es un rumbo (OpenStreetMap lo da en grados); «MADRID» o «both», no.
                p.optString("direction").toDoubleOrNull()?.takeIf { it in 0.0..360.0 },
                p.optString("valid_from").takeIf { it.length >= 10 }?.take(10),
                p.optString("valid_to").takeIf { it.length >= 10 }?.take(10))
        }
        return out
    }

    /**
     * Las cámaras de semáforo de OpenStreetMap (respuesta de Overpass con `out body; >; out skel`):
     * relaciones `type=enforcement` + `enforcement=traffic_signals` (el semáforo vigilado es el
     * miembro `to` —o `force`—, la cámara el `device`, y de `from` a `to` va el sentido que controla)
     * y algún nodo suelto con `enforcement=traffic_signals`.
     */
    fun parseOverpassRedLights(json: String): List<Radar> {
        val els = org.json.JSONObject(json).getJSONArray("elements")
        val nodes = HashMap<Long, Pair<Double, Double>>()
        val loose = ArrayList<Radar>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "node" || !e.has("lat")) continue
            val pos = e.getDouble("lat") to e.getDouble("lon")
            nodes[e.getLong("id")] = pos
            if (e.optJSONObject("tags")?.optString("enforcement") == "traffic_signals")
                loose += Radar(RadarKind.RED_LIGHT, pos.first, pos.second, "")
        }
        val out = ArrayList<Radar>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "relation") continue
            val members = e.optJSONArray("members") ?: continue
            fun role(vararg r: String): Pair<Double, Double>? = (0 until members.length()).map { members.getJSONObject(it) }
                .firstOrNull { it.optString("type") == "node" && it.optString("role") in r && nodes.containsKey(it.getLong("ref")) }
                ?.let { nodes[it.getLong("ref")] }
            val at = role("to", "force") ?: role("device") ?: continue
            val from = role("from")
            val bearing = from?.takeIf { it != at }?.let { Direction.bearingDeg(it.first, it.second, at.first, at.second) }
            out += Radar(RadarKind.RED_LIGHT, at.first, at.second, e.optJSONObject("tags")?.optString("name").orEmpty(), bearing = bearing)
        }
        return out + loose
    }

    /** «Radar fijo A-2 km 202.3 (sentido ZARAGOZA)» → «A-2». */
    private fun roadFromName(name: String): String =
        Regex("""\b([A-Z]{1,3}-\d{1,4}[A-Z]?)\b""").find(name)?.groupValues?.get(1) ?: ""

    private val locRe = Regex("""<_0:predefinedLocation id="GUID_(CABINACINEMOMETRO|CVM)_[^"]*">(.*?)</_0:predefinedLocationName>(.*?)(?=<_0:predefinedLocation id="GUID_|</_0:predefinedLocationSet>)""",
        RegexOption.DOT_MATCHES_ALL)
    private val coordRe = Regex("""<_0:latitude>(-?[\d.]+)</_0:latitude>\s*<_0:longitude>(-?[\d.]+)</_0:longitude>""")
    private val roadRe = Regex("""<_0:roadNumber>([^<]+)</_0:roadNumber>""")
    private val linkRe = Regex("""<_0:value>([^<]+)</_0:value>\s*</_0:descriptor>\s*<_0:tpegDescriptorType>linkName""")

    /** La lista oficial de la DGT (DATEX II): cabinas (`Point`) y tramos (`Linear`; el `to` va antes que el `from`). */
    fun parseDatex(xml: String): List<Radar> = locRe.findAll(xml).mapNotNull { m ->
        val body = m.groupValues[3]
        val coords = coordRe.findAll(body).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        val road = roadRe.find(body)?.groupValues?.get(1) ?: linkRe.find(body)?.groupValues?.get(1) ?: ""
        if (m.groupValues[1] == "CVM") {
            if (coords.size < 2) null else Radar(RadarKind.SECTION, coords[1].first, coords[1].second, road, coords[0].first, coords[0].second)
        } else coords.firstOrNull()?.let { Radar(RadarKind.FIXED, it.first, it.second, road) }
    }.toList()

    /** Una línea por radar: `clase;lat;lon;latFin;lonFin;carretera;límite;rumbo;desde;hasta`. */
    fun toCsv(list: List<Radar>): String = list.joinToString("\n") { r ->
        fun d(v: Double?) = v?.let { "%.6f".format(Locale.ROOT, it) } ?: ""
        listOf(r.kind.name, d(r.lat), d(r.lon), d(r.endLat), d(r.endLon), r.road.replace(";", ","),
            r.maxspeed?.toString() ?: "", r.bearing?.let { "%.0f".format(Locale.ROOT, it) } ?: "",
            r.validFrom ?: "", r.validTo ?: "").joinToString(";")
    }

    fun fromCsv(text: String): List<Radar> = text.lineSequence().mapNotNull { line ->
        val f = line.split(';')
        if (f.size < 6) return@mapNotNull null
        // Las listas de la 0.10.0 usaban «F»/«T».
        val kind = when (f[0]) { "F" -> RadarKind.FIXED; "T" -> RadarKind.SECTION; else -> runCatching { RadarKind.valueOf(f[0]) }.getOrNull() }
            ?: return@mapNotNull null
        val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
        Radar(kind, lat, lon, f[5], f[3].toDoubleOrNull(), f[4].toDoubleOrNull(),
            f.getOrNull(6)?.toIntOrNull(), f.getOrNull(7)?.toDoubleOrNull(),
            f.getOrNull(8)?.ifBlank { null }, f.getOrNull(9)?.ifBlank { null })
    }.toList()
}

/** Lo que se avisa de un radar. */
data class RadarAlert(val kind: RadarKind, val distanceM: Double, val road: String, val maxspeed: Int? = null, val inside: Boolean = false) {
    /** «Radar a 500 metros, límite 90», «Entras en un tramo de radar». */
    fun spoken(): String = when {
        inside -> "Entras en un " + kind.label.lowercase() + (maxspeed?.let { ", límite $it" } ?: "")
        else -> kind.label + " a " + RouteGuide.spokenDistance(distanceM) + (maxspeed?.let { ", límite $it" } ?: "")
    }

    /** Para la píldora: «Radar a 350 m · 90 km/h · M-40», «En tramo de radar · 120 km/h · A-2». */
    fun line(): String = listOfNotNull(
        if (inside) "En " + kind.label.lowercase() else kind.label + " a " + Geo.formatDistance(distanceM),
        maxspeed?.let { "$it km/h" },
        road.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * Los radares de una ruta en coche: se sitúa cada uno sobre la ruta (si queda a más de [onRouteM]
 * no es de esta carretera; si trae rumbo y no es el de la ruta ahí, vigila el otro sentido) y,
 * según avanzas, se dice el que viene: una vez al entrar en [warnM] y, en los tramos, otra al
 * entrar; dentro del tramo la píldora lo recuerda. Sólo las clases de [kinds] y los válidos [today].
 */
class RadarGuide(
    route: FollowRoute, radars: List<Radar>, private val warnM: Double = 500.0,
    kinds: Set<RadarKind> = setOf(RadarKind.FIXED, RadarKind.SECTION, RadarKind.MOBILE, RadarKind.RED_LIGHT),
    today: String? = null, onRouteM: Double = 40.0,
) {
    private data class OnRoute(val radar: Radar, val startM: Double, val endM: Double)

    private val ahead: List<OnRoute>
    private val warned = HashSet<Int>()
    private val entered = HashSet<Int>()

    init {
        val pts = route.points
        val minLat = pts.minOf { it.lat } - 0.01; val maxLat = pts.maxOf { it.lat } + 0.01
        val minLon = pts.minOf { it.lon } - 0.01; val maxLon = pts.maxOf { it.lon } + 0.01
        ahead = radars.asSequence()
            .filter { it.kind in kinds && (today == null || it.validOn(today)) }
            .filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
            .mapNotNull { r ->
                val a = route.locate(r.lat, r.lon) ?: return@mapNotNull null
                if (a.offM > onRouteM) return@mapNotNull null
                // El sentido: el rumbo del tramo de la ruta donde cae el radar, frente al del radar.
                r.bearing?.let { b ->
                    val p = pts[a.segment]; val q = pts[minOf(a.segment + 1, pts.size - 1)]
                    if (p != q && kotlin.math.abs(Direction.relativeDeg(b, Direction.bearingDeg(p.lat, p.lon, q.lat, q.lon))) > 90) return@mapNotNull null
                }
                if (!r.isStretch || r.endLat == null || r.endLon == null) return@mapNotNull OnRoute(r, a.alongM, a.alongM)
                val b = route.locate(r.endLat, r.endLon) ?: return@mapNotNull null
                // Un tramo cuenta si la ruta lo recorre entero y en su sentido (el final, después del
                // principio); los de radar móvil valen en los dos sentidos.
                when {
                    b.offM > onRouteM -> null
                    b.alongM > a.alongM -> OnRoute(r, a.alongM, b.alongM)
                    r.kind == RadarKind.MOBILE_STRETCH && b.alongM < a.alongM -> OnRoute(r, b.alongM, a.alongM)
                    else -> null
                }
            }.sortedBy { it.startM }.toList()
    }

    /** Cuántos radares hay en la ruta. */
    val count: Int get() = ahead.size

    private companion object { const val RED_LIGHT_WARN_M = 200.0 }

    /** Con lo que llevas recorrido: el aviso que toca decir ahora (o null) y lo que se enseña. */
    fun update(alongM: Double): Pair<RadarAlert?, RadarAlert?> {
        var say: RadarAlert? = null
        var show: RadarAlert? = null
        ahead.forEachIndexed { i, o ->
            val r = o.radar
            // Las cámaras de semáforo, en ciudad: a 500 m avisaría demasiado pronto.
            val warnM = if (r.kind == RadarKind.RED_LIGHT) minOf(warnM, RED_LIGHT_WARN_M) else warnM
            val inside = r.isStretch && o.endM > o.startM && alongM >= o.startM && alongM <= o.endM
            if (inside) {
                val a = RadarAlert(r.kind, 0.0, r.road, r.maxspeed, inside = true)
                if (entered.add(i) && say == null) say = a
                if (show == null) show = a
                return@forEachIndexed
            }
            val d = o.startM - alongM
            if (d in 0.0..warnM) {
                val a = RadarAlert(r.kind, d, r.road, r.maxspeed)
                if (warned.add(i) && say == null) say = a
                if (show == null) show = a
            }
        }
        return say to show
    }
}

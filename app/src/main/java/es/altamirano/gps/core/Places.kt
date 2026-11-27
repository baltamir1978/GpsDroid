package es.altamirano.gps.core

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Un sitio encontrado buscando por nombre o dirección: lo que se enseña y dónde está. */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double) {
    /** Como punto (sin guardar) para la ruta o la flecha. Id negativo: no choca con los guardados. */
    fun toWaypoint() = Waypoint(-1L - (name.hashCode().toLong() and 0xffffffL), name, lat, lon)
}

/**
 * Buscar sitios en **Photon** (photon.komoot.io): buscador libre sobre OpenStreetMap, sin clave y
 * pensado para buscar mientras se escribe. Encuentra direcciones, calles, pueblos, montes y
 * negocios. Se le pasa la posición para que lo cercano salga antes. Nada de Google.
 *
 * Con un número en lo buscado (una dirección), además **CartoCiudad** (IGN, sin clave): OpenStreetMap
 * no tiene los portales de muchas calles (de Alonso Cano, en Madrid, sólo la calle; Bruno buscó un
 * número y le salía la calle sin él), y CartoCiudad tiene los de toda España, sacados del Catastro y
 * de los ayuntamientos. No ordena por cercanía: se ordena aquí.
 */
object PlaceSearch {
    const val BASE = "https://photon.komoot.io/api/"
    const val LIMIT = 12

    fun url(query: String, lat: Double? = null, lon: Double? = null): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val near = if (lat != null && lon != null) "&lat=%.5f&lon=%.5f".format(Locale.ROOT, lat, lon) else ""
        return "$BASE?q=$q&limit=$LIMIT$near"
    }

    const val CARTOCIUDAD = "https://www.cartociudad.es/geocoder/api/geocoder/candidates"

    /** Si lo buscado lleva un número suelto («alonso cano 56», «mayor 3b»): es una dirección. */
    fun looksLikeAddress(query: String): Boolean = Regex("""(^|\s|,)\d+[a-zA-Z]?($|\s|,)""").containsMatchIn(query.trim())

    fun cartoCiudadUrl(query: String): String = "$CARTOCIUDAD?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=20"

    /**
     * Lee la respuesta de CartoCiudad (una lista JSON). Sólo los portales (`type` = «portal»): lo
     * demás (calles, municipios) ya lo da Photon. «CALLE ALONSO CANO 58, Madrid» pasa a «Calle Alonso
     * Cano 58» con «Madrid, 28003, Madrid» de detalle. Con posición, los más cerca primero.
     */
    fun parseCartoCiudad(json: String, lat: Double? = null, lon: Double? = null): List<Place> {
        val list = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("type") != "portal") continue
            val la = o.optDouble("lat", Double.NaN); val lo = o.optDouble("lng", Double.NaN)
            if (la.isNaN() || lo.isNaN()) continue
            val address = o.optString("address").substringBefore(",").trim().takeIf { it.isNotEmpty() } ?: continue
            fun s(k: String) = o.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val town = s("poblacion") ?: s("muni")
            val detail = listOfNotNull(town, s("postalCode"), s("province")?.takeIf { it != town }).joinToString(", ")
            out += Place(titleCase(address), detail, la, lo)
        }
        return if (lat != null && lon != null) out.sortedBy { Geo.distanceM(lat, lon, it.lat, it.lon) } else out
    }

    /** Los portales de CartoCiudad primero (son lo que se buscaba), y de Photon lo que no repita uno de ellos. */
    fun merge(portals: List<Place>, photon: List<Place>): List<Place> =
        portals + photon.filter { p -> portals.none { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) < 30 } }

    /** «CALLE DE LA ALONSO CANO» → «Calle de la Alonso Cano»: CartoCiudad lo da todo en mayúsculas. */
    internal fun titleCase(text: String): String {
        val small = setOf("de", "del", "la", "las", "los", "el", "y", "e", "a", "en")
        return text.lowercase(Locale.ROOT).split(' ').mapIndexed { i, w ->
            if (i > 0 && w in small) w else w.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.joinToString(" ")
    }

    /** Lee la respuesta (GeoJSON) de Photon. Lo que no trae nombre ni calle se queda fuera; sin repetidos. */
    fun parsePhoton(json: String): List<Place> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (c.length() < 2) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            fun s(k: String) = p.optString(k, "").trim().takeIf { it.isNotEmpty() }
            val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ").takeIf { it.isNotEmpty() }
            val town = s("city") ?: s("town") ?: s("village") ?: s("locality") ?: s("county")
            val name = s("name") ?: street ?: town ?: continue
            val detail = listOfNotNull(street?.takeIf { it != name }, town?.takeIf { it != name }, s("state")?.takeIf { it != town && it != name })
                .distinct().joinToString(", ")
            out += Place(name, detail, c.getDouble(1), c.getDouble(0))
        }
        // Photon da a veces el mismo sitio dos veces (un nodo y su edificio): mismo nombre a menos de 50 m.
        return out.fold(ArrayList()) { acc, p ->
            if (acc.none { it.name == p.name && Geo.distanceM(it.lat, it.lon, p.lat, p.lon) < 50 }) acc += p
            acc
        }
    }
}

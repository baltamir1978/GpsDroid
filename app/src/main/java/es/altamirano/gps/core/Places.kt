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
 */
object PlaceSearch {
    const val BASE = "https://photon.komoot.io/api/"
    const val LIMIT = 12

    fun url(query: String, lat: Double? = null, lon: Double? = null): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val near = if (lat != null && lon != null) "&lat=%.5f&lon=%.5f".format(Locale.ROOT, lat, lon) else ""
        return "$BASE?q=$q&limit=$LIMIT$near"
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

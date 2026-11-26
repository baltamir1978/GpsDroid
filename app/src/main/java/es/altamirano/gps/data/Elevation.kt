package es.altamirano.gps.data

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * La altitud de un sitio del mapa, para los puntos guardados con una pulsación larga (el GPS sólo
 * sabe la de donde estás). Viene del modelo digital del terreno Copernicus GLO-90 a través de la API
 * de elevación de Open-Meteo (la misma casa que usa Tiempo ES): gratis, sin clave y sobre el nivel del
 * mar, como los mapas. Tiene unos 90 m de resolución: en un cortado puede errar algunas decenas de
 * metros, en una ladera normal unos pocos.
 */
object Elevation {
    private const val MAX_PER_REQUEST = 100   // lo que admite la API de una vez

    /** Altitudes en el mismo orden que [coords] (lat, lon); null si no se pudo (sin red, error). */
    fun fetch(coords: List<Pair<Double, Double>>): List<Double?>? {
        if (coords.isEmpty()) return emptyList()
        val out = ArrayList<Double?>()
        for (chunk in coords.chunked(MAX_PER_REQUEST)) {
            val lats = chunk.joinToString(",") { "%.5f".format(Locale.ROOT, it.first) }
            val lons = chunk.joinToString(",") { "%.5f".format(Locale.ROOT, it.second) }
            val text = runCatching {
                val c = URL("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else null } finally { c.disconnect() }
            }.onFailure { Log.w("GpsDroid", "Altitud: ${it.message}") }.getOrNull() ?: return null
            val values = parse(text) ?: return null
            if (values.size != chunk.size) return null
            out += values
        }
        return out
    }

    /** `{"elevation":[812.0,1450.0]}` → [812.0, 1450.0]. Sin librería JSON para tan poco. */
    internal fun parse(json: String): List<Double?>? {
        val body = Regex(""""elevation"\s*:\s*\[([^\]]*)]""").find(json)?.groupValues?.get(1) ?: return null
        if (body.isBlank()) return emptyList()
        // En el mar la API da 0; fuera del modelo, null o NaN.
        return body.split(',').map { it.trim().toDoubleOrNull()?.takeIf { v -> !v.isNaN() } }
    }
}

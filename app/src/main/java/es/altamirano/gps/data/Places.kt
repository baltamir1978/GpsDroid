package es.altamirano.gps.data

import android.content.Context
import es.altamirano.gps.BuildConfig
import es.altamirano.gps.core.Place
import es.altamirano.gps.core.PlaceSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Buscar un sitio por nombre o dirección (Photon, ver [PlaceSearch]). Hace falta red. */
object Places {
    sealed interface Result {
        data class Ok(val places: List<Place>) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun search(context: Context, query: String): Result {
        if (query.isBlank()) return Result.Ok(emptyList())
        if (!Tiles.canUseNetwork(context)) return Result.Failed(
            if (!Prefs(context).online) "Para buscar sitios hace falta la red (está apagada en el mapa)" else "Sin conexión: no se puede buscar")
        // Cerca de donde estás (o de donde se miró el mapa por última vez) sale antes.
        val here = Gps.location.value
        return withContext(Dispatchers.IO) {
            runCatching {
                coroutineScope {
                    // Una dirección con número: los portales de CartoCiudad a la vez que Photon. Si CartoCiudad
                    // falla, se sigue con lo de Photon.
                    val portals = if (PlaceSearch.looksLikeAddress(query)) async {
                        runCatching { get(PlaceSearch.cartoCiudadUrl(query)) }.getOrNull()
                            ?.let { PlaceSearch.parseCartoCiudad(it, here?.latitude, here?.longitude) }.orEmpty()
                    } else null
                    val text = get(PlaceSearch.url(query, here?.latitude, here?.longitude))
                    val found = portals?.await().orEmpty()
                    when {
                        text != null -> Result.Ok(PlaceSearch.merge(found, PlaceSearch.parsePhoton(text)))
                        found.isNotEmpty() -> Result.Ok(found)
                        else -> Result.Failed("El buscador no responde; prueba en un rato")
                    }
                }
            }.getOrElse { Result.Failed("No se pudo buscar: ${it.message ?: "error de red"}") }
        }
    }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "IGN-GPS/" + BuildConfig.VERSION_NAME + " (Android)")
        return try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else null } finally { c.disconnect() }
    }
}

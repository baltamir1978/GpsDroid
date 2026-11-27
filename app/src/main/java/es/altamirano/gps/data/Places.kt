package es.altamirano.gps.data

import android.content.Context
import es.altamirano.gps.BuildConfig
import es.altamirano.gps.core.Place
import es.altamirano.gps.core.PlaceSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        // Sin posición del GPS, la última conocida o donde se miró el mapa.
        val here = Gps.location.value?.let { it.latitude to it.longitude }
            ?: Prefs(context).let { p -> p.last ?: p.view?.let { it.first to it.second } }
        return withContext(Dispatchers.IO) {
            runCatching {
                coroutineScope {
                    // Una dirección con número: los portales de CartoCiudad a la vez que Photon. Si CartoCiudad
                    // falla, se sigue con lo de Photon.
                    val portals = if (PlaceSearch.looksLikeAddress(query)) async {
                        val number = PlaceSearch.portalNumber(query)
                        val local = PlaceSearch.withTown(query, here?.let { town(it.first, it.second) })
                        listOfNotNull(local, query).map { q ->
                            async {
                                runCatching { get(PlaceSearch.cartoCiudadUrl(q)) }.getOrNull()
                                    ?.let { PlaceSearch.parseCartoCiudad(it, number = number) }.orEmpty()
                            }
                        }.awaitAll().let { PlaceSearch.joinPortals(it, here?.first, here?.second) }
                    } else null
                    val text = get(PlaceSearch.url(query, here?.first, here?.second))
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

    /** El municipio de la última búsqueda, para no preguntarlo en cada letra: vale a menos de 1 km. */
    @Volatile private var townCache: Triple<Double, Double, String?>? = null

    /** El municipio donde se está (CartoCiudad), o null si no se sabe. */
    private fun town(lat: Double, lon: Double): String? {
        townCache?.let { (la, lo, t) -> if (es.altamirano.gps.core.Geo.distanceM(la, lo, lat, lon) < 1000) return t }
        val t = runCatching { get(PlaceSearch.cartoCiudadReverseUrl(lat, lon)) }.getOrNull()?.let { PlaceSearch.parseReverseTown(it) }
        if (t != null) townCache = Triple(lat, lon, t)
        return t
    }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "IGN-GPS/" + BuildConfig.VERSION_NAME + " (Android)")
        return try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else null } finally { c.disconnect() }
    }
}

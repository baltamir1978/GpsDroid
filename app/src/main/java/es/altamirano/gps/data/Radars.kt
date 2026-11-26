package es.altamirano.gps.data

import android.content.Context
import android.util.Log
import es.altamirano.gps.core.Radar
import es.altamirano.gps.core.RadarList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Los radares de la DGT para avisar en coche. La app lleva una copia (`assets/radares.csv`, sacada
 * del fichero oficial) para que funcione sin red desde el primer día; con red, una vez por semana,
 * se baja el fichero de la DGT (DATEX II, ~2 MB) y se guarda la lista en `files/radares.csv`.
 * Avisar de radares con su ubicación es legal en España (los detectores no).
 */
object Radars {
    private const val URL_DGT = "https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml"
    private const val REFRESH_MS = 7L * 24 * 3600 * 1000
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cached: List<Radar>? = null

    private fun file(context: Context) = File(context.filesDir, "radares.csv")

    /** Avisar de radares yendo en coche (se puede quitar en los ajustes de Offline). */
    fun enabled(context: Context) = context.getSharedPreferences("gps", Context.MODE_PRIVATE).getBoolean("radar_alerts", true)
    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences("gps", Context.MODE_PRIVATE).edit().putBoolean("radar_alerts", on).apply()

    /** La lista: la última bajada o, si no hay, la que trae la app. */
    fun list(context: Context): List<Radar> = cached ?: run {
        val f = file(context)
        val text = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
        (text ?: context.assets.open("radares.csv").use { it.readBytes().decodeToString() })
            .let { RadarList.fromCsv(it) }.also { cached = it }
    }

    /** Fecha de la lista que se usa (la bajada) o null si es la que trae la app. */
    fun updatedAt(context: Context): Long? = file(context).takeIf { it.exists() }?.lastModified()

    /** Si hace más de una semana (o nunca), bajar la de la DGT. Sólo con red y «Usar la red». */
    fun refreshIfOld(context: Context) {
        val f = file(context)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return
        if (!Tiles.canUseNetwork(context)) return
        io.launch {
            runCatching {
                val c = URL(URL_DGT).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 60_000
                val xml = try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else null } finally { c.disconnect() }
                val list = xml?.let { RadarList.parseDatex(it) }.orEmpty()
                // Una respuesta rara (sin radares o muy pocos) no sustituye a la buena.
                if (list.size >= 100) {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(RadarList.toCsv(list)); tmp.renameTo(f)
                    cached = list
                }
            }.onFailure { Log.w("GpsDroid", "Radares: ${it.message}") }
        }
    }
}

package es.altamirano.gps.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import es.altamirano.gps.core.Radar
import es.altamirano.gps.core.RadarKind
import es.altamirano.gps.core.RadarList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Cómo avisar de un radar. */
enum class RadarAlertMode(val label: String) {
    /** Un pitido suave («bip-bip»), por el coche si va con Android Auto. */
    SOUND("Con pitido"),
    /** Sólo la franja roja en el mapa y en el coche. */
    SILENT("Sólo en pantalla"),
}

/** Lo que se puede elegir de los avisos de radares (en el móvil y en el coche). */
data class RadarSettings(
    val enabled: Boolean = true,
    val distanceM: Int = 500,
    val mode: RadarAlertMode = RadarAlertMode.SOUND,
    val kinds: Set<RadarKind> = setOf(RadarKind.FIXED, RadarKind.SECTION, RadarKind.MOBILE, RadarKind.RED_LIGHT),
) {
    companion object {
        val DISTANCES = listOf(300, 500, 800, 1000)
    }
}

/**
 * Los radares para avisar en coche. La app lleva una copia (`assets/radares.csv`, sacada del
 * GeoJSON de Radares Anunciados) para ir sin red desde el primer día. Con red y «Usar la red», una
 * vez al día se baja ese GeoJSON (DGT, Cataluña, Euskadi, ayuntamientos y OpenStreetMap; ~2,6 MB) y,
 * si falla, la lista oficial de la DGT; se guarda en `files/radares.csv`. Avisar de radares con su
 * ubicación es legal en España (los detectores no).
 */
object Radars {
    private const val URL_FEED = "https://geiserx.github.io/radares-anunciados-ha/feed.geojson"
    private const val URL_DGT = "https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml"
    private const val REFRESH_MS = 24L * 3600 * 1000
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cached: List<Radar>? = null

    /**
     * La lista bajada. La que guardaba la 0.10.0 (sólo la DGT, líneas «F;»/«T;») se tira al actualizar:
     * la de la app ya trae más radares, y con red se baja la buena en el acto.
     */
    private fun file(context: Context) = File(context.filesDir, "radares.csv").also { f ->
        if (f.exists() && runCatching { f.bufferedReader().use { it.read() } }.getOrNull().let { it == 'F'.code || it == 'T'.code }) f.delete()
    }
    private fun prefs(context: Context) = context.getSharedPreferences("gps", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow<RadarSettings?>(null)
    /** Los ajustes, para que el móvil y el coche vean el mismo valor al cambiarlo en cualquiera. */
    fun settingsFlow(context: Context): StateFlow<RadarSettings?> { if (_settings.value == null) _settings.value = settings(context); return _settings.asStateFlow() }

    fun settings(context: Context): RadarSettings {
        val p = prefs(context)
        var kinds = p.getStringSet("radar_kinds", null)?.mapNotNull { runCatching { RadarKind.valueOf(it) }.getOrNull() }?.toSet()
        // Las cámaras de semáforo llegaron después (0.17.0): a quien ya había elegido clases, se le añaden una vez.
        if (kinds != null && !p.getBoolean("radar_kinds_red_light", false)) {
            kinds = kinds + RadarKind.RED_LIGHT
            p.edit { putStringSet("radar_kinds", kinds.map { it.name }.toSet()); putBoolean("radar_kinds_red_light", true) }
        }
        return RadarSettings(
            enabled = p.getBoolean("radar_alerts", true),
            distanceM = p.getInt("radar_distance", 500),
            mode = runCatching { RadarAlertMode.valueOf(p.getString("radar_mode", null) ?: "") }.getOrDefault(RadarAlertMode.SOUND),
            kinds = kinds ?: RadarSettings().kinds,
        )
    }

    fun save(context: Context, s: RadarSettings) {
        prefs(context).edit {
            putBoolean("radar_alerts", s.enabled)
            putInt("radar_distance", s.distanceM)
            putString("radar_mode", s.mode.name)
            putStringSet("radar_kinds", s.kinds.map { it.name }.toSet())
        }
        _settings.value = s
    }

    /** La lista: la última bajada o, si no hay, la que trae la app; más las cámaras de semáforo. */
    fun list(context: Context): List<Radar> = cached ?: run {
        fun load(f: File, asset: String) = ((if (f.exists()) runCatching { f.readText() }.getOrNull() else null)
            ?: context.assets.open(asset).use { it.readBytes().decodeToString() }).let { RadarList.fromCsv(it) }
        (load(file(context), "radares.csv") + load(redLightFile(context), "semaforos.csv")).also { cached = it }
    }

    /** Las cámaras de semáforo van aparte: ni la DGT ni Radares Anunciados las tienen; salen de OpenStreetMap. */
    private fun redLightFile(context: Context) = File(context.filesDir, "semaforos.csv")
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val RED_LIGHT_QUERY = "[out:json][timeout:180];area[\"ISO3166-1\"=\"ES\"][admin_level=2]->.es;" +
        "(relation[\"type\"=\"enforcement\"][\"enforcement\"=\"traffic_signals\"](area.es);" +
        "node[\"enforcement\"=\"traffic_signals\"](area.es););out body;>;out skel qt;"
    private const val RED_LIGHT_REFRESH_MS = 7L * 24 * 3600 * 1000

    /** Fecha de la lista que se usa (la bajada) o null si es la que trae la app. */
    fun updatedAt(context: Context): Long? = file(context).takeIf { it.exists() }?.lastModified()

    /** Si hace más de un día (o nunca), bajar la lista. Sólo con red y «Usar la red». */
    fun refreshIfOld(context: Context) {
        refreshRedLightsIfOld(context)
        val f = file(context)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return
        if (!Tiles.canUseNetwork(context)) return
        io.launch {
            val list = runCatching { get(URL_FEED)?.let { RadarList.parseFeed(it) } }.onFailure { Log.w("GpsDroid", "Radares (feed): ${it.message}") }.getOrNull()
                ?.takeIf { it.size >= 500 }
                ?: runCatching { get(URL_DGT)?.let { RadarList.parseDatex(it) } }.onFailure { Log.w("GpsDroid", "Radares (DGT): ${it.message}") }.getOrNull()
                    ?.takeIf { it.size >= 100 }
                ?: return@launch
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(RadarList.toCsv(list)); tmp.renameTo(f)
            cached = null   // se vuelve a leer con las cámaras de semáforo
        }
    }

    /**
     * Las cámaras de semáforo, una vez por semana, de Overpass (OpenStreetMap). Sus servidores públicos
     * se saturan a menudo: si falla, se sigue con las que hay y se prueba la semana siguiente... o
     * mañana, si ni siquiera había lista bajada.
     */
    private fun refreshRedLightsIfOld(context: Context) {
        val f = redLightFile(context)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < RED_LIGHT_REFRESH_MS) return
        if (!Tiles.canUseNetwork(context)) return
        io.launch {
            runCatching {
                val c = URL(OVERPASS).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 200_000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("User-Agent", "IGN-GPS (Android)")
                c.outputStream.use { it.write(("data=" + java.net.URLEncoder.encode(RED_LIGHT_QUERY, "UTF-8")).toByteArray()) }
                val text = try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else null } finally { c.disconnect() }
                val list = text?.let { RadarList.parseOverpassRedLights(it) }.orEmpty()
                if (list.size >= 50) {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(RadarList.toCsv(list)); tmp.renameTo(f)
                    cached = null
                }
            }.onFailure { Log.w("GpsDroid", "Semáforos: ${it.message}") }
        }
    }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        return try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else null } finally { c.disconnect() }
    }
}

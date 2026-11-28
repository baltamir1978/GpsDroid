package es.altamirano.gps.data

import android.content.Context
import es.altamirano.gps.core.BBox
import es.altamirano.gps.core.MapLayer
import es.altamirano.gps.core.MapStyle
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.map.MapStyles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.cos

/** Estado de una descarga (zona o camino). */
data class DownloadState(
    val running: Boolean = false,
    val done: Int = 0,          // recursos ya bajados o ya guardados (teselas, y el estilo, letras e iconos)
    val total: Int = 0,
    val failed: Int = 0,
    /** Lo bajado, en bytes (lo que se enseña, con el porcentaje). */
    val bytes: Long = 0,
    /** Si lleva un rato sin avanzar y MapLibre está reintentando, por qué («sin conexión…»); null si va bien. */
    val retrying: String? = null,
    val layer: MapLayer? = null,
    val message: String? = null, // al acabar: resumen o motivo de la parada
    /** Los mapas del camino que se sigue (no una zona elegida en Offline). */
    val route: Boolean = false,
)

/** Lo guardado de una capa: regiones descargadas (zonas y caminos), lo que ocupan y la más vieja. */
data class SavedLayer(val regions: Int, val bytes: Long, val oldest: Long?)

/**
 * Mapas sin cobertura, con MapLibre (desde la 0.27.0; antes osmdroid). Dos cosas:
 * - **Lo que se va viendo** queda en la caché de MapLibre (sin tope práctico: Bruno no quiere límite).
 * - **Lo descargado** son *regiones* de MapLibre: una por capa y zona (Offline) o por capa y camino (al
 *   empezar a seguir algo con red). MapLibre baja las teselas del estilo de la capa —el vectorial con sus
 *   letras e iconos, o las imágenes del IGN— y no las borra nunca de la caché mientras exista la región.
 *   Repetir una descarga con la misma zona reanuda la región que ya había.
 * Todo vive en `files/mbgl-offline.db` (la base de MapLibre).
 */
object Tiles {
    private lateinit var app: Context
    private var mapLibreReady = false

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Antes de crear un mapa o tocar las descargas: MapLibre, sus topes y los estilos propios. */
    fun ensureMapLibre(context: Context) {
        if (mapLibreReady) return
        mapLibreReady = true
        val a = context.applicationContext
        MapLibre.getInstance(a)
        MapStyles.init(a)
        setOnline(Prefs(a).online)
        runCatching {
            val om = OfflineManager.getInstance(a)
            // De serie MapLibre corta las regiones en 6.000 teselas y la caché de lo visto en 50 MB.
            om.setOfflineMapboxTileCountLimit(Long.MAX_VALUE)
            om.setMaximumAmbientCacheSize(AMBIENT_BYTES, quiet)
        }
    }

    /** «Usar la red»: apagado, MapLibre sólo enseña lo guardado. */
    fun setOnline(on: Boolean) { MapLibre.setConnected(if (on) null else false) }

    private val quiet = object : OfflineManager.FileSourceCallback {
        override fun onSuccess() {}
        override fun onError(message: String) {}
    }

    /** Sólo para mirar el espacio libre del disco donde se guarda. */
    val cacheDir: File get() = app.filesDir

    private val manager get() = ensureMapLibre(app).let { OfflineManager.getInstance(app) }

    // --- Regiones -------------------------------------------------------------------------------

    private data class Meta(val layer: MapLayer, val kind: String, val key: String, val created: Long)

    private fun meta(r: OfflineRegion): Meta? = runCatching {
        val j = JSONObject(String(r.metadata))
        Meta(MapLayer.entries.first { it.key == j.getString("layer") }, j.getString("kind"), j.getString("key"), j.getLong("created"))
    }.getOrNull()

    private fun metaBytes(layer: MapLayer, kind: String, key: String) = JSONObject()
        .put("layer", layer.key).put("kind", kind).put("key", key).put("created", System.currentTimeMillis()).toString().toByteArray()

    private suspend fun regions(): List<OfflineRegion> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) = c.resume(offlineRegions?.toList().orEmpty())
                override fun onError(error: String) = c.resume(emptyList())
            })
        }
    }

    private suspend fun status(r: OfflineRegion): OfflineRegionStatus? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) = c.resume(status)
                override fun onError(error: String?) = c.resume(null)
            })
        }
    }

    private suspend fun create(def: org.maplibre.android.offline.OfflineRegionDefinition, metadata: ByteArray): OfflineRegion? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { c ->
                manager.createOfflineRegion(def, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) = c.resume(offlineRegion)
                    override fun onError(error: String) = c.resume(null)
                })
            }
        }

    private suspend fun delete(r: OfflineRegion) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = c.resume(Unit)
                override fun onError(error: String) = c.resume(Unit)
            })
        }
    }

    /** Por capa: cuántas regiones, cuánto ocupan y de cuándo es la más vieja (para Offline y Actualizaciones). */
    suspend fun saved(): Map<MapLayer, SavedLayer> {
        val out = HashMap<MapLayer, SavedLayer>()
        for (r in regions()) {
            // Las que dejó a medias la 0.27.0 con el estilo en `file://` no se pueden bajar: fuera.
            if (r.definition.styleURL?.startsWith("file:") == true) { if (job?.isActive != true) delete(r); continue }
            val m = meta(r) ?: continue
            val bytes = status(r)?.completedResourceSize ?: 0L
            val prev = out[m.layer]
            out[m.layer] = SavedLayer((prev?.regions ?: 0) + 1, (prev?.bytes ?: 0L) + bytes,
                listOfNotNull(prev?.oldest, m.created).minOrNull())
        }
        return out
    }

    /** Lo que ocupa todo (descargas y lo visto). */
    fun cacheBytes(): Long = File(app.filesDir, "mbgl-offline.db").length()

    /** Borra las descargas de una capa (lo que no use otra región, MapLibre lo puede soltar). */
    suspend fun purge(layer: MapLayer) { regions().filter { meta(it)?.layer == layer }.forEach { delete(it) } }

    /** Vacía lo visto que no está en ninguna descarga. */
    suspend fun clearViewed() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            manager.clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = c.resume(Unit)
                override fun onError(message: String) = c.resume(Unit)
            })
        }
    }

    /**
     * Renovar las descargas de una capa: no se borra nada (sin red se siguen viendo); MapLibre da sus
     * teselas por caducadas y las vuelve a pedir (y sólo baja las que hayan cambiado: el IGN contesta
     * «no modificada»). Con red, se vuelven a bajar ya.
     */
    suspend fun renew(layer: MapLayer) {
        for (r in regions().filter { meta(it)?.layer == layer }) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { c ->
                    r.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
                        override fun onInvalidate() = c.resume(Unit)
                        override fun onError(error: String) = c.resume(Unit)
                    })
                }
            }
        }
    }

    // --- Lo de osmdroid (hasta la 0.26.0) ---------------------------------------------------------

    private val oldDir: File get() = File(app.noBackupFilesDir, "osmdroid")

    /** Lo que ocupan los mapas guardados con la versión anterior (ya no sirven para nada). */
    fun oldCacheBytes(): Long = oldDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun deleteOldCache() { oldDir.deleteRecursively() }

    // --- Descargas --------------------------------------------------------------------------------

    private val _download = MutableStateFlow(DownloadState())
    val download: StateFlow<DownloadState> = _download.asStateFlow()
    private var job: Job? = null
    private var active: OfflineRegion? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Por debajo de esto se para: el móvil sin espacio va mal entero. */
    private const val MIN_FREE_BYTES = 256L shl 20
    /** La caché de lo visto: «sin límite» (Bruno, 27-nov-2026); MapLibre pide un número. */
    private const val AMBIENT_BYTES = 1L shl 40

    private val pixelRatio get() = app.resources.displayMetrics.density

    /** Una zona de Offline: una región por capa, de [zmin] a [zmax] (o el máximo de cada capa). */
    fun start(box: BBox, zmin: Int, zmax: Int, layers: List<MapLayer>) {
        val b = box.normalized()
        val bounds = LatLngBounds.from(b.maxLat, b.maxLon, b.minLat, b.minLon)
        val key = "%.4f,%.4f,%.4f,%.4f,%d,%d".format(java.util.Locale.ROOT, b.minLat, b.minLon, b.maxLat, b.maxLon, zmin, zmax)
        run(layers.map { l ->
            val top = minOf(zmax, l.maxZoom)
            Triple(l, "zona:$key") { OfflineTilePyramidRegionDefinition(MapStyles.downloadUriOf(l), bounds, zmin.toDouble(), top.toDouble(), pixelRatio) as org.maplibre.android.offline.OfflineRegionDefinition }
        }, kind = "zona", route = false, doneMessage = "Zona descargada")
    }

    /**
     * Los mapas de un camino (±[marginM]), de z10 a z17: el callejero vectorial (el del coche) y, si el
     * mapa del móvil es otro, también ese. Si ya hay una descarga en marcha, no se pisa.
     */
    fun startRoute(points: List<TrackPoint>, phoneStyle: MapStyle, marginM: Double = 500.0, maxBytesPerSec: Long? = null) {
        if (job?.isActive == true || points.size < 2) return
        val geometry = corridor(points, marginM)
        val layers = (listOf(MapLayer.VECTOR) + phoneStyle.layers).distinct()
        val key = "${points.size}:${points.first().lat},${points.first().lon}:${points.last().lat},${points.last().lon}"
        run(layers.map { l ->
            Triple(l, "camino:$key") {
                OfflineGeometryRegionDefinition(MapStyles.downloadUriOf(l), geometry, ROUTE_ZMIN.toDouble(), minOf(ROUTE_ZMAX, l.maxZoom).toDouble(), pixelRatio)
                    as org.maplibre.android.offline.OfflineRegionDefinition
            }
        }, kind = "camino", route = true, doneMessage = "Mapas del camino guardados", maxBytesPerSec = maxBytesPerSec)
    }

    /**
     * La franja de ±[marginM] a lo largo del camino, como cuadrados alrededor de puntos cada medio
     * margen (MapLibre baja las teselas que tocan alguno). Basta y es mucho más sencillo que un buffer.
     */
    internal fun corridor(points: List<TrackPoint>, marginM: Double): MultiPolygon {
        val squares = ArrayList<Polygon>()
        var last: TrackPoint? = null
        val step = marginM / 2
        fun square(lat: Double, lon: Double) {
            val dLat = marginM / 111_320.0
            val dLon = marginM / (111_320.0 * cos(Math.toRadians(lat)))
            squares += Polygon.fromLngLats(listOf(listOf(
                Point.fromLngLat(lon - dLon, lat - dLat), Point.fromLngLat(lon + dLon, lat - dLat),
                Point.fromLngLat(lon + dLon, lat + dLat), Point.fromLngLat(lon - dLon, lat + dLat), Point.fromLngLat(lon - dLon, lat - dLat))))
        }
        for (p in points) {
            val l = last
            if (l == null) { square(p.lat, p.lon); last = p; continue }
            val d = es.altamirano.gps.core.Geo.distanceM(l.lat, l.lon, p.lat, p.lon)
            if (d < step) continue
            // Tramos largos (una recta en el campo): puntos intermedios.
            val n = kotlin.math.ceil(d / step).toInt()
            for (i in 1..n) square(l.lat + (p.lat - l.lat) * i / n, l.lon + (p.lon - l.lon) * i / n)
            last = p
        }
        return MultiPolygon.fromPolygons(squares)
    }

    /** Hay red de verdad (validada: no un wifi sin salida a internet) y el usuario deja usarla. */
    fun canUseNetwork(context: Context): Boolean {
        if (!Prefs(context).online) return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Al empezar a seguir un camino: guardar sus mapas si hay red (y no se está bajando ya otra cosa).
     * Una ruta en coche con datos del móvil, despacio ([CAR_METERED_BYTES_PER_SEC]): de golpe eran
     * decenas de megas (la franja de z10 a z17, otra vez en cada recálculo) y a Bruno se le cortaba la
     * radio por internet en el coche. Con wifi, o a pie (para el monte, antes de perder cobertura), a
     * toda velocidad.
     */
    fun saveAlong(context: Context, points: List<TrackPoint>, car: Boolean = false) {
        if (!canUseNetwork(context)) return
        val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        scope.launch { startRoute(points, Prefs(context).style, maxBytesPerSec = if (car && metered) CAR_METERED_BYTES_PER_SEC else null) }
    }

    /**
     * Tope de la descarga del camino en coche con datos: 40 KB/s (320 kbit/s). Una radio por internet
     * gasta 16-40 KB/s y el 4G en carretera da bastante más, así que queda hueco para las dos. Así una
     * franja de 30 MB tarda unos 13 minutos en vez de uno.
     */
    private const val CAR_METERED_BYTES_PER_SEC = 40_000L
    /** Lo que puede adelantarse al tope antes de pausar (MapLibre pide varias teselas a la vez). */
    private const val RATE_BURST_BYTES = 150_000L

    private const val ROUTE_ZMIN = 10
    private const val ROUTE_ZMAX = 17
    /** Sin avanzar nada en este tiempo (sin red, el servidor no responde), se para. */
    private const val STALL_MS = 90_000L

    /**
     * Baja las regiones una detrás de otra. Una que ya exista con la misma clave se reanuda (MapLibre
     * salta lo que ya tiene). Para si falta espacio o si no avanza en [STALL_MS].
     */
    private fun run(work: List<Triple<MapLayer, String, () -> org.maplibre.android.offline.OfflineRegionDefinition>>,
                    kind: String, route: Boolean, doneMessage: String, maxBytesPerSec: Long? = null) {
        if (job?.isActive == true) return
        _download.value = DownloadState(true, route = route)
        job = scope.launch {
            var stopped: String? = null
            var failed = 0
            var doneBefore = 0
            var totalBefore = 0
            var bytesBefore = 0L
            val existing = regions()
            for ((layer, key, def) in work) {
                if (cacheDir.usableSpace < MIN_FREE_BYTES) { stopped = "Parada: queda poco espacio libre"; break }
                val d = def()
                // La misma zona ya empezada se reanuda; si se creó con otro estilo (la 0.27.0 los ponía con
                // `file://` y se quedaban en «0 de 1»), se borra y se hace de nuevo.
                val old = existing.firstOrNull { meta(it)?.let { m -> m.layer == layer && m.key == key } == true }
                if (old != null && old.definition.styleURL != d.styleURL) delete(old)
                val region = old?.takeIf { it.definition.styleURL == d.styleURL } ?: create(d, metaBytes(layer, kind, key))
                if (region == null) { stopped = "No se pudo preparar la descarga"; break }
                active = region
                val finished = CompletableDeferred<Unit>()
                var lastProgress = System.currentTimeMillis()
                var lastDone = -1L
                // Con tope de velocidad: lo bajado de esta región desde que empezó (lo que ya tenía no cuenta).
                val started = System.currentTimeMillis()
                var bytesAtStart = -1L
                var regionBytes = 0L
                var paused = false
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (bytesAtStart < 0) bytesAtStart = status.completedResourceSize
                        regionBytes = status.completedResourceSize - bytesAtStart
                        val moved = status.completedResourceCount != lastDone
                        if (moved) { lastDone = status.completedResourceCount; lastProgress = System.currentTimeMillis() }
                        _download.value = _download.value.copy(done = doneBefore + status.completedResourceCount.toInt(),
                            total = totalBefore + status.requiredResourceCount.toInt(), failed = failed, layer = layer,
                            bytes = bytesBefore + status.completedResourceSize,
                            retrying = if (moved) null else _download.value.retrying)
                        if (status.isComplete) finished.complete(Unit)
                    }
                    // Una tesela que no existe (la ortofoto fuera de cobertura, el vectorial vacío) no es un fallo; lo demás
                    // (cortes de red, el IGN que no contesta) MapLibre lo reintenta solo: sólo se cuenta para decirlo si
                    // se atasca. Antes cada reintento salía como «fallo» aunque luego bajara (Bruno vio «28 fallos»).
                    override fun onError(error: OfflineRegionError) {
                        if (error.reason == OfflineRegionError.REASON_NOT_FOUND) return
                        failed++; lastError = error.message; lastReason = error.reason
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) { stopped = "Demasiadas teselas"; finished.complete(Unit) }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                // Esperar a que acabe, vigilando que avance y el espacio y, si hay tope, la velocidad: por
                // encima de lo permitido se pausa la región y se reanuda cuando la media vuelve a caber
                // (MapLibre no deja limitar el ancho de banda; pausar corta las peticiones nuevas).
                while (!finished.isCompleted) {
                    withTimeoutOrNull(if (maxBytesPerSec != null) 250L else 2000L) { finished.await() }
                    if (finished.isCompleted) break
                    if (maxBytesPerSec != null) {
                        val now = System.currentTimeMillis()
                        val allowed = maxBytesPerSec * (now - started) / 1000 + RATE_BURST_BYTES
                        if (!paused && regionBytes > allowed) { region.setDownloadState(OfflineRegion.STATE_INACTIVE); paused = true }
                        else if (paused && regionBytes <= allowed) { region.setDownloadState(OfflineRegion.STATE_ACTIVE); paused = false }
                        // Pausado a propósito no es atascarse.
                        if (paused) { lastProgress = now; continue }
                    }
                    // Quince segundos sin avanzar con errores: decir que está reintentando y por qué.
                    if (System.currentTimeMillis() - lastProgress > 15_000 && lastReason != null)
                        _download.value = _download.value.copy(retrying = when (lastReason) {
                            OfflineRegionError.REASON_CONNECTION -> "sin conexión, reintentando"
                            OfflineRegionError.REASON_SERVER -> "el IGN no responde, reintentando"
                            else -> "reintentando"
                        })
                    if (System.currentTimeMillis() - lastProgress > STALL_MS) { stopped = "Parada: el IGN no responde (¿sin conexión?)"; break }
                    if (cacheDir.usableSpace < MIN_FREE_BYTES) { stopped = "Parada: queda poco espacio libre"; break }
                }
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.setObserver(null)
                active = null
                val st = status(region)
                doneBefore += st?.completedResourceCount?.toInt() ?: 0
                totalBefore += st?.requiredResourceCount?.toInt() ?: 0
                bytesBefore += st?.completedResourceSize ?: 0L
                if (stopped != null) break
            }
            val why = lastError?.let { " Último error: $it." } ?: ""
            _download.value = DownloadState(false, doneBefore, totalBefore, failed, bytesBefore, null, null,
                stopped?.let { "$it.$why" } ?: doneMessage, route = route)
            lastError = null; lastReason = null
        }
    }

    fun cancel() {
        val j = job ?: return
        active?.let { it.setDownloadState(OfflineRegion.STATE_INACTIVE); it.setObserver(null) }
        active = null
        j.cancel()
        _download.value = _download.value.copy(running = false, message = "Cancelada: lo bajado se queda; repite para seguir")
    }

    @Volatile private var lastError: String? = null
    @Volatile private var lastReason: String? = null

    /** Para las pruebas o el registro: espera a que acabe la descarga en marcha. */
    internal suspend fun awaitIdle() { while (job?.isActive == true) delay(500) }
}

package es.altamirano.gps.data

import android.content.Context
import es.altamirano.gps.BuildConfig
import es.altamirano.gps.core.BBox
import es.altamirano.gps.core.MapLayer
import es.altamirano.gps.core.MapTiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Una capa del IGN para osmdroid. El IGN no sirve z/x/y en la ruta sino como parámetros de WMTS
 * (`tilerow` = y, `tilecol` = x): por eso se sobreescribe la URL entera. El nombre identifica la capa
 * en la caché de osmdroid (una base SQLite con todas).
 */
class IgnTileSource(val layer: MapLayer) : OnlineTileSourceBase(
    "IGN-${layer.key}", 0, layer.maxZoom, MapTiles.TILE_SIZE, if (layer.png) ".png" else ".jpeg", arrayOf("https://www.ign.es/wmts/"), MapLayer.ATTRIBUTION,
) {
    override fun getTileURLString(index: Long) =
        layer.tileUrl(MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
}

/** Estado de una descarga de zona. */
data class DownloadState(
    val running: Boolean = false,
    val done: Int = 0,          // teselas ya tratadas (bajadas, ya guardadas o con error)
    val total: Int = 0,
    val failed: Int = 0,
    val layer: MapLayer? = null,
    val message: String? = null, // al acabar: resumen o motivo de la parada
)

object Tiles {
    private lateinit var app: Context
    val sources = MapLayer.entries.associateWith { IgnTileSource(it) }

    /** Lo del mapa y lo de las descargas va a la misma base de osmdroid. */
    val cacheDir: File get() = File(app.noBackupFilesDir, "osmdroid/tiles")

    /**
     * Antes de crear ningún `MapView`. La caché va en `noBackupFilesDir`: en `cacheDir` Android
     * podría borrar las zonas descargadas, y no tiene sentido que pasen a la copia de seguridad.
     */
    fun init(context: Context) {
        app = context.applicationContext
        Configuration.getInstance().apply {
            userAgentValue = BuildConfig.APPLICATION_ID + "/" + BuildConfig.VERSION_NAME
            osmdroidBasePath = File(app.noBackupFilesDir, "osmdroid")
            osmdroidTileCache = cacheDir
            // osmdroid recorta la caché a 500 MB y se llevaría las zonas descargadas: aquí manda el
            // usuario, que la vacía desde la pantalla de mapas offline.
            tileFileSystemCacheMaxBytes = 64L shl 30
            tileFileSystemCacheTrimBytes = 60L shl 30
            // El IGN apenas cambia sus mapas: una tesela vale un año (si no, al caducar se volverían
            // a pedir y sin cobertura no se verían mejor).
            expirationOverrideDuration = 365L * 24 * 3600 * 1000
            tileDownloadThreads = 4
        }
    }

    private val writer by lazy { SqlTileWriter() }

    fun countFor(layer: MapLayer): Long = runCatching { writer.getRowCount(sources.getValue(layer).name()) }.getOrDefault(0L)

    fun cacheBytes(): Long = cacheDir.listFiles().orEmpty().sumOf { it.length() }

    fun purge(layer: MapLayer) = runCatching { writer.purgeCache(sources.getValue(layer).name()) }

    // --- Descarga de zonas -------------------------------------------------------------------

    private val _download = MutableStateFlow(DownloadState())
    val download: StateFlow<DownloadState> = _download.asStateFlow()
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Por debajo de esto se para: el móvil sin espacio va mal entero. */
    private const val MIN_FREE_BYTES = 256L shl 20

    /**
     * Baja la zona capa a capa y de zoom menor a mayor (si se cancela, lo bajado ya sirve para ver
     * la zona entera aunque con menos detalle), con 3 peticiones a la vez como ZeroGPS: es un
     * servicio público. Se salta lo que ya está guardado y sin caducar, así que repetir la misma
     * descarga reanuda la anterior.
     */
    fun start(box: BBox, zmin: Int, zmax: Int, layers: List<MapLayer>) {
        if (job?.isActive == true) return
        val plan = layers.map { l -> l to es.altamirano.gps.core.OfflineZone.plan(box, zmin, zmax, l) }
        val total = plan.sumOf { it.second.tiles }.toInt()
        lastError = null
        _download.value = DownloadState(true, 0, total)
        job = scope.launch {
            val done = AtomicInteger(0)
            val failed = AtomicInteger(0)
            var stopped: String? = null
            outer@ for ((layer, p) in plan) {
                val src = sources.getValue(layer)
                for (z in p.zmin..p.zmax) {
                    if (cacheDir.usableSpace < MIN_FREE_BYTES) { stopped = "Parada: queda poco espacio libre"; break@outer }
                    val before = failed.get()
                    val tl = MapTiles.tileAt(box.maxLat, box.minLon, z)
                    val br = MapTiles.tileAt(box.minLat, box.maxLon, z)
                    val queue = Channel<Long>(64)
                    coroutineScope {
                        repeat(3) {
                            launch {
                                for (index in queue) {
                                    if (!fetch(src, index)) failed.incrementAndGet()
                                    val d = done.incrementAndGet()
                                    if (d % 8 == 0) _download.value = _download.value.copy(done = d, failed = failed.get(), layer = layer)
                                }
                            }
                        }
                        for (y in tl.y..br.y) for (x in tl.x..br.x) {
                            if (!isActive) break
                            queue.send(MapTileIndex.getTileIndex(z, x, y))
                        }
                        queue.close()
                    }
                    // Un zoom entero sin una sola tesela buena: sin red o el IGN no responde.
                    val tried = p.perZoom.getValue(z)
                    if (tried >= 8 && failed.get() - before >= tried) { stopped = "Parada: el IGN no responde (¿sin conexión?)"; break@outer }
                }
            }
            val f = failed.get()
            val why = lastError?.let { " Último error: $it." } ?: ""
            _download.value = DownloadState(false, done.get(), total, f, null,
                (stopped?.let { "$it.$why" }) ?: if (f == 0) "Zona descargada" else "Descargada con $f teselas que fallaron (repite la descarga para reintentarlas).$why")
        }
    }

    fun cancel() {
        val j = job ?: return
        j.cancel()
        _download.value = _download.value.copy(running = false, message = "Cancelada: lo bajado se queda; repite para seguir")
    }

    /** Un JPEG empieza por FF D8; un PNG, por 89 50 4E 47 ("\u0089PNG"). */
    internal fun looksLikeImage(b: ByteArray, png: Boolean): Boolean =
        if (png) b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        else b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    /** El motivo del último fallo, para enseñarlo (y para el registro). */
    @Volatile private var lastError: String? = null

    /** true si la tesela ya estaba o se bajó bien. */
    private fun fetch(src: IgnTileSource, index: Long): Boolean {
        val exp = writer.getExpirationTimestamp(src, index)
        if (exp != null && exp > System.currentTimeMillis()) return true
        val error: String? = try {
            val c = URL(src.getTileURLString(index)).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", Configuration.getInstance().userAgentValue)
            try {
                if (c.responseCode != 200) "HTTP ${c.responseCode}"
                else {
                    val bytes = c.inputStream.use { it.readBytes() }
                    // El IGN contesta a veces errores de WMTS en XML con un 200: se mira la firma.
                    if (!looksLikeImage(bytes, src.layer.png)) "no es una imagen"
                    else {
                        val expires = System.currentTimeMillis() + Configuration.getInstance().expirationOverrideDuration!!
                        // saveFile de osmdroid 6.1.20 devuelve siempre false, aunque guarde (su
                        // bytecode acaba en `iconst_0`): se comprueba mirando si está.
                        writer.saveFile(src, index, bytes.inputStream(), expires)
                        if (writer.exists(src, index)) null else "no se pudo guardar"
                    }
                }
            } finally { c.disconnect() }
        } catch (e: Exception) {
            e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
        if (error != null) {
            lastError = error
            android.util.Log.w("GpsDroid", "Tesela ${MapTileIndex.toString(index)}: $error")
        }
        return error == null
    }
}

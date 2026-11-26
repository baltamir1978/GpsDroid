package es.altamirano.gps.data

import android.content.Context
import android.util.Log
import btools.router.FormatJson
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import es.altamirano.gps.core.BRouterJson
import es.altamirano.gps.core.OfflineZone
import es.altamirano.gps.core.PlannedRoute
import es.altamirano.gps.core.RouteTiles
import es.altamirano.gps.core.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Rutas calculadas **dentro de la app** con el motor de BRouter (MIT, por JitPack): sin conexión,
 * con los datos de OpenStreetMap que prepara brouter.de en cuadros de 5° × 5° ([RouteTiles]). Los
 * cuadros se bajan una vez (en `noBackupFilesDir/brouter/segments4`: son grandes y no deben ir a la
 * copia de seguridad) y los perfiles van en los assets. Antes (0.6.0) se le pedían a la app BRouter
 * por su servicio; a Bruno le daba todo tipo de problemas tener otra app que preparar.
 */
object Routing {
    private const val SEGMENTS_URL = "https://brouter.de/brouter/segments4/"
    /** Sube cuando cambian los perfiles de los assets: se vuelven a copiar. */
    private const val PROFILES_VERSION = 1

    private fun baseDir(context: Context) = File(context.noBackupFilesDir, "brouter")
    fun segmentsDir(context: Context) = File(baseDir(context), "segments4").apply { mkdirs() }
    private fun profilesDir(context: Context) = File(baseDir(context), "profiles2")

    sealed interface Result {
        data class Ok(val route: PlannedRoute) : Result
        data class Failed(val message: String) : Result
        /** Faltan cuadros de datos: hay que bajarlos antes (se pregunta, son decenas de MB). */
        data class MissingTiles(val tiles: List<String>) : Result
    }

    /** Un cuadro bajado. */
    data class Tile(val name: String, val bytes: Long, val modified: Long)

    private val _tiles = MutableStateFlow<List<Tile>>(emptyList())
    /** Los cuadros que hay en el móvil (se rellena con [refreshTiles]). */
    val tiles: StateFlow<List<Tile>> = _tiles.asStateFlow()

    fun refreshTiles(context: Context) {
        _tiles.value = segmentsDir(context).listFiles { f -> f.name.endsWith(".rd5") }.orEmpty()
            .map { Tile(it.nameWithoutExtension, it.length(), it.lastModified()) }.sortedBy { it.name }
    }

    fun missingTiles(context: Context, from: Pair<Double, Double>, to: Pair<Double, Double>): List<String> =
        RouteTiles.between(from, to).filter { !File(segmentsDir(context), "$it.rd5").exists() }

    fun deleteTile(context: Context, name: String) {
        File(segmentsDir(context), "$name.rd5").delete()
        refreshTiles(context)
    }

    /** La ruta de (lat, lon) a (lat, lon). Se puede cancelar (para el motor). */
    suspend fun route(context: Context, from: Pair<Double, Double>, to: Pair<Double, Double>, mode: TravelMode,
                      headingDeg: Float? = null, alternative: Int = 0): Result {
        val first = calculate(context, from, to, mode, headingDeg, alternative, offroad = false)
        // En coche, `car-vario` no entra en pistas de tierra: a un sitio en el campo la ruta acaba en la
        // carretera más cercana, a kilómetros (lo vio Bruno entre Vadillo de la Sierra y Cabezas del
        // Villar, Ávila). Si se queda lejos, otra dejando ir por pistas, y la que llegue más cerca.
        if (mode != TravelMode.CAR || first !is Result.Ok || first.route.gapToM(to.first, to.second) <= OFFROAD_IF_GAP_M) return first
        val off = calculate(context, from, to, mode, headingDeg, alternative, offroad = true)
        return if (off is Result.Ok && off.route.gapToM(to.first, to.second) < first.route.gapToM(to.first, to.second) - 100)
            Result.Ok(off.route.copy(offroad = true)) else first
    }

    /** Si la ruta en coche acaba más lejos que esto del destino, se prueba por pistas de tierra. */
    private const val OFFROAD_IF_GAP_M = 300.0

    private suspend fun calculate(context: Context, from: Pair<Double, Double>, to: Pair<Double, Double>, mode: TravelMode,
                                  headingDeg: Float?, alternative: Int, offroad: Boolean): Result = withContext(Dispatchers.IO) {
        missingTiles(context, from, to).takeIf { it.isNotEmpty() }?.let { return@withContext Result.MissingTiles(it) }
        val profile = runCatching { profileFile(context, mode) }.getOrElse {
            return@withContext Result.Failed("No se pudo preparar el perfil de ruta")
        }
        val rc = RoutingContext().apply { localFunction = profile.absolutePath }
        val collector = RoutingParamCollector()
        val waypoints = collector.readPositions(doubleArrayOf(from.second, to.second), doubleArrayOf(from.first, to.first))
        // timode 2 (estilo Locus): salir por la izquierda o la derecha llegan como tales (17, 18).
        val params = HashMap<String, String>().apply {
            put("timode", "2")
            headingDeg?.let { put("heading", it.toInt().toString()) }
            // 0: la mejor; 1 a 3: alternativas (cada una evita lo que usan las anteriores).
            if (alternative > 0) put("alternativeidx", alternative.toString())
        }
        collector.setParams(rc, waypoints, params)
        // Opción del perfil de coche: «carreteras adicionales» (pistas de tierra, caminos agrícolas).
        if (offroad) collector.setProfileParams(rc, mapOf("use_offroad" to "1"))
        val engine = RoutingEngine(null, null, segmentsDir(context), waypoints, rc)
        engine.quite = true
        // doRun bloquea: en este hilo de IO, y cancelar la corrutina le pide al motor que pare.
        val json = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { engine.terminate() } }
            val r = runCatching {
                engine.doRun(60_000)
                engine.errorMessage?.let { "ERROR:$it" } ?: engine.foundTrack?.let { FormatJson(rc).format(it) }
            }.onFailure { Log.w("GpsDroid", "BRouter", it) }.getOrElse { "ERROR:${it.message}" }
            if (cont.isActive) cont.resume(r)
        }
        ensureActive()
        when {
            json == null -> Result.Failed("No se ha encontrado ruta")
            json.startsWith("ERROR:") -> Result.Failed(message(json.removePrefix("ERROR:")))
            else -> BRouterJson.parse(json)?.let { Result.Ok(it) } ?: Result.Failed("No se ha encontrado ruta")
        }
    }

    /** El perfil de [mode], copiado de los assets (con `lookups.dat` al lado: el motor lo busca ahí). */
    private fun profileFile(context: Context, mode: TravelMode): File {
        val dir = profilesDir(context)
        val stamp = File(dir, ".version")
        if (stamp.takeIf { it.exists() }?.readText()?.trim() != PROFILES_VERSION.toString()) {
            dir.mkdirs()
            for (name in context.assets.list("brouter").orEmpty().filter { it.endsWith(".brf") || it == "lookups.dat" }) {
                context.assets.open("brouter/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            stamp.writeText(PROFILES_VERSION.toString())
        }
        return File(dir, "${mode.profile}.brf")
    }

    /** Lo que pesa un cuadro (pregunta al servidor), o null sin red. */
    fun remoteSize(name: String): Long? = runCatching {
        val c = URL("$SEGMENTS_URL$name.rd5").openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 10_000; c.readTimeout = 10_000
        try { if (c.responseCode == 200) c.contentLengthLong.takeIf { it > 0 } else null } finally { c.disconnect() }
    }.getOrNull()

    /**
     * Baja un cuadro a `<nombre>.rd5.part` y lo renombra al acabar: uno a medias nunca se usa. Si se
     * corta, la vez siguiente sigue donde se quedó (`Range`). [progress]: bytes bajados y total.
     */
    suspend fun download(context: Context, name: String, progress: (Long, Long) -> Unit): String? = withContext(Dispatchers.IO) {
        val dir = segmentsDir(context)
        val part = File(dir, "$name.rd5.part")
        val done = File(dir, "$name.rd5")
        try {
            val have = part.length()
            val c = URL("$SEGMENTS_URL$name.rd5").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = c.responseCode
                if (code == 404) return@withContext "No hay datos de rutas para esa zona ($name)"
                if (code != 200 && code != 206) return@withContext "El servidor de BRouter ha respondido $code"
                val resumed = code == 206
                val total = (if (resumed) have else 0L) + c.contentLengthLong.coerceAtLeast(0)
                if (dir.usableSpace < total - (if (resumed) have else 0L) + 50L * 1024 * 1024)
                    return@withContext "No hay sitio en el móvil (hacen falta ${OfflineZone.formatBytes(total)})"
                RandomAccessFile(part, "rw").use { out ->
                    if (resumed) out.seek(have) else out.setLength(0)
                    var got = if (resumed) have else 0L
                    val buf = ByteArray(64 * 1024)
                    c.inputStream.use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            progress(got, total)
                        }
                    }
                    if (total > 0 && got != total) return@withContext "La descarga se ha cortado: vuelve a intentarlo (sigue donde se quedó)"
                }
            } finally { c.disconnect() }
            if (!part.renameTo(done)) return@withContext "No se pudo guardar el cuadro"
            refreshTiles(context)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GpsDroid", "Cuadro $name", e)
            "Sin conexión con el servidor de BRouter: vuelve a intentarlo (sigue donde se quedó)"
        }
    }

    /** Los errores del motor, en cristiano. */
    fun message(raw: String): String = when {
        raw.contains("datafile", true) && raw.contains("not found", true) -> "Faltan los datos de rutas de esta zona"
        raw.contains("not mapped", true) || raw.contains("from-position", true) || raw.contains("to-position", true) ->
            "No hay caminos cerca del inicio o del destino"
        raw.contains("timeout", true) || raw.contains("Timed out", true) || raw.contains("operation killed", true) ->
            "La ruta tarda demasiado en calcularse: prueba con un destino más cerca"
        raw.length > 160 -> raw.take(160) + "…"
        else -> raw
    }
}

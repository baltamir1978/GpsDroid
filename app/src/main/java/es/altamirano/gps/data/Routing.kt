package es.altamirano.gps.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import btools.routingapp.IBRouterService
import es.altamirano.gps.core.BRouterJson
import es.altamirano.gps.core.PlannedRoute
import es.altamirano.gps.core.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume

/**
 * Rutas calculadas por **BRouter** (app aparte, libre, de F-Droid o Google Play): calcula sin
 * conexión con los datos de OpenStreetMap que el usuario se baja por zonas dentro de BRouter. Se le
 * piden por su servicio (`btools.routingapp.BRouterService`, el mismo que usan OsmAnd y Locus), que
 * está exportado y no pide permiso. Así IGN GPS no lleva motor de rutas ni datos de carreteras.
 */
object Routing {
    const val PACKAGE = "btools.routingapp"
    private const val SERVICE = "btools.routingapp.BRouterService"
    const val FDROID_URL = "https://f-droid.org/packages/btools.routingapp/"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    sealed interface Result {
        data class Ok(val route: PlannedRoute) : Result
        data class Failed(val message: String) : Result
    }

    /** La ruta de (lat, lon) a (lat, lon). En un hilo de fondo: BRouter puede tardar varios segundos. */
    suspend fun route(context: Context, from: Pair<Double, Double>, to: Pair<Double, Double>, mode: TravelMode,
                      headingDeg: Float? = null): Result = withContext(Dispatchers.IO) {
        if (!isInstalled(context)) return@withContext Result.Failed("BRouter no está instalado")
        val reply = runCatching {
            withTimeout(70_000) { call(context, params(from, to, mode, headingDeg, withProfile = true)) }
        }.getOrElse { return@withContext Result.Failed(message(it.message ?: "BRouter no responde")) }
        // Si BRouter no tiene ese perfil, con su configuración de servicio (pie, bici o coche).
        val text = if (reply?.startsWith("Profile ") == true && reply.contains("does not exist")) {
            runCatching { withTimeout(70_000) { call(context, params(from, to, mode, headingDeg, withProfile = false)) } }.getOrNull()
        } else reply
        val decoded = text?.let(::decompress)
        val route = decoded?.let { BRouterJson.parse(it) }
        if (route != null) Result.Ok(route) else Result.Failed(message(decoded ?: "BRouter no ha devuelto nada"))
    }

    private fun params(from: Pair<Double, Double>, to: Pair<Double, Double>, mode: TravelMode, heading: Float?, withProfile: Boolean) = Bundle().apply {
        putDoubleArray("lats", doubleArrayOf(from.first, to.first))
        putDoubleArray("lons", doubleArrayOf(from.second, to.second))
        if (withProfile) putString("profile", mode.profile)
        putString("v", mode.serviceMode)
        putString("fast", if (mode.fast) "1" else "0")
        putString("trackFormat", "json")
        // Estilo Locus: salir por la izquierda o la derecha llegan como tales (17, 18).
        putInt("timode", 2)
        putString("maxRunningTime", "60")
        // Comprimido: una ruta larga en coche puede pasar del límite de un Binder (1 MB).
        putString("acceptCompressedResult", "true")
        heading?.let { putInt("heading", it.toInt()) }
    }

    /** Conectar, pedir y soltar. Cada ruta es una conexión: se piden pocas. */
    private suspend fun call(context: Context, params: Bundle): String? = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = IBRouterService.Stub.asInterface(binder)
                // El servicio responde en el hilo que llama: fuera del principal.
                Thread {
                    val r = runCatching { svc.getTrackFromParams(params) }
                        .onFailure { Log.w("GpsDroid", "BRouter: ${it.message}") }.getOrNull()
                    runCatching { context.unbindService(this) }
                    if (cont.isActive) cont.resume(r)
                }.start()
            }
            override fun onServiceDisconnected(name: ComponentName?) { if (cont.isActive) cont.resume(null) }
        }
        val ok = runCatching {
            context.bindService(Intent().setClassName(PACKAGE, SERVICE), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) { if (cont.isActive) cont.resume("No se pudo conectar con BRouter") }
        cont.invokeOnCancellation { runCatching { context.unbindService(conn) } }
    }

    /** `acceptCompressedResult`: base64 de "z64" + gzip. Lo demás llega tal cual. */
    internal fun decompress(text: String): String {
        val t = text.trim()
        if (t.startsWith("{") || t.startsWith("<")) return t
        val bytes = runCatching { Base64.decode(t, Base64.DEFAULT) }.getOrNull() ?: return t
        if (bytes.size < 3 || String(bytes, 0, 3, Charsets.UTF_8) != "z64") return t
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes, 3, bytes.size - 3)).use { it.readBytes().decodeToString() }
        }.getOrDefault(t)
    }

    /** Los errores de BRouter, en cristiano. */
    fun message(raw: String): String = when {
        raw.contains("datafile", true) && raw.contains("not found", true) ->
            "Faltan los datos de esta zona en BRouter: ábrelo, toca «Download Manager» y baja los cuadros de la zona"
        raw.contains("no brouter service config", true) || raw.contains("config15", true) ->
            "BRouter no está preparado: ábrelo una vez y elige su carpeta"
        raw.contains("not mapped", true) || raw.contains("from-position", true) || raw.contains("to-position", true) ->
            "No hay caminos cerca del inicio o del destino"
        raw.contains("timeout", true) || raw.contains("Timed out", true) -> "BRouter ha tardado demasiado: prueba con un destino más cerca"
        raw.length > 160 -> raw.take(160) + "…"
        else -> raw
    }
}

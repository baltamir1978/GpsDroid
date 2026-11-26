package es.altamirano.gps.data

import android.content.Context
import es.altamirano.gps.core.FollowRoute
import es.altamirano.gps.core.PlannedRoute
import es.altamirano.gps.core.TravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Una de las rutas posibles, con lo que se enseña de ella antes de elegir. */
data class RouteOption(val route: PlannedRoute, val lengthM: Double, val ascentM: Double?, val descentM: Double?) {
    companion object {
        fun of(r: PlannedRoute): RouteOption {
            val f = FollowRoute(r.points)
            return RouteOption(r, f.totalM, if (f.hasElevation) f.remainingAscentFrom(0) else null,
                if (f.hasElevation) f.remainingDescentFrom(0) else null)
        }
    }
}

/** La ruta que se está mirando antes de empezar: adónde, cómo, las alternativas calculadas y cuál se ve. */
data class RoutePreviewState(
    val destName: String, val destLat: Double, val destLon: Double, val mode: TravelMode,
    val from: Pair<Double, Double>, val heading: Float?,
    val options: List<RouteOption>, val selected: Int = 0,
    /** Calculando otra alternativa. */
    val loading: Boolean = false,
    /** BRouter ya no da más rutas distintas: «Otra ruta» vuelve a la primera. */
    val exhausted: Boolean = false,
    val message: String? = null,
) {
    val current: RouteOption get() = options[selected]
}

/**
 * La vista previa de una ruta: se enseña en el mapa con sus datos y se pueden pedir alternativas a
 * BRouter (`alternativeidx` 1 a 3: hasta cuatro rutas distintas) antes de empezar a ir por una.
 */
object RoutePreview {
    /** BRouter calcula la ruta principal y hasta tres alternativas. */
    const val MAX_OPTIONS = 4

    private val _state = MutableStateFlow<RoutePreviewState?>(null)
    val state: StateFlow<RoutePreviewState?> = _state.asStateFlow()

    /** Sube al enseñar una ruta (o cambiar de alternativa): la app pasa al mapa y la encuadra. */
    private val _fitRequest = MutableStateFlow(0)
    val fitRequest: StateFlow<Int> = _fitRequest.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun show(context: Context, first: PlannedRoute, destName: String, destLat: Double, destLon: Double, mode: TravelMode,
             from: Pair<Double, Double>, heading: Float?) {
        job?.cancel()
        _state.value = RoutePreviewState(destName, destLat, destLon, mode, from, heading, listOf(RouteOption.of(first)), loading = true)
        _fitRequest.value++
        // Las alternativas, todas de una vez y en segundo plano (como Waze): se van añadiendo al mapa.
        job = scope.launch {
            for (alt in 1 until MAX_OPTIONS) {
                val r = Routing.route(context.applicationContext, from, destLat to destLon, mode, heading, alternative = alt)
                val cur = _state.value ?: return@launch
                val opt = (r as? Routing.Result.Ok)?.route?.let { RouteOption.of(it) }
                // BRouter, cuando ya no encuentra otra distinta, devuelve una casi igual: se acaba ahí.
                if (opt == null || cur.options.any { same(it, opt) }) break
                _state.value = cur.copy(options = cur.options + opt)
            }
            _state.value = _state.value?.copy(loading = false, exhausted = true,
                message = if ((_state.value?.options?.size ?: 0) == 1) "No hay otra ruta distinta" else null)
        }
    }

    /**
     * Yendo ya por una ruta: las rutas posibles desde donde estás hasta el mismo destino (el botón de
     * las dos flechas). La primera se calcula aquí; null si se pudo, o lo que haya que decir.
     */
    suspend fun fromNavigation(context: Context): String? {
        val nav = Following.state.value?.nav ?: return "No vas por ninguna ruta"
        val loc = Gps.location.value?.takeIf { Gps.isFresh(it) } ?: return "Sin posición del GPS"
        val from = loc.latitude to loc.longitude
        val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1f) loc.bearing else null
        return when (val r = Routing.route(context, from, nav.destLat to nav.destLon, nav.mode, heading)) {
            is Routing.Result.Ok -> { show(context, r.route, nav.destName, nav.destLat, nav.destLon, nav.mode, from, heading); null }
            is Routing.Result.Failed -> r.message
            is Routing.Result.MissingTiles -> "Faltan los datos de rutas de la zona"
        }
    }

    /** Ver otra de las rutas (al tocar su botón o su línea en el mapa). */
    fun select(i: Int) {
        val st = _state.value ?: return
        if (i !in st.options.indices || i == st.selected) return
        _state.value = st.copy(selected = i, message = null)
    }

    /** Dos rutas son la misma si miden casi lo mismo (1 %) y pasan por casi los mismos sitios. */
    private fun same(a: RouteOption, b: RouteOption): Boolean {
        if (abs(a.lengthM - b.lengthM) > maxOf(20.0, a.lengthM * 0.01)) return false
        val pa = a.route.points; val pb = b.route.points
        val mid = { p: List<es.altamirano.gps.core.TrackPoint> -> p[p.size / 2] }
        return es.altamirano.gps.core.Geo.distanceM(mid(pa).lat, mid(pa).lon, mid(pb).lat, mid(pb).lon) < 50
    }

    fun cancel() { job?.cancel(); _state.value = null }

    /** Empezar a ir por la ruta que se ve. null si se pudo; si no, lo que haya que decir. */
    fun start(context: Context): String? {
        val st = _state.value ?: return null
        job?.cancel()
        _state.value = null
        return Following.startRoute(context, st.current.route, st.destName, st.destLat, st.destLon, st.mode)
    }
}

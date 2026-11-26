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

    fun show(first: PlannedRoute, destName: String, destLat: Double, destLon: Double, mode: TravelMode,
             from: Pair<Double, Double>, heading: Float?) {
        job?.cancel()
        _state.value = RoutePreviewState(destName, destLat, destLon, mode, from, heading, listOf(RouteOption.of(first)))
        _fitRequest.value++
    }

    /** La siguiente alternativa: la ya calculada, o se pide a BRouter; sin más distintas, vuelta a la primera. */
    fun next(context: Context) {
        val st = _state.value ?: return
        if (st.loading) return
        if (st.selected + 1 < st.options.size) { select(st.selected + 1); return }
        if (st.exhausted || st.options.size >= MAX_OPTIONS) { select(0); return }
        _state.value = st.copy(loading = true, message = null)
        job = scope.launch {
            val r = Routing.route(context.applicationContext, st.from, st.destLat to st.destLon, st.mode, st.heading, alternative = st.options.size)
            val cur = _state.value ?: return@launch
            _state.value = when (r) {
                is Routing.Result.Ok -> {
                    val opt = RouteOption.of(r.route)
                    // BRouter, si no encuentra otra distinta, devuelve una casi igual: no cuenta.
                    if (cur.options.any { same(it, opt) }) cur.copy(loading = false, exhausted = true, selected = 0,
                        message = if (cur.options.size == 1) "No hay otra ruta distinta" else "No hay más rutas distintas")
                    else cur.copy(options = cur.options + opt, selected = cur.options.size, loading = false)
                }
                is Routing.Result.Failed -> cur.copy(loading = false, message = r.message)
                is Routing.Result.MissingTiles -> cur.copy(loading = false, message = "Faltan los datos de rutas de la zona")
            }
            _fitRequest.value++
        }
    }

    private fun select(i: Int) {
        _state.value = _state.value?.copy(selected = i, message = null)
        _fitRequest.value++
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

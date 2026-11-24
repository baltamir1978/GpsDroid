package es.altamirano.gps.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import es.altamirano.gps.core.Waypoint
import es.altamirano.gps.core.WaypointGpx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Los puntos guardados, todos en un GPX (`files/puntos/puntos.gpx`): así se comparten y se abren en
 * cualquier app de mapas tal cual. Son pocos y el fichero es pequeño: se reescribe entero en cada
 * cambio (temporal + rename). El «destino» es el punto hacia el que se quiere ir: el mapa (y el
 * coche) dicen a qué distancia y hacia dónde queda.
 */
object Waypoints {
    private lateinit var file: File
    private lateinit var prefs: SharedPreferences
    private var nextId = 1L

    private val _list = MutableStateFlow<List<Waypoint>>(emptyList())
    val list: StateFlow<List<Waypoint>> = _list.asStateFlow()

    private val _target = MutableStateFlow<Waypoint?>(null)
    val target: StateFlow<Waypoint?> = _target.asStateFlow()

    /** Pedir al mapa que enseñe un punto (sube cada vez, como `Tracks.fitRequest`). */
    data class Focus(val n: Int = 0, val point: Waypoint? = null)
    private val _focus = MutableStateFlow(Focus())
    val focus: StateFlow<Focus> = _focus.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "puntos/puntos.gpx").apply { parentFile?.mkdirs() }
        prefs = context.getSharedPreferences("gps", Context.MODE_PRIVATE)
        val loaded = if (file.exists()) runCatching { WaypointGpx.parse(file.readText()) }.getOrDefault(emptyList()) else emptyList()
        nextId = loaded.size + 1L
        _list.value = loaded
        // El destino se recuerda por sus coordenadas (los id no pasan de una sesión a otra).
        prefs.getString("target", null)?.let { key -> _target.value = loaded.firstOrNull { key(it) == key } }
    }

    fun file(): File = file

    private fun key(w: Waypoint) = "%.7f,%.7f".format(Locale.ROOT, w.lat, w.lon)

    fun nextName() = WaypointGpx.nextName(_list.value)

    fun add(name: String, lat: Double, lon: Double, ele: Double? = null): Waypoint {
        val w = Waypoint(nextId++, name.trim().ifEmpty { nextName() }, lat, lon, ele,
            Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
        save(_list.value + w)
        return w
    }

    /** Los `<wpt>` de un GPX importado. Devuelve cuántos. */
    fun addAll(points: List<Waypoint>): Int {
        if (points.isEmpty()) return 0
        save(_list.value + points.map { it.copy(id = nextId++) })
        return points.size
    }

    fun rename(w: Waypoint, name: String) {
        val n = name.trim().ifEmpty { return }
        save(_list.value.map { if (it.id == w.id) it.copy(name = n) else it })
        if (_target.value?.id == w.id) _target.value = _target.value?.copy(name = n)
    }

    fun delete(w: Waypoint) {
        save(_list.value.filter { it.id != w.id })
        if (_target.value?.id == w.id) setTarget(null)
    }

    fun setTarget(w: Waypoint?) {
        _target.value = w
        prefs.edit { putString("target", w?.let(::key)) }
    }

    fun show(w: Waypoint) { _focus.value = Focus(_focus.value.n + 1, w) }

    private fun save(points: List<Waypoint>) {
        _list.value = points
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching { tmp.writeText(WaypointGpx.write(points)); tmp.renameTo(file) }
    }
}

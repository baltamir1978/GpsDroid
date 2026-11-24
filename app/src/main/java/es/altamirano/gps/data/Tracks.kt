package es.altamirano.gps.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.Gpx
import es.altamirano.gps.core.Track
import es.altamirano.gps.core.WaypointGpx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Un GPX guardado, con lo que enseña la lista. */
data class TrackInfo(val file: File, val name: String, val points: Int, val lengthM: Double, val durationS: Long?, val ascentM: Double, val modified: Long)

/**
 * Los tracks: un GPX por track en `files/tracks` (dentro de la app; se sacan con «Compartir» y se
 * meten con «Importar»). El track abierto se pinta en el mapa encima del que se graba.
 */
object Tracks {
    private lateinit var dir: File

    private val _opened = MutableStateFlow<Track?>(null)
    val opened: StateFlow<Track?> = _opened.asStateFlow()

    /** Sube cada vez que se abre un track: el mapa encuadra el track al verlo cambiar. */
    private val _fitRequest = MutableStateFlow(0)
    val fitRequest: StateFlow<Int> = _fitRequest.asStateFlow()

    private val _version = MutableStateFlow(0)
    /** Sube al guardar, borrar o importar: la lista se vuelve a leer. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) { dir = File(context.filesDir, "tracks").apply { mkdirs() } }

    fun dir(): File = dir

    fun changed() { _version.value++ }

    fun list(): List<TrackInfo> = dir.listFiles { f -> f.name.endsWith(".gpx") }.orEmpty()
        .mapNotNull { f -> runCatching { info(f) }.getOrNull() }
        .sortedByDescending { it.modified }

    fun info(f: File): TrackInfo {
        val t = Gpx.parse(f.readText(), f.nameWithoutExtension) ?: Track(f.nameWithoutExtension, emptyList())
        val times = t.points.mapNotNull { p -> p.time?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } }
        val dur = if (times.size >= 2) times.last().epochSecond - times.first().epochSecond else null
        return TrackInfo(f, t.name, t.points.size, Geo.lengthM(t.points), dur, Geo.ascentM(t.points), f.lastModified())
    }

    fun open(f: File) {
        _opened.value = Gpx.parse(f.readText(), f.nameWithoutExtension)
        _fitRequest.value++
    }

    fun close() { _opened.value = null }

    fun delete(f: File) { f.delete(); changed() }

    /** Escribe con temporal + rename: si la app muere a mitad, el GPX anterior sigue entero. */
    fun write(f: File, track: Track) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(Gpx.write(track))
        tmp.renameTo(f)
    }

    /** Copia un GPX elegido con el selector de ficheros. null si se pudo sin más; si no, lo que haya que decir. */
    fun import(context: Context, uri: Uri): String? {
        val cr = context.contentResolver
        val display = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "importado.gpx"
        val text = cr.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return "No se pudo leer el fichero"
        val base = display.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").ifBlank { "importado" }
        val track = Gpx.parse(text, base) ?: return "No es un fichero GPX"
        // Los puntos (`<wpt>`) que traiga van a los puntos guardados.
        val added = Waypoints.addAll(WaypointGpx.parse(text))
        if (track.points.isEmpty()) return if (added > 0) "Añadidos $added puntos" else "El GPX no tiene puntos de track ni de ruta"
        var f = File(dir, "$base.gpx")
        var i = 2
        while (f.exists()) f = File(dir, "$base ($i).gpx").also { i++ }
        f.writeText(text)
        changed()
        return if (added > 0) "Importado el track y $added puntos" else null
    }
}

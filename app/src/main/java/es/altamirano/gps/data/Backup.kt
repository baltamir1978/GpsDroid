package es.altamirano.gps.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Copia de todo lo del usuario (tracks y puntos) en un ZIP donde él elija. Los datos viven en
 * `filesDir`, que sólo ve la app: si se desinstala se pierden, y Bruno no quiere perder sus
 * tracks. Dentro van como están (los GPX en `tracks/` y `puntos/puntos.gpx`), así que se pueden abrir
 * en cualquier otra app o volver a importar uno a uno.
 */
object Backup {
    fun suggestedName(): String = "IGN-GPS-copia-${LocalDate.now()}.zip"

    /** Escribe el ZIP en [uri]. Devuelve el texto para el aviso (cuántos tracks y puntos). */
    fun export(context: Context, uri: Uri): String {
        // Los temporales del grabador y de los puntos (`.tmp`) están a medio escribir: fuera.
        val tracks = Tracks.dir().listFiles { f -> f.isFile && f.name.endsWith(".gpx") }?.sortedBy { it.name }.orEmpty()
        val points = Waypoints.file().takeIf { it.isFile }
        return try {
            val out = context.contentResolver.openOutputStream(uri) ?: return "No se pudo crear el archivo"
            writeZip(out, tracks.map { "tracks/${it.name}" to it } + listOfNotNull(points?.let { "puntos/${it.name}" to it }))
            val n = Waypoints.list.value.size
            "Copia guardada: ${tracks.size} ${if (tracks.size == 1) "track" else "tracks"} y $n ${if (n == 1) "punto" else "puntos"}"
        } catch (e: Exception) {
            "No se pudo guardar la copia: ${e.message}"
        }
    }

    /** El ZIP en sí, sin Android de por medio (para poder probarlo): cada fichero con su ruta. */
    fun writeZip(out: OutputStream, entries: List<Pair<String, File>>) {
        ZipOutputStream(out.buffered()).use { zip ->
            for ((name, file) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

package es.altamirano.gps.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class BackupTest {
    @Test
    fun zipLlevaCadaFicheroConSuRutaYContenido() {
        val dir = Files.createTempDirectory("backup").toFile()
        val track = File(dir, "2026-11-26_100000.gpx").apply { writeText("<gpx>track</gpx>") }
        val points = File(dir, "puntos.gpx").apply { writeText("<gpx>puntos</gpx>") }
        val out = ByteArrayOutputStream()

        Backup.writeZip(out, listOf("tracks/${track.name}" to track, "puntos/${points.name}" to points))

        val found = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                found[e.name] = zip.readBytes().decodeToString()
            }
        }
        assertEquals(mapOf("tracks/2026-11-26_100000.gpx" to "<gpx>track</gpx>", "puntos/puntos.gpx" to "<gpx>puntos</gpx>"), found)
        dir.deleteRecursively()
    }
}

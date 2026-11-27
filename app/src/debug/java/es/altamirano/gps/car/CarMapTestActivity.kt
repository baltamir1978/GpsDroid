package es.altamirano.gps.car

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.location.Location
import android.view.TextureView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.SurfaceContainer
import es.altamirano.gps.core.Waypoint
import java.io.File

/**
 * Prueba del mapa vectorial del coche en el emulador: lo monta sobre un `ImageReader` (como si fuera
 * la `Surface` de Android Auto, 1280×640 a 160 dpi), simula ir por la Castellana hacia el norte y
 * luego girar al este y al sur, y guarda capturas en `files/carmap-N.png`
 * (`adb shell am start -n es.altamirano.gps/.car.CarMapTestActivity`).
 */
class CarMapTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var map: CarMap
    private lateinit var tv: TextureView
    private val w = 1280; private val h = 640
    private var frames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La «pantalla del coche»: una TextureView de la propia actividad (en el emulador sin ventana un
        // ImageReader no recibía fotogramas de la pantalla virtual); se lee con getBitmap().
        tv = TextureView(this)
        setContentView(tv, android.view.ViewGroup.LayoutParams(w, h))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(w, h)
                map.onSurfaceAvailable(SurfaceContainer(android.view.Surface(st), w, h, 160))
                map.onVisibleAreaChanged(Rect(0, 0, w - 110, h))
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) { frames++ }
        }
        val dark = intent.getBooleanExtra("dark", false)
        map = if (intent.getBooleanExtra("osm", false)) OsmCarMap(this, dark, es.altamirano.gps.core.MapStyle.TOPO) else VectorCarMap(this, dark)
        map.setPanel(ManeuverOverlay.Panel(null, "250 m", "Gira a la derecha", "15:42 · 12 min · 6,3 km"))
        var lat = 40.4720; var lon = -3.6895; var step = 0
        val tick = object : Runnable {
            override fun run() {
                // 12 s al norte, 12 al este, 12 al sur; 15 m/s.
                val bearing = when { step < 12 -> 8f; step < 24 -> 90f; else -> 180f }
                val r = Math.toRadians(bearing.toDouble())
                lat += 15 * Math.cos(r) / 111_320.0; lon += 15 * Math.sin(r) / (111_320.0 * Math.cos(Math.toRadians(lat)))
                val loc = Location("gps").apply {
                    latitude = lat; longitude = lon; this.bearing = bearing; speed = 15f; accuracy = 5f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                val target = Waypoint(1, "Casa", 40.4800, -3.6800, null)
                map.update(loc, listOf(target, Waypoint(2, "Trabajo", 40.4650, -3.6950, null)), target, emptyList(), null)
                step++
                if (step in setOf(10, 22, 34)) main.postDelayed({ capture(step) }, 1500)
                if (step < 36) main.postDelayed(this, 1000) else main.postDelayed({ finish() }, 4000)
            }
        }
        main.postDelayed(tick, 6000)
    }

    private fun capture(n: Int) {
        Log.i("CarMapTest", "fotogramas: $frames")
        val bmp = tv.getBitmap(w, h) ?: run { Log.w("CarMapTest", "sin imagen $n"); return }
        val f = File(filesDir, "carmap-$n.png")
        f.outputStream().use { Bitmap.createBitmap(bmp, 0, 0, w, h).compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("CarMapTest", "guardada $f")
    }

    override fun onDestroy() { map.release(); super.onDestroy() }
}

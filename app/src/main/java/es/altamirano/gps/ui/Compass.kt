package es.altamirano.gps.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import es.altamirano.gps.core.Direction

/**
 * Hacia dónde apunta la parte de arriba del móvil (grados desde el norte magnético; en España la
 * declinación es de 1–2°, no merece la pena corregirla), con el sensor de rotación del sistema.
 * Sólo escucha mientras [enabled]: la brújula gasta. null si el móvil no la tiene.
 */
@Composable
fun rememberCompass(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val heading = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || sensor == null) { heading.value = null; return@DisposableEffect onDispose {} }
        val rot = FloatArray(9); val orient = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orient)
                // Girado de lado, lo de «arriba» es otro borde.
                val extra = when (displayRotation(context)) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
                val deg = Direction.norm(Math.toDegrees(orient[0].toDouble()) + extra).toFloat()
                // Suavizado (por el camino corto) para que la flecha no tiemble.
                val old = heading.value
                heading.value = if (old == null) deg else Direction.norm(old + 0.2 * Direction.relativeDeg(deg.toDouble(), old.toDouble())).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener); heading.value = null }
    }
    return heading
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    runCatching { context.display.rotation }.getOrElse { context.getSystemService(WindowManager::class.java).defaultDisplay.rotation }

/** Hacia dónde vas según el GPS (sólo si te mueves: parado, el rumbo del GPS es ruido). */
fun Location.travelHeading(minSpeed: Float = 1.5f): Float? = if (hasBearing() && hasSpeed() && speed >= minSpeed) bearing else null

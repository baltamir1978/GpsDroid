package es.altamirano.gps.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Un satélite tal como lo da `GnssStatus`. */
data class Satellite(
    val constellation: Int, val svid: Int, val cn0: Float, val elevation: Float, val azimuth: Float,
    val usedInFix: Boolean, val hasPosition: Boolean,
)

/**
 * El GPS del móvil, por demanda (como `GpsSession` de ZeroGPS): lo encienden el mapa y la pantalla de
 * satélites mientras se ven y el servicio de grabación mientras graba; con cero usuarios se apaga
 * para no gastar batería. Sólo `GPS_PROVIDER` de `LocationManager`: nada de Google (en GrapheneOS
 * no hay `FusedLocationProviderClient`) ni de ubicación por red, que en el monte no sirve.
 */
object Gps {
    private lateinit var app: Context
    private val lm by lazy { app.getSystemService(LocationManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private val users = mutableSetOf<String>()
    private var running = false

    private val _location = MutableStateFlow<Location?>(null)
    /** La última posición del GPS de esta sesión (null hasta el primer fix). */
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _sky = MutableStateFlow<List<Satellite>>(emptyList())
    val sky: StateFlow<List<Satellite>> = _sky.asStateFlow()

    private val _active = MutableStateFlow(false)
    /** Encendido (alguien lo está usando y hay permiso). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun init(context: Context) { app = context.applicationContext }

    fun hasPermission(context: Context = app) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isProviderEnabled() = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    /** Una posición tiene fix «de ahora» si llegó hace menos de 10 s. */
    fun isFresh(loc: Location?) = loc != null && SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos < 10_000_000_000L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { _location.value = location }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            _sky.value = (0 until status.satelliteCount).map { i ->
                Satellite(status.getConstellationType(i), status.getSvid(i), status.getCn0DbHz(i),
                    status.getElevationDegrees(i), status.getAzimuthDegrees(i), status.usedInFix(i),
                    // Sin efemérides, Android da elevación y azimut 0.
                    status.getElevationDegrees(i) != 0f || status.getAzimuthDegrees(i) != 0f)
            }
        }
        override fun onStopped() { _sky.value = emptyList() }
    }

    @Synchronized fun acquire(owner: String) { users += owner; update() }
    @Synchronized fun release(owner: String) { users -= owner; update() }

    /** Tras conceder el permiso: los que ya lo pedían no tienen que volver a hacerlo. */
    @Synchronized fun permissionChanged() = update()

    @SuppressLint("MissingPermission")
    private fun update() {
        val want = users.isNotEmpty() && hasPermission()
        if (want == running) return
        running = want
        _active.value = want
        if (want) {
            if (_location.value == null) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { _location.value = it }
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnss, main)
        } else {
            lm.removeUpdates(listener)
            lm.unregisterGnssStatusCallback(gnss)
            _sky.value = emptyList()
        }
    }
}

package es.altamirano.gps.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import androidx.annotation.RequiresApi
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * La altitud sobre el nivel del mar, la de los mapas. El GPS da la altura sobre el elipsoide WGS84,
 * que en España sale unos 50 m más alta (lo que el geoide queda por encima del elipsoide); desde
 * Android 14 el sistema sabe convertirla ([Gps] la añade a cada posición). Sin esa conversión, la
 * del GPS tal cual.
 */
fun Location.altitudeMsl(): Double? = when {
    Build.VERSION.SDK_INT >= 34 && hasMslAltitude() -> mslAltitudeMeters
    hasAltitude() -> altitude
    else -> null
}

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
    /**
     * Desde dónde sale una ruta: la posición de ahora o, sin señal (en un garaje), la última que se
     * supo —la bola gris del mapa: la de esta sesión o la del sistema, y si no la guardada—. null si
     * nunca ha habido ninguna. Con una vieja la ruta se enseña igual y, al llegar la señal, se sigue
     * (o se recalcula si estás en otro sitio).
     */
    fun routeStart(context: Context): Location? = location.value
        ?: Prefs(context).last?.let { (lat, lon) -> Location("last").apply { latitude = lat; longitude = lon } }

    fun isFresh(loc: Location?) = loc != null && SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos < 10_000_000_000L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (Build.VERSION.SDK_INT >= 34 && location.hasAltitude() && !location.hasMslAltitude()) addMsl(location)
            else publish(location)
        }
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

    private val converter by lazy { if (Build.VERSION.SDK_INT >= 34) AltitudeConverter() else null }
    private val altitudeThread by lazy { Executors.newSingleThreadExecutor() }

    /**
     * La conversión lee del disco el modelo del geoide la primera vez (puede tardar): se hace fuera
     * del hilo principal. Desde Android 15, ya cargado, se puede hacer en el acto.
     */
    @RequiresApi(34)
    private fun addMsl(location: Location) {
        val c = converter as AltitudeConverter
        if (Build.VERSION.SDK_INT >= 35 && runCatching { c.tryAddMslAltitudeToLocation(location) }.getOrDefault(false)) {
            publish(location); return
        }
        altitudeThread.execute {
            runCatching { c.addMslAltitudeToLocation(app, location) }
            publish(location)
        }
    }

    /** Sin pisar una posición más nueva con una vieja (la convertida en el otro hilo puede llegar tarde). */
    private fun publish(location: Location) {
        _location.update { cur -> if (cur != null && cur.elapsedRealtimeNanos > location.elapsedRealtimeNanos) cur else location }
    }

    @Synchronized fun acquire(owner: String) { users += owner; update() }
    @Synchronized fun release(owner: String) { users -= owner; update() }

    /** Tras conceder el permiso: los que ya lo pedían no tienen que volver a hacerlo. */
    @Synchronized fun permissionChanged() = update()

    /**
     * Encender y apagar el GPS son llamadas al sistema (Binder) que pueden tardar si el servicio de
     * ubicación va lento: en otro hilo, para que la pantalla nunca se quede esperando (en el emulador,
     * con el GPS simulado atascado, la app daba «no responde» al cambiar de pestaña). Las posiciones
     * siguen llegando al hilo principal.
     */
    private val control by lazy { Handler(android.os.HandlerThread("gps-control").apply { start() }.looper) }

    @SuppressLint("MissingPermission")
    private fun update() {
        val want = users.isNotEmpty() && hasPermission()
        if (want == running) return
        running = want
        _active.value = want
        control.post { apply(want) }
    }

    @SuppressLint("MissingPermission")
    private fun apply(want: Boolean) = runCatching {
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

package es.altamirano.gps.data

import android.content.Context
import android.content.SharedPreferences
import es.altamirano.gps.core.MapStyle

/** Lo que se recuerda entre sesiones (lo que en ZeroGPS es `maps.conf`). */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("gps", Context.MODE_PRIVATE)

    /** El mapa elegido. Se guarda con la clave de la 0.2 («layer»): sus valores siguen valiendo. */
    var style: MapStyle
        get() = MapStyle.fromKey(p.getString("layer", null))
        set(v) = p.edit().putString("layer", v.key).apply()

    /** Dónde se quedó el mapa: lat, lon, zoom. */
    var view: Triple<Double, Double, Double>?
        get() = p.getString("view", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2]) }
        set(v) = p.edit().putString("view", v?.let { "${it.first},${it.second},${it.third}" }).apply()

    /** La última posición con fix, para pintarla en gris al abrir sin fix. */
    var last: Pair<Double, Double>?
        get() = p.getString("last", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        set(v) = p.edit().putString("last", v?.let { "${it.first},${it.second}" }).apply()

    /** Cuándo se miró por última vez si había cuadros de rutas nuevos (0: nunca). */
    var routeTilesChecked: Long
        get() = p.getLong("route_tiles_checked", 0L)
        set(v) = p.edit().putLong("route_tiles_checked", v).apply()

    /** Bajar teselas que no están guardadas (con datos móviles cuesta; en el monte no hay). */
    var online: Boolean
        get() = p.getBoolean("online", true)
        set(v) = p.edit().putBoolean("online", v).apply()

    /** Mantener la pantalla encendida mientras se ve el mapa. */
    var keepScreenOn: Boolean
        get() = p.getBoolean("keep_screen_on", false)
        set(v) = p.edit().putBoolean("keep_screen_on", v).apply()

    /** En el coche, el mapa con la dirección de la marcha hacia arriba (si no, el norte). */
    var carHeadingUp: Boolean
        get() = p.getBoolean("car_heading_up", true)
        set(v) = p.edit().putBoolean("car_heading_up", v).apply()

    /** El zoom del mapa del coche: el último que se dejó (Bruno: «selecciona por defecto el zoom que estás viendo»). */
    var carZoom: Double
        get() = p.getFloat("car_zoom", 14f).toDouble()
        set(v) = p.edit().putFloat("car_zoom", v.toFloat()).apply()
}

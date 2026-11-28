package es.altamirano.gps.car

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import es.altamirano.gps.data.Gps

/**
 * Android Auto. Es una app de «navegación» porque es la única categoría a la que Android Auto deja
 * pintar su propio mapa (las demás sólo enseñan el de Google); no guía calle a calle: enseña el mapa
 * del IGN, tu posición, los puntos y hacia dónde queda el destino. También en el cuadro de
 * instrumentos (`FEATURE_CLUSTER` en el manifiesto) y, con una ruta, la maniobra en el head-up.
 */
class GpsCarAppService : CarAppService() {
    // La lista de anfitriones es «privada» de la librería, pero es la que su documentación manda usar.
    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        // Sólo Android Auto y los demás anfitriones conocidos (la lista que trae la librería).
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    // Una sesión por pantalla: la principal y, si el coche la tiene y lo deja, la del cuadro de instrumentos.
    override fun onCreateSession(sessionInfo: SessionInfo): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = when {
            sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER -> ClusterCarScreen(carContext)
            Gps.hasPermission(carContext) -> MapCarScreen(carContext)
            else -> NoPermissionScreen(carContext)
        }
    }
}

/** El permiso de ubicación sólo se puede dar en el móvil. */
private class NoPermissionScreen(car: androidx.car.app.CarContext) : Screen(car) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder("Abre IGN GPS en el móvil y permite la ubicación.")
        .setTitle("IGN GPS").setHeaderAction(Action.APP_ICON)
        .addAction(Action.Builder().setTitle("Reintentar").setOnClickListener {
            if (Gps.hasPermission(carContext)) screenManager.push(MapCarScreen(carContext)) else invalidate()
        }.build())
        .build()
}

package es.altamirano.gps.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import es.altamirano.gps.R
import es.altamirano.gps.data.Following
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Recording
import es.altamirano.gps.data.Waypoints
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * El mapa en la segunda pantalla del coche (el cuadro de instrumentos, donde Waze también pinta el
 * suyo). Android Auto abre aquí una sesión aparte (`SessionInfo.DISPLAY_TYPE_CLUSTER`) y sólo admite
 * la plantilla de navegación, sin botones a la vista: es el mismo mapa, siguiendo la posición, con la
 * ruta y los radares. La maniobra la pinta el propio coche con lo que manda `MapCarScreen.sendTrip`.
 */
class ClusterCarScreen(car: CarContext) : Screen(car) {
    private val renderer = CarMapRenderer(car, car.isDarkMode)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
            override fun onStart(owner: LifecycleOwner) = Gps.acquire(OWNER)
            override fun onStop(owner: LifecycleOwner) = Gps.release(OWNER)
            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Gps.location, Waypoints.list, Waypoints.target, Recording.state, Following.state) { loc, pts, t, rec, fol ->
                    renderer.update(loc, pts, t, rec.points, fol)
                }.collect {}
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { CarMapRenderer.styleChanges.collect { renderer.reloadStyleFromPrefs() } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { es.altamirano.gps.data.Radars.settingsFlow(carContext).collect { renderer.radarsChanged() } }
        }
    }

    override fun onGetTemplate(): Template {
        // La plantilla exige una tira de botones; en el cuadro no se ve.
        val strip = ActionStrip.Builder().addAction(Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_my_location)).build())
            .setOnClickListener { renderer.follow = true }.build()).build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    private companion object { const val OWNER = "car-cluster" }
}

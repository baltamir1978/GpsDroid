package es.altamirano.gps.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import es.altamirano.gps.core.RadarKind
import es.altamirano.gps.data.RadarAlertMode
import es.altamirano.gps.data.RadarSettings
import es.altamirano.gps.data.Radars

/**
 * Los ajustes del coche: el mapa (dirección o norte arriba) y los avisos de radares: encendido, a qué distancia (cada toque, la siguiente), cómo
 * (con sonido o sólo en pantalla) y de qué clases. Son los mismos ajustes que en el móvil.
 */
class RadarSettingsCarScreen(car: CarContext, private val onHeadingUp: (Boolean) -> Unit) : Screen(car) {
    private fun save(s: RadarSettings) { Radars.save(carContext, s); invalidate() }

    @Suppress("DEPRECATION") // setTitle/setHeaderAction: Header pide un Android Auto más nuevo.
    override fun onGetTemplate(): Template {
        val s = Radars.settings(carContext)
        val prefs = es.altamirano.gps.data.Prefs(carContext)
        val list = ItemList.Builder()
        // El mapa: con la dirección de la marcha hacia arriba o con el norte.
        list.addItem(Row.Builder().setTitle(if (prefs.carHeadingUp) "Mapa: dirección arriba" else "Mapa: norte arriba")
            .addText("Toca para cambiar").setOnClickListener {
                onHeadingUp(!prefs.carHeadingUp); invalidate()
            }.build())
        list.addItem(Row.Builder().setTitle("Avisar de radares")
            .setToggle(Toggle.Builder { on -> save(s.copy(enabled = on)) }.setChecked(s.enabled).build()).build())
        if (s.enabled) {
            val d = s.distanceM
            list.addItem(Row.Builder().setTitle("Avisar a " + if (d >= 1000) "${d / 1000} km" else "$d m")
                .addText("Toca para cambiar").setOnClickListener {
                    val all = RadarSettings.DISTANCES
                    save(s.copy(distanceM = all[(all.indexOf(d).coerceAtLeast(0) + 1) % all.size]))
                }.build())
            list.addItem(Row.Builder().setTitle(s.mode.label).addText("Toca para cambiar").setOnClickListener {
                save(s.copy(mode = if (s.mode == RadarAlertMode.SOUND) RadarAlertMode.SILENT else RadarAlertMode.SOUND))
            }.build())
            listOf(RadarKind.FIXED to "Fijos y en remolque", RadarKind.SECTION to "Tramos de velocidad media",
                RadarKind.MOBILE to "Móviles anunciados", RadarKind.MOBILE_STRETCH to "Tramos de radar móvil de la DGT").forEach { (k, label) ->
                list.addItem(Row.Builder().setTitle(label)
                    .setToggle(Toggle.Builder { on -> save(s.copy(kinds = if (on) s.kinds + k else s.kinds - k)) }.setChecked(k in s.kinds).build()).build())
            }
        }
        return ListTemplate.Builder().setTitle("Ajustes").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

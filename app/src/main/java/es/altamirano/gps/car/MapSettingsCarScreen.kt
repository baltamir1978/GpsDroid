package es.altamirano.gps.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import es.altamirano.gps.data.Prefs
import es.altamirano.gps.data.Tiles

/**
 * El botón de capas del coche: qué mapa se ve, si se usa la red y la orientación. Antes el botón
 * pasaba al mapa siguiente sin más; Bruno pidió poder poner «con conexión o sin ella» desde Android
 * Auto (la radio por internet se le cortaba) y en el mapa no cabían más botones (cuatro y cuatro).
 */
class MapSettingsCarScreen(car: CarContext, private val renderer: CarMapRenderer) : Screen(car) {
    @Suppress("DEPRECATION") // setTitle/setHeaderAction: Header pide un Android Auto más nuevo.
    override fun onGetTemplate(): Template {
        val prefs = Prefs(carContext)
        val list = ItemList.Builder()
        CarMapRenderer.CAR_STYLES.forEach { s ->
            val row = Row.Builder().setTitle(s.label).setOnClickListener { renderer.setStyle(s); screenManager.pop() }
            if (s == renderer.currentStyle) row.addText("El de ahora")
            list.addItem(row.build())
        }
        // Sin red sólo se ve lo ya guardado (lo visto y lo descargado) y no se bajan los mapas del camino.
        list.addItem(Row.Builder().setTitle("Usar la red").addText(if (prefs.online) "Baja los mapas que faltan" else "Sólo lo guardado en el móvil")
            .setToggle(Toggle.Builder { on -> prefs.online = on; Tiles.setOnline(on); invalidate() }.setChecked(prefs.online).build()).build())
        list.addItem(Row.Builder().setTitle(if (prefs.carHeadingUp) "Dirección arriba" else "Norte arriba")
            .addText("Toca para cambiar").setOnClickListener { renderer.setHeadingUp(!prefs.carHeadingUp); invalidate() }.build())
        return ListTemplate.Builder().setTitle("Mapa").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

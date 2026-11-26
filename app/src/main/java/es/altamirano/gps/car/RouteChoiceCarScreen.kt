package es.altamirano.gps.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import es.altamirano.gps.core.Geo
import es.altamirano.gps.data.RoutePreview
import es.altamirano.gps.ui.duration
import kotlinx.coroutines.launch

/**
 * Elegir entre las rutas posibles, en el coche: la plantilla de Android Auto para eso (lista a un lado
 * y todas las rutas dibujadas en el mapa; la elegida, fuerte). «Empezar» va por la elegida; atrás,
 * nada (si ya ibas por una ruta, sigues por ella).
 */
class RouteChoiceCarScreen(car: CarContext) : Screen(car) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RoutePreview.state.collect { st -> if (st == null) screenManager.pop() else invalidate() }
            }
        }
    }

    @Suppress("DEPRECATION") // setTitle/setHeaderAction: Header pide un Android Auto más nuevo.
    override fun onGetTemplate(): Template {
        val st = RoutePreview.state.value
        val b = RoutePreviewNavigationTemplate.Builder()
            .setTitle(st?.let { "Hasta «${it.destName}»" } ?: "Rutas")
            .setHeaderAction(Action.BACK)
        if (st == null || st.options.isEmpty()) return b.setLoading(true).build()
        val list = ItemList.Builder()
            .setOnSelectedListener { i -> RoutePreview.select(i) }
            .setSelectedIndex(st.selected.coerceIn(0, st.options.size - 1))
        st.options.forEachIndexed { i, o ->
            list.addItem(Row.Builder()
                .setTitle(o.route.totalS?.let { duration(it) } ?: "Ruta ${i + 1}")
                .addText(Geo.formatDistance(o.lengthM) + if (o.route.offroad) " · con pistas de tierra" else "")
                .build())
        }
        b.setItemList(list.build())
        b.setNavigateAction(Action.Builder().setTitle(if (st.loading) "Empezar (buscando más…)" else "Empezar").setOnClickListener {
            RoutePreview.start(carContext)?.let { CarToast.makeText(carContext, it, CarToast.LENGTH_LONG).show() }
        }.build())
        return b.build()
    }

}

package es.altamirano.gps.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.Action
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
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
            // Android Auto exige que cada fila lleve la duración o la distancia como «span» (si no, la
            // plantilla lanza IllegalArgumentException y la app se cierra): el coche las formatea a su modo.
            val title = o.route.totalS?.let { s -> spanned(duration(s), DurationSpan.create(s)) } ?: SpannableString("Ruta ${i + 1}")
            val dist = Geo.formatDistance(o.lengthM)
            val text = spanned(dist + if (o.route.offroad) " · con pistas de tierra" else "",
                DistanceSpan.create(carDistance(o.lengthM)), dist.length)
            list.addItem(Row.Builder().setTitle(title).addText(text).build())
        }
        b.setItemList(list.build())
        b.setNavigateAction(Action.Builder().setTitle(if (st.loading) "Empezar (buscando más…)" else "Empezar").setOnClickListener {
            RoutePreview.start(carContext)?.let { CarToast.makeText(carContext, it, CarToast.LENGTH_LONG).show() }
        }.build())
        return b.build()
    }

    private fun spanned(text: String, span: Any, end: Int = text.length) =
        SpannableString(text).apply { setSpan(span, 0, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE) }

    private fun carDistance(m: Double): Distance =
        if (m >= 1000) Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS) else Distance.create(m, Distance.UNIT_METERS)
}

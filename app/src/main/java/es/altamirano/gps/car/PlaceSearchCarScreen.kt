package es.altamirano.gps.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import es.altamirano.gps.R
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.Place
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Places
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Buscar un sitio desde el coche (nombre, dirección, pueblo, negocio) y hacerlo destino: tocar un
 * resultado calcula la ruta en coche, como un punto guardado. El teclado del coche sólo se puede
 * usar parado (lo bloquea Android Auto en marcha).
 */
class PlaceSearchCarScreen(car: CarContext, private val onPick: (Place) -> Unit) : Screen(car) {
    private var query = ""
    private var results: List<Place> = emptyList()
    private var loading = false
    private var message: String? = null
    private var job: Job? = null

    private fun search(text: String, now: Boolean) {
        query = text
        job?.cancel()
        if (text.trim().length < 3) { results = emptyList(); loading = false; message = null; invalidate(); return }
        job = lifecycleScope.launch {
            if (!now) delay(500)   // mientras se escribe, no una petición por letra
            loading = true; invalidate()
            when (val r = Places.search(carContext, text)) {
                is Places.Result.Ok -> { results = r.places; message = if (r.places.isEmpty()) "No se ha encontrado nada" else null }
                is Places.Result.Failed -> { results = emptyList(); message = r.message }
            }
            loading = false; invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText, now = false)
            override fun onSearchSubmitted(searchText: String) = search(searchText, now = true)
        }).setHeaderAction(Action.BACK).setSearchHint("Dirección, pueblo, sitio…").setInitialSearchText(query)
            .setShowKeyboardByDefault(results.isEmpty())
        if (loading) return b.setLoading(true).build()
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val loc = Gps.location.value?.takeIf { Gps.isFresh(it) }
        val list = ItemList.Builder()
        message?.let { list.setNoItemsMessage(it) }
        for (p in results.take(limit)) {
            val row = Row.Builder().setTitle(p.name)
                .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_place)).build())
                .setOnClickListener { screenManager.popToRoot(); onPick(p) }
            val km = loc?.let { Geo.distanceM(it.latitude, it.longitude, p.lat, p.lon) / 1000.0 }
            val text = SpannableString((if (km != null) "· " else "") + p.detail)
            if (km != null) {
                val d = if (km < 1) Distance.create(km * 1000, Distance.UNIT_METERS) else Distance.create(km, Distance.UNIT_KILOMETERS)
                text.setSpan(DistanceSpan.create(d), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
            if (text.isNotBlank()) row.addText(text)
            list.addItem(row.build())
        }
        return b.setItemList(list.build()).build()
    }
}

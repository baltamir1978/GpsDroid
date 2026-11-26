package es.altamirano.gps.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import es.altamirano.gps.R
import es.altamirano.gps.core.Direction
import es.altamirano.gps.core.Geo
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Following
import es.altamirano.gps.core.FollowStatus
import es.altamirano.gps.data.altitudeMsl
import es.altamirano.gps.data.Recording
import es.altamirano.gps.data.Waypoints
import es.altamirano.gps.ui.MapColors
import es.altamirano.gps.ui.travelHeading
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * La pantalla del coche: el mapa a pantalla completa con dos tiras de botones (las que permite la
 * plantilla de navegación: cuatro y cuatro) y, si hay destino, una tarjeta con la flecha, la
 * distancia y hacia dónde queda.
 */
class MapCarScreen(car: CarContext) : Screen(car) {
    private val renderer = CarMapRenderer(car)
    /** Lo que enseña la tarjeta; sólo se rehace la plantilla si cambia (el coche limita los refrescos). */
    private var card: Card? = null
    private var recording = Recording.state.value.active
    private var lastInvalidate = 0L

    private data class Card(val title: String, val text: String, val arrowDeg: Int?)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                renderer.onFollowChanged = { invalidate() }
            }
            override fun onStart(owner: LifecycleOwner) = Gps.acquire(OWNER)
            override fun onStop(owner: LifecycleOwner) = Gps.release(OWNER)
            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Gps.location, Waypoints.list, Waypoints.target, Recording.state, Following.state) { loc, pts, t, rec, fol -> Five(loc, pts, t, rec, fol) }
                    .collect { (loc, pts, t, rec, fol) ->
                        renderer.update(loc, pts, t, rec.points, fol)
                        val newCard = cardFor(loc, t, rec, fol)
                        if (newCard != card || rec.active != recording) {
                            // Como mucho cada 2 s (salvo al cambiar de destino o de grabar).
                            val now = SystemClock.elapsedRealtime()
                            val important = newCard?.title != card?.title || rec.active != recording
                            if (important || now - lastInvalidate >= 2000) {
                                card = newCard; recording = rec.active; lastInvalidate = now
                                invalidate()
                            }
                        }
                    }
            }
        }
    }

    private data class Five<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private fun cardFor(loc: android.location.Location?, t: es.altamirano.gps.core.Waypoint?, rec: es.altamirano.gps.data.RecordingState,
                        fol: FollowState?): Card? {
        val fresh = loc?.takeIf { Gps.isFresh(it) }
        val recText = if (rec.active) "Grabando · ${Geo.formatDistance(rec.lengthM)}" else null
        // Un destino manda sobre el track (se ha elegido después, desde el coche o el móvil).
        if (t == null && fol != null) return followCard(fol, fresh, recText)
        if (t == null) return recText?.let { Card(it, if (fresh == null) "Sin señal GPS" else "Sin destino", null) }
        if (fresh == null) return Card(t.name, listOfNotNull("Sin señal GPS", recText).joinToString(" · "), null)
        val d = Geo.distanceM(fresh.latitude, fresh.longitude, t.lat, t.lon)
        val b = Direction.bearingDeg(fresh.latitude, fresh.longitude, t.lat, t.lon)
        // En marcha, a qué mano queda; parado, el rumbo (el mapa va con el norte arriba).
        val heading = fresh.travelHeading(2f)
        // Cifras redondeadas para no rehacer la tarjeta a cada metro.
        val shown = if (d < 1000) (d / 10).roundToInt() * 10.0 else d
        val text = when {
            d < 25 -> "Has llegado"
            heading != null -> "${Geo.formatDistance(shown)} al ${Direction.cardinal(b)} · ${Direction.relative(b, heading.toDouble())}"
            else -> Direction.describe(shown, b)
        }
        val arrow = ((Direction.norm(b - (heading ?: 0f)) / 15).roundToInt() * 15) % 360
        return Card(t.name, listOfNotNull(text, recText).joinToString(" · "), arrow.takeIf { d >= 25 })
    }

    /** El track que se sigue: lo que queda o, fuera de él, hacia dónde volver (con flecha). */
    private fun followCard(fol: FollowState, fresh: android.location.Location?, recText: String?): Card {
        val f = fol.fix
        if (fresh == null || f == null) return Card(fol.name, listOfNotNull("Sin señal GPS", recText).joinToString(" · "), null)
        val away = f.status == FollowStatus.OFF_TRACK || f.status == FollowStatus.APPROACHING
        val title = if (f.status == FollowStatus.OFF_TRACK) "Fuera del track" else fol.name
        // Cifras redondeadas (a 100 m lo que queda, a 10 m lo de volver) para no rehacer la tarjeta a cada paso.
        val text = if (away) {
            val d = (f.match.offM / 10).roundToInt() * 10.0
            "${Geo.formatDistance(d)} al ${Direction.cardinal(f.bearingToTrack)}"
        } else Following.summary(fol.copy(fix = f.copy(remainingM = (f.remainingM / 100).roundToInt() * 100.0)))
        val heading = fresh.travelHeading(2f)
        val arrow = if (away) ((Direction.norm(f.bearingToTrack - (heading ?: 0f)) / 15).roundToInt() * 15) % 360 else null
        return Card(title, listOfNotNull(text, recText).joinToString(" · "), arrow)
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(icon(R.drawable.car_layers) {
                CarToast.makeText(carContext, renderer.nextStyle().label, CarToast.LENGTH_SHORT).show()
            })
            .addAction(icon(R.drawable.car_place) { screenManager.push(PointsCarScreen(carContext)) })
            .addAction(icon(R.drawable.car_add_place) { savePoint() })
            .addAction(icon(if (recording) R.drawable.car_stop else R.drawable.car_record,
                if (recording) CarColor.RED else CarColor.DEFAULT) { toggleRecording() })
            .build()
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(icon(R.drawable.car_zoom_in) { renderer.zoomBy(1.0) })
            .addAction(icon(R.drawable.car_zoom_out) { renderer.zoomBy(-1.0) })
            .addAction(icon(R.drawable.car_my_location, if (renderer.follow) CarColor.BLUE else CarColor.DEFAULT) { renderer.follow = true })
            .build()
        val b = NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .setPanModeListener { panning -> if (panning) renderer.follow = false }
            .setBackgroundColor(CarColor.createCustom(MapColors.target.toArgb(), MapColors.target.toArgb()))
        card?.let { c ->
            val info = MessageInfo.Builder(c.title).setText(c.text)
            c.arrowDeg?.let { info.setImage(CarIcon.Builder(IconCompat.createWithBitmap(arrow(it))).build()) }
            b.setNavigationInfo(info.build())
        }
        return b.build()
    }

    private fun icon(res: Int, tint: CarColor = CarColor.DEFAULT, onClick: () -> Unit) = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(tint).build())
        .setOnClickListener(onClick)
        .build()

    private fun savePoint() {
        val loc = Gps.location.value?.takeIf { Gps.isFresh(it) }
        if (loc == null) { CarToast.makeText(carContext, "Sin señal GPS", CarToast.LENGTH_SHORT).show(); return }
        val w = Waypoints.add(Waypoints.nextName(), loc.latitude, loc.longitude, loc.altitudeMsl())
        CarToast.makeText(carContext, "Guardado «${w.name}» (se renombra en el móvil)", CarToast.LENGTH_LONG).show()
    }

    private fun toggleRecording() {
        if (recording) { Recording.stop(carContext); return }
        // Arrancar un servicio en primer plano puede no estar permitido si la app no está a la vista en el móvil.
        runCatching { Recording.start(carContext) }.onFailure {
            CarToast.makeText(carContext, "No se pudo empezar a grabar: empieza desde el móvil", CarToast.LENGTH_LONG).show()
        }
    }

    /** La flecha de la tarjeta, girada hacia el destino (blanca: va sobre el fondo naranja). */
    private fun arrow(deg: Int): Bitmap {
        val s = 128
        val bmp = createBitmap(s, s)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val path = Path().apply {
            moveTo(s / 2f, s * 0.08f); lineTo(s * 0.82f, s * 0.9f); lineTo(s / 2f, s * 0.72f); lineTo(s * 0.18f, s * 0.9f); close()
        }
        c.rotate(deg.toFloat(), s / 2f, s / 2f)
        c.drawPath(path, p)
        return bmp
    }

    private companion object { const val OWNER = "car" }
}

/** Los puntos, los más cerca primero (el coche limita cuántas filas se ven); tocar uno lo hace destino. */
class PointsCarScreen(car: CarContext) : Screen(car) {
    @Suppress("DEPRECATION") // setTitle/setHeaderAction: Header pide un Android Auto más nuevo.
    override fun onGetTemplate(): Template {
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val loc = Gps.location.value?.takeIf { Gps.isFresh(it) }
        val target = Waypoints.target.value
        val list = ItemList.Builder().setNoItemsMessage("No hay puntos guardados")
        var n = 0
        if (target != null) {
            list.addItem(Row.Builder().setTitle("Dejar de ir a «${target.name}»").setOnClickListener {
                Waypoints.setTarget(null); screenManager.pop()
            }.build())
            n++
        }
        val sorted = if (loc == null) Waypoints.list.value.sortedBy { it.name.lowercase() }
            else Waypoints.list.value.sortedBy { Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lon) }
        for (w in sorted.take((limit - n).coerceAtLeast(0))) {
            val row = Row.Builder().setTitle(w.name)
                .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_place))
                    .setTint(CarColor.createCustom((if (w.id == target?.id) MapColors.target else MapColors.waypoint).toArgb(),
                        (if (w.id == target?.id) MapColors.target else MapColors.waypoint).toArgb())).build())
                .setOnClickListener { Waypoints.setTarget(w); screenManager.pop() }
            if (loc != null) row.addText(Direction.describe(Geo.distanceM(loc.latitude, loc.longitude, w.lat, w.lon),
                Direction.bearingDeg(loc.latitude, loc.longitude, w.lat, w.lon)))
            list.addItem(row.build())
        }
        return ListTemplate.Builder().setTitle("Puntos").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

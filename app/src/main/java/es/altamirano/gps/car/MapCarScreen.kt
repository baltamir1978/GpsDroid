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
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import es.altamirano.gps.data.Routing
import es.altamirano.gps.data.RoutePreview
import es.altamirano.gps.data.Tiles
import es.altamirano.gps.core.TravelMode
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.Maneuver
import es.altamirano.gps.core.Maneuver.Kind as MKind
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.TravelEstimate
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

    private data class Card(val title: String, val text: String, val arrowDeg: Int?, val step: StepCard? = null)

    /** La maniobra de una ruta, con la distancia ya redondeada (para no rehacer la plantilla a cada metro). */
    private data class StepCard(val kind: MKind, val exit: Int, val angle: Int, val distanceM: Int,
                                val cue: String, val then: String?,
                                /** Lo que queda hasta el destino (a 100 m) y en segundos (al minuto), para la línea de abajo. */
                                val remainingM: Int = 0, val remainingS: Long? = null)

    /** Se le ha dicho al coche que estamos navegando (para el cuadro de instrumentos y su botón de parar). */
    private var navigating = false
    private var following = Following.state.value != null
    private val nav by lazy { carContext.getCarService(NavigationManager::class.java) }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                renderer.onFollowChanged = { invalidate() }
                // El coche puede parar la navegación por su cuenta (sus mandos, otra app de navegación).
                nav.setNavigationManagerCallback(object : NavigationManagerCallback {
                    override fun onStopNavigation() { navigating = false; Following.stop(carContext) }
                })
            }
            override fun onStart(owner: LifecycleOwner) = Gps.acquire(OWNER)
            override fun onStop(owner: LifecycleOwner) = Gps.release(OWNER)
            override fun onDestroy(owner: LifecycleOwner) {
                if (navigating) runCatching { nav.navigationEnded() }
                runCatching { nav.clearNavigationManagerCallback() }
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
            }
        })
        // Las rutas posibles (al elegir destino o con el botón de las dos flechas), en el mapa del coche.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { RoutePreview.state.collect { renderer.showPreview(it) } }
        }
        // Los ajustes de radares (clases, encendido) cambian lo que se pinta en el mapa.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { es.altamirano.gps.data.Radars.settingsFlow(carContext).collect { renderer.radarsChanged() } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Gps.location, Waypoints.list, Waypoints.target, Recording.state, Following.state) { loc, pts, t, rec, fol -> Five(loc, pts, t, rec, fol) }
                    .collect { (loc, pts, t, rec, fol) ->
                        renderer.update(loc, pts, t, rec.points, fol)
                        // Empieza o acaba una ruta (desde el móvil o desde aquí): avisar al coche y
                        // cambiar los botones; al empezar, el mapa vuelve a seguirte.
                        val navNow = fol?.nav != null
                        if (navNow != navigating) {
                            navigating = navNow
                            runCatching { if (navNow) nav.navigationStarted() else nav.navigationEnded() }
                            if (navNow) renderer.follow = true
                            invalidate()
                        }
                        if ((fol != null) != following) { following = fol != null; invalidate() }
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
        if (t == null && fol != null) {
            val next = fol.nav?.next
            val f = fol.fix
            if (next != null && fresh != null && f?.status == FollowStatus.ON_TRACK && fol.nav.rerouting.not()) {
                val d = next.distanceM.let { if (it < 1000) (it / 10).roundToInt() * 10 else (it / 100).roundToInt() * 100 }
                // Un radar a la vista va en la misma tarjeta, detrás de la maniobra (a 50 m: no rehacerla a cada metro).
                val radar = fol.radar?.let { r -> " · " + r.copy(distanceM = (r.distanceM / 50).roundToInt() * 50.0).line() } ?: ""
                return Card(next.maneuver.title(), Following.remainingText(f.copy(remainingM = (f.remainingM / 100).roundToInt() * 100.0)),
                    null, StepCard(next.maneuver.kind, next.maneuver.exit, next.maneuver.angle, d, next.maneuver.title() + radar, next.then?.title(),
                        (f.remainingM / 100).roundToInt() * 100, f.remainingS?.let { (it + 30) / 60 * 60 }))
            }
            return followCard(fol, fresh, recText)
        }
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
            .addAction(icon(R.drawable.car_search) { screenManager.push(PointsCarScreen(carContext, { renderer.setHeadingUp(it) }) { w -> routeTo(w) }) })
            // Con una ruta o un track en marcha, este botón es el de pararlo (guardar un punto conduciendo es lo de menos).
            .addAction(if (following) icon(R.drawable.car_close, CarColor.RED) { Following.stop(carContext) }
                else icon(R.drawable.car_add_place) { savePoint() })
            // Yendo por una ruta, el botón de las dos flechas (otras rutas desde aquí) en vez del de grabar.
            .addAction(if (navigating) icon(R.drawable.car_alt_route) { otherRoutes() }
                else icon(if (recording) R.drawable.car_stop else R.drawable.car_record,
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
            .setPanModeListener { panning -> if (panning) renderer.userMovedMap() }
            .setBackgroundColor(CarColor.createCustom(MapColors.target.toArgb(), MapColors.target.toArgb()))
        card?.step?.let { s ->
            // Yendo por una ruta: la maniobra como la enseñan los navegadores (y el cuadro del coche).
            val info = RoutingInfo.Builder().setCurrentStep(step(s.kind, s.exit, s.angle, s.cue, s.then == null),
                if (s.distanceM >= 1000) Distance.create(s.distanceM / 1000.0, Distance.UNIT_KILOMETERS) else Distance.create(s.distanceM.toDouble(), Distance.UNIT_METERS))
            b.setNavigationInfo(info.build())
            // Debajo de la maniobra, como en los navegadores: hora de llegada, tiempo y kilómetros que quedan.
            s.remainingS?.let { secs ->
                val left = if (s.remainingM >= 1000) Distance.create(s.remainingM / 1000.0, Distance.UNIT_KILOMETERS)
                    else Distance.create(s.remainingM.toDouble(), Distance.UNIT_METERS)
                b.setDestinationTravelEstimate(TravelEstimate.Builder(left, java.time.ZonedDateTime.now().plusSeconds(secs))
                    .setRemainingTimeSeconds(secs).build())
            }
            return b.build()
        }
        card?.let { c ->
            val info = MessageInfo.Builder(c.title).setText(c.text)
            c.arrowDeg?.let { info.setImage(CarIcon.Builder(IconCompat.createWithBitmap(arrow(it))).build()) }
            b.setNavigationInfo(info.build())
        }
        return b.build()
    }

    private fun step(kind: MKind, exit: Int, angle: Int, cue: String, @Suppress("UNUSED_PARAMETER") last: Boolean): Step {
        val type = when (kind) {
            MKind.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            MKind.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            MKind.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            MKind.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            MKind.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            MKind.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            MKind.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
            MKind.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            MKind.EXIT_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            MKind.EXIT_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            MKind.UTURN_LEFT, MKind.UTURN -> Maneuver.TYPE_U_TURN_LEFT
            MKind.UTURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
            // En España las rotondas se recorren en sentido contrario a las agujas del reloj.
            MKind.ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
            MKind.ROUNDABOUT_LEFT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
            MKind.END -> Maneuver.TYPE_DESTINATION
            else -> Maneuver.TYPE_STRAIGHT
        }
        val m = Maneuver.Builder(type)
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(maneuverIcon(kind, angle))).build())
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW || type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW)
            m.setRoundaboutExitNumber(kotlin.math.abs(exit).coerceAtLeast(1))
        return Step.Builder(cue).setManeuver(m.build()).build()
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

    /**
     * Ir a un punto desde el coche: ruta en coche con BRouter desde donde estás, y a guiar. Si faltan
     * los datos de la zona se bajan (con red); si no se puede, o no hay ruta, se indica la dirección en
     * línea recta como último recurso.
     */
    private fun routeTo(w: es.altamirano.gps.core.Waypoint) {
        val here = Gps.location.value?.takeIf { Gps.isFresh(it) }
        if (here == null) { beeline(w, "Sin señal GPS: te indico la dirección cuando la haya"); return }
        CarToast.makeText(carContext, "Calculando la ruta a «${w.name}»…", CarToast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val from = here.latitude to here.longitude
            var r = Routing.route(carContext, from, w.lat to w.lon, TravelMode.CAR, here.travelHeading(1f))
            if (r is Routing.Result.MissingTiles) {
                if (!Tiles.canUseNetwork(carContext)) { beeline(w, "Faltan los datos de rutas de esta zona y no hay red: te indico la dirección"); return@launch }
                CarToast.makeText(carContext, "Bajando los datos de rutas de la zona…", CarToast.LENGTH_LONG).show()
                for (name in r.tiles) {
                    Routing.download(carContext, name) { _, _ -> }?.let { beeline(w, "$it. Te indico la dirección"); return@launch }
                }
                r = Routing.route(carContext, from, w.lat to w.lon, TravelMode.CAR, here.travelHeading(1f))
            }
            when (r) {
                is Routing.Result.Ok -> {
                    Waypoints.setTarget(null)
                    // Como Waze: todas las rutas posibles en el mapa, a elegir; «Empezar» va por la elegida.
                    RoutePreview.show(carContext, r.route, w.name, w.lat, w.lon, TravelMode.CAR, from, here.travelHeading(1f))
                    screenManager.push(RouteChoiceCarScreen(carContext))
                }
                is Routing.Result.Failed -> beeline(w, "${r.message}: te indico la dirección")
                is Routing.Result.MissingTiles -> beeline(w, "Faltan los datos de rutas de esta zona: te indico la dirección")
            }
        }
    }

    /** Las otras rutas posibles desde donde estás hasta el mismo destino. */
    private fun otherRoutes() {
        CarToast.makeText(carContext, "Buscando rutas…", CarToast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val err = RoutePreview.fromNavigation(carContext)
            if (err != null) CarToast.makeText(carContext, err, CarToast.LENGTH_LONG).show()
            else screenManager.push(RouteChoiceCarScreen(carContext))
        }
    }

    /** El último recurso: «ir hacia aquí» con la flecha en línea recta. */
    private fun beeline(w: es.altamirano.gps.core.Waypoint, why: String) {
        Waypoints.setTarget(w)
        CarToast.makeText(carContext, why, CarToast.LENGTH_LONG).show()
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

    /**
     * El dibujo de la maniobra como en los navegadores: un tallo que sube desde abajo y se dobla hacia
     * donde gira la calle, con la punta al final; la rotonda con su anillo; el destino, una bandera.
     * El lado sale del tipo de maniobra y la cuánto gira, del ángulo de BRouter (si es razonable).
     * Antes era una flecha suelta girada, que parecía el cursor de posición.
     */
    private fun maneuverIcon(kind: MKind, angle: Int): Bitmap {
        val s = 128f
        val bmp = createBitmap(s.toInt(), s.toInt())
        val c = Canvas(bmp)
        val w = s * 0.13f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = w
            strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val side = kind.side
        val typical = when (kind) {
            MKind.SLIGHT_LEFT, MKind.SLIGHT_RIGHT -> 40
            MKind.SHARP_LEFT, MKind.SHARP_RIGHT -> 135
            MKind.KEEP_LEFT, MKind.KEEP_RIGHT -> 25
            MKind.EXIT_LEFT, MKind.EXIT_RIGHT -> 45
            else -> 90
        }
        val a = kotlin.math.abs(angle)
        // Cuánto se dobla: el ángulo real dentro de lo que corresponde al tipo, para que «ligeramente» no parezca un giro cerrado.
        val bend = side * (if (a in 1..180) a.coerceIn(typical - 25, typical + 30) else typical).coerceIn(15, 160)
        /** La punta: un triángulo en (x, y) mirando a [deg] (0 = arriba, positivo a la derecha). */
        fun head(x: Float, y: Float, deg: Float) {
            val r = Math.toRadians(deg.toDouble())
            val ux = kotlin.math.sin(r).toFloat(); val uy = -kotlin.math.cos(r).toFloat()
            val len = s * 0.21f; val half = s * 0.18f
            c.drawPath(Path().apply {
                moveTo(x + ux * len, y + uy * len)
                lineTo(x - uy * half, y + ux * half)
                lineTo(x + uy * half, y - ux * half)
                close()
            }, fill)
        }
        when (kind) {
            MKind.END -> {
                // Bandera: mástil y banderín.
                c.drawLine(s * 0.32f, s * 0.92f, s * 0.32f, s * 0.12f, stroke)
                c.drawPath(Path().apply { moveTo(s * 0.32f, s * 0.12f); lineTo(s * 0.82f, s * 0.28f); lineTo(s * 0.32f, s * 0.46f); close() }, fill)
            }
            MKind.UTURN, MKind.UTURN_LEFT, MKind.UTURN_RIGHT -> {
                val dir = if (side == 0) -1 else side
                val x0 = s / 2 - dir * s * 0.16f; val x1 = s / 2 + dir * s * 0.16f
                c.drawPath(Path().apply {
                    moveTo(x0, s * 0.92f); lineTo(x0, s * 0.42f)
                    arcTo(android.graphics.RectF(minOf(x0, x1), s * 0.26f, maxOf(x0, x1), s * 0.58f), if (dir > 0) 180f else 0f, dir * 180f)
                    lineTo(x1, s * 0.56f)
                }, stroke)
                head(x1, s * 0.56f, 180f)
            }
            MKind.ROUNDABOUT, MKind.ROUNDABOUT_LEFT -> {
                // Anillo, entrada desde abajo y salida hacia donde queda (en España, girando a la izquierda por dentro).
                val cx = s / 2; val cy = s * 0.52f; val rr = s * 0.15f
                val ring = Paint(stroke).apply { strokeWidth = w * 0.7f; alpha = 170 }
                c.drawCircle(cx, cy, rr, ring)
                c.drawLine(cx, s * 0.95f, cx, cy + rr, stroke)
                // El ángulo de BRouter, con su signo (negativo a la izquierda): una rotonda puede salir a cualquier lado.
                val out = if (a <= 180) angle else 90
                val r = Math.toRadians(out.toDouble())
                val ux = kotlin.math.sin(r).toFloat(); val uy = -kotlin.math.cos(r).toFloat()
                val sx = cx + ux * rr; val sy = cy + uy * rr
                val ex = cx + ux * s * 0.27f; val ey = cy + uy * s * 0.27f
                c.drawLine(sx, sy, ex, ey, stroke)
                head(ex, ey, out.toFloat())
            }
            else -> if (side == 0) {
                c.drawLine(s / 2, s * 0.95f, s / 2, s * 0.32f, stroke)
                head(s / 2, s * 0.32f, 0f)
            } else {
                // Tallo desde abajo (algo hacia el lado contrario, para que quepa el brazo) y brazo doblado.
                // En un giro cerrado el codo va más arriba y más al lado contrario: el brazo baja y tiene que caber.
                val sharp = kotlin.math.abs(bend) > 100
                val px = s / 2 - side * s * (if (sharp) 0.2f else 0.12f); val py = s * (if (sharp) 0.36f else 0.56f)
                val r = Math.toRadians(bend.toDouble())
                val ux = kotlin.math.sin(r).toFloat(); val uy = -kotlin.math.cos(r).toFloat()
                val arm = s * 0.26f
                val ex = px + ux * arm; val ey = py + uy * arm
                c.drawPath(Path().apply { moveTo(px, s * 0.95f); lineTo(px, py); lineTo(ex, ey) }, stroke)
                head(ex, ey, bend.toFloat())
            }
        }
        return bmp
    }

    private companion object { const val OWNER = "car" }
}

/** Los puntos, los más cerca primero (el coche limita cuántas filas se ven); tocar uno lo hace destino. */
class PointsCarScreen(car: CarContext, private val onHeadingUp: (Boolean) -> Unit, private val onPick: (es.altamirano.gps.core.Waypoint) -> Unit) : Screen(car) {
    @Suppress("DEPRECATION") // setTitle/setHeaderAction: Header pide un Android Auto más nuevo.
    override fun onGetTemplate(): Template {
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val loc = Gps.location.value?.takeIf { Gps.isFresh(it) }
        val target = Waypoints.target.value
        val list = ItemList.Builder().setNoItemsMessage("No hay puntos guardados")
        var n = 0
        // Lo que esté en marcha, lo primero: pararlo.
        Following.state.value?.let { st ->
            list.addItem(Row.Builder().setTitle(if (st.nav != null) "Parar la ruta a «${st.nav.destName}»" else "Dejar de seguir «${st.name}»")
                .setOnClickListener { Following.stop(carContext); screenManager.pop() }.build())
            n++
        }
        if (target != null) {
            list.addItem(Row.Builder().setTitle("Dejar de ir a «${target.name}»").setOnClickListener {
                Waypoints.setTarget(null); screenManager.pop()
            }.build())
            n++
        }
        // Buscar cualquier sitio (no sólo los puntos guardados) y llevar allí la ruta.
        list.addItem(Row.Builder().setTitle("Buscar un sitio…").addText("Dirección, pueblo, negocio, monte…")
            .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_search)).build())
            .setOnClickListener { screenManager.push(PlaceSearchCarScreen(carContext) { p -> onPick(p.toWaypoint()) }) }.build())
        n++
        val sorted = if (loc == null) Waypoints.list.value.sortedBy { it.name.lowercase() }
            else Waypoints.list.value.sortedBy { Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lon) }
        for (w in sorted.take((limit - n).coerceAtLeast(0))) {
            val row = Row.Builder().setTitle(w.name)
                .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_place))
                    .setTint(CarColor.createCustom((if (w.id == target?.id) MapColors.target else MapColors.waypoint).toArgb(),
                        (if (w.id == target?.id) MapColors.target else MapColors.waypoint).toArgb())).build())
                // Tocar un punto: ruta en coche hasta él (lo calcula la pantalla del mapa, que sigue viva).
                .setOnClickListener { screenManager.pop(); onPick(w) }
            if (loc != null) row.addText(Direction.describe(Geo.distanceM(loc.latitude, loc.longitude, w.lat, w.lon),
                Direction.bearingDeg(loc.latitude, loc.longitude, w.lat, w.lon)))
            list.addItem(row.build())
        }
        // Los ajustes de radares, aquí: en el mapa no caben más botones (la plantilla deja cuatro y cuatro).
        val strip = androidx.car.app.model.ActionStrip.Builder().addAction(Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_settings)).build())
            .setOnClickListener { screenManager.push(RadarSettingsCarScreen(carContext, onHeadingUp)) }.build()).build()
        return ListTemplate.Builder().setTitle("Puntos").setHeaderAction(Action.BACK).setActionStrip(strip).setSingleList(list.build()).build()
    }
}

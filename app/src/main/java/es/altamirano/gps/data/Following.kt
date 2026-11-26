package es.altamirano.gps.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import es.altamirano.gps.R
import es.altamirano.gps.core.Altitude
import es.altamirano.gps.core.Direction
import es.altamirano.gps.core.FollowEvent
import es.altamirano.gps.core.FollowFix
import es.altamirano.gps.core.FollowRoute
import es.altamirano.gps.core.FollowStatus
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.Gpx
import es.altamirano.gps.core.NextManeuver
import es.altamirano.gps.core.PlannedRoute
import es.altamirano.gps.core.RouteGuide
import es.altamirano.gps.core.TrackFollower
import es.altamirano.gps.core.TravelMode
import es.altamirano.gps.ui.MainActivity
import es.altamirano.gps.ui.travelHeading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ir a un sitio por una ruta calculada (BRouter): adónde, cómo y la siguiente maniobra. */
data class Navigation(
    val destName: String, val destLat: Double, val destLon: Double, val mode: TravelMode,
    val guide: RouteGuide, val next: NextManeuver? = null,
    /** Recalculando tras salirse de la ruta. */
    val rerouting: Boolean = false,
)

/**
 * El track que se sigue y cómo vas. [fix] es null hasta la primera posición. [file] es el GPX si se
 * sigue un track guardado; con una ruta calculada es null y va [nav].
 */
data class FollowState(
    val name: String, val file: File?, val reversed: Boolean, val route: FollowRoute,
    val fix: FollowFix? = null, val nav: Navigation? = null,
    /** Sin motor de voz en el móvil: las indicaciones sólo se ven (y vibran). */
    val noVoice: Boolean = false,
)

/**
 * Seguir un track o una ruta: guía diciendo cuánto queda, el desnivel que falta y la hora de llegada,
 * y avisa (vibra y suena) si te sales, al volver y al llegar. Con una ruta de BRouter, además, dice
 * en voz alta cada giro y, si te sales, calcula otra desde donde estés. Corre en un servicio en
 * primer plano, como la grabación, para que funcione con el móvil en el bolsillo; es independiente
 * de grabar (se pueden hacer las dos cosas a la vez).
 */
object Following {
    private val _state = MutableStateFlow<FollowState?>(null)
    val state: StateFlow<FollowState?> = _state.asStateFlow()

    /** Sube al empezar a seguir: la app pasa al mapa y encuadra el track. */
    private val _fitRequest = MutableStateFlow(0)
    val fitRequest: StateFlow<Int> = _fitRequest.asStateFlow()

    /** Seguir un track guardado. null si se pudo; si no, lo que haya que decir. */
    fun start(context: Context, file: File, reversed: Boolean): String? {
        val track = runCatching { Gpx.parse(file.readText(), file.nameWithoutExtension) }.getOrNull()
        if (track == null || track.points.size < 2) return "El track no tiene puntos suficientes para seguirlo"
        val route = FollowRoute(track.points).let { if (reversed) it.reversed() else it }
        return begin(context, FollowState(track.name, file, reversed, route))
    }

    /** Ir por una ruta calculada hasta un destino. */
    fun startRoute(context: Context, planned: PlannedRoute, destName: String, destLat: Double, destLon: Double, mode: TravelMode): String? {
        val guide = RouteGuide(planned, mode.announceM)
        return begin(context, FollowState("Ruta a «$destName»", null, false, guide.follow,
            nav = Navigation(destName, destLat, destLon, mode, guide)))
    }

    private fun begin(context: Context, st: FollowState): String? {
        _state.value = st
        _fitRequest.value++
        Tracks.close()   // lo seguido ya se pinta; abierto saldría dos veces
        runCatching {
            context.startForegroundService(Intent(context, FollowService::class.java).setAction(FollowService.START))
        }.onFailure { _state.value = null; return "No se pudo empezar" }
        return null
    }

    fun stop(context: Context) {
        _state.value = null
        context.startService(Intent(context, FollowService::class.java).setAction(FollowService.STOP))
    }

    internal fun set(st: FollowState?) { _state.value = st }

    /** Lo que se enseña en una línea (notificación, coche): según estés en el track o no. */
    fun summary(st: FollowState): String {
        val f = st.fix ?: return "Esperando al GPS…"
        if (st.nav?.rerouting == true) return "Recalculando la ruta…"
        return when (f.status) {
            FollowStatus.ARRIVED -> if (st.nav != null) "Has llegado a «${st.nav.destName}»" else "Has llegado al final del track"
            FollowStatus.APPROACHING -> "${if (st.nav != null) "La ruta empieza" else "El track queda"} a ${Geo.formatDistance(f.match.offM)} al ${Direction.cardinal(f.bearingToTrack)}"
            FollowStatus.OFF_TRACK -> "Fuera ${if (st.nav != null) "de la ruta" else "del track"}: a ${Geo.formatDistance(f.match.offM)}, vuelve hacia el ${Direction.cardinalName(f.bearingToTrack)}"
            FollowStatus.ON_TRACK -> st.nav?.next?.let { n -> "En ${Geo.formatDistance(n.distanceM)}, ${n.maneuver.phrase()} · ${remainingText(f)}" }
                ?: remainingText(f)
        }
    }

    /** "Quedan 4,2 km · ↑ 320 m ↓ 120 m · llegada 13:45". */
    fun remainingText(f: FollowFix): String = buildList {
        add("Quedan ${Geo.formatDistance(f.remainingM)}")
        val up = f.remainingAscentM; val down = f.remainingDescentM
        if (up != null && down != null && (up >= 1 || down >= 1)) add("↑ ${Altitude.format(up)} ↓ ${Altitude.format(down)}")
        f.remainingS?.let { add("llegada " + LocalTime.now().plusSeconds(it).format(DateTimeFormatter.ofPattern("HH:mm"))) }
    }.joinToString(" · ")
}

class FollowService : Service() {
    companion object {
        const val START = "start"
        const val STOP = "stop"
        private const val CHANNEL = "following"
        private const val ALERTS = "following-alerts"
        private const val NOTIFICATION_ID = 2
        private const val ALERT_ID = 3
        private const val OWNER = "following"
        /** Entre dos recálculos, como poco (BRouter tarda y gasta batería). */
        private const val REROUTE_EVERY_MS = 20_000L
    }

    private var scope: CoroutineScope? = null
    private var lastText = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focus: AudioFocusRequest? = null
    private var lastReroute = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> begin()
            STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Seguir un track", NotificationManager.IMPORTANCE_LOW))
        // Los avisos, en su propio canal con sonido y vibración: se oyen con el móvil en el bolsillo
        // y se pueden silenciar aparte en los ajustes de Android.
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Avisos al seguir un track", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
        })
        val st = Following.state.value ?: run { stopSelf(); return }
        val ok = runCatching {
            startForeground(NOTIFICATION_ID, notification(st.name, "Esperando al GPS…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.isSuccess
        if (!ok) { Following.set(null); stopSelf(); return }

        // Seguir otra cosa con una ya en marcha: se empieza de nuevo con la nueva.
        scope?.cancel()
        nm.cancel(ALERT_ID)
        Gps.acquire(OWNER)
        if (st.nav != null) startVoice()
        var follower = TrackFollower(st.route)
        var route = st.route
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            Gps.location.filterNotNull().collect { loc ->
                if (!Gps.isFresh(loc)) return@collect
                var cur = Following.state.value ?: return@collect
                // Una ruta nueva (recalculada): se sigue desde cero.
                if (cur.route !== route) { route = cur.route; follower = TrackFollower(route) }
                val event = follower.update(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    loc.elapsedRealtimeNanos / 1e9)
                val fix = follower.last ?: return@collect
                val nav = cur.nav
                var say: String? = null
                if (nav != null) {
                    if (fix.status == FollowStatus.ON_TRACK) say = nav.guide.announce(fix.match.alongM)
                    cur = cur.copy(nav = nav.copy(next = if (fix.status == FollowStatus.ARRIVED) null else nav.guide.next(fix.match.alongM)))
                    // Fuera de la ruta: otra desde aquí (no de nuevo hasta pasados unos segundos).
                    if (fix.status == FollowStatus.OFF_TRACK && !nav.rerouting &&
                        SystemClock.elapsedRealtime() - lastReroute >= REROUTE_EVERY_MS) {
                        reroute(loc.latitude to loc.longitude, loc.travelHeading(1f))
                        cur = cur.copy(nav = cur.nav?.copy(rerouting = true))
                    }
                }
                Following.set(cur.copy(fix = fix, noVoice = nav != null && !ttsReady && tts == null))
                event?.let { alert(it, Following.state.value ?: cur) }
                say?.let { speak(it) }
                val text = Following.state.value?.let { Following.summary(it) } ?: return@collect
                if (text != lastText) { lastText = text; nm.notify(NOTIFICATION_ID, notification(cur.name, text)) }
            }
        }
    }

    private fun reroute(from: Pair<Double, Double>, heading: Float?) {
        val nav = Following.state.value?.nav ?: return
        lastReroute = SystemClock.elapsedRealtime()
        speak("Recalculando")
        scope?.launch {
            val r = Routing.route(this@FollowService, from, nav.destLat to nav.destLon, nav.mode, heading)
            val cur = Following.state.value ?: return@launch
            val curNav = cur.nav ?: return@launch
            when (r) {
                is Routing.Result.Ok -> {
                    val guide = RouteGuide(r.route, nav.mode.announceM)
                    Following.set(cur.copy(route = guide.follow, fix = null, nav = curNav.copy(guide = guide, next = null, rerouting = false)))
                    speak("Ruta nueva")
                }
                // Sin ruta nueva se sigue con la de antes (y la flecha hacia ella); se reintenta más tarde.
                is Routing.Result.Failed -> Following.set(cur.copy(nav = curNav.copy(rerouting = false)))
            }
        }
    }

    // --- Voz -----------------------------------------------------------------------------------

    /**
     * El motor de voz del sistema, en español. En GrapheneOS no viene ninguno de serie (hay que
     * instalar uno, p. ej. RHVoice o eSpeak NG): sin él las indicaciones se ven y vibran, nada más.
     */
    private fun startVoice() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            val t = tts ?: return@TextToSpeech
            ttsReady = status == TextToSpeech.SUCCESS &&
                t.setLanguage(Locale.forLanguageTag("es-ES")).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
            if (!ttsReady) { t.shutdown(); tts = null; Following.state.value?.let { Following.set(it.copy(noVoice = true)) }; return@TextToSpeech }
            t.setAudioAttributes(navAudio)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { releaseFocus() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { releaseFocus() }
            })
        }
    }

    private val navAudio = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Dice [text] bajando un momento lo que suene (música, radio) y lo devuelve al acabar. */
    private fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(navAudio).build()
        focus = req
        am.requestAudioFocus(req)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    private fun releaseFocus() {
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    // --- Avisos y notificaciones -----------------------------------------------------------------

    private fun alert(e: FollowEvent, st: FollowState) {
        val nm = getSystemService(NotificationManager::class.java)
        val ofWhat = if (st.nav != null) "de la ruta" else "del track"
        when (e) {
            FollowEvent.LEFT_TRACK -> {
                // Con ruta, lo dice la voz y se recalcula: sin sonido de notificación encima.
                if (st.nav != null && ttsReady) return
                nm.notify(ALERT_ID, alertNotification("Te has salido $ofWhat", Following.summary(st)))
            }
            FollowEvent.ARRIVED -> {
                val text = if (st.nav != null) "Has llegado a «${st.nav.destName}»" else "Final de «${st.name}»"
                if (st.nav != null && ttsReady) speak(text)
                else nm.notify(ALERT_ID, alertNotification("Has llegado", text))
            }
            FollowEvent.BACK_ON_TRACK -> {
                // Volver no necesita sonar: se quita el aviso y un toque corto basta.
                nm.cancel(ALERT_ID)
                runCatching { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)) }
            }
        }
    }

    private fun finish() {
        scope?.cancel(); scope = null
        Gps.release(OWNER)
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        tts?.shutdown(); tts = null; ttsReady = false
        releaseFocus()
        Following.set(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (scope != null) finish()
        super.onDestroy()
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)

    private fun notification(name: String, text: String): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, FollowService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (name.startsWith("Ruta")) name else "Siguiendo «$name»")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }

    private fun alertNotification(title: String, text: String): Notification = Notification.Builder(this, ALERTS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setCategory(Notification.CATEGORY_NAVIGATION)
        .setAutoCancel(true)
        .setContentIntent(openApp())
        .build()
}

package es.altamirano.gps.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import es.altamirano.gps.R
import es.altamirano.gps.core.Altitude
import es.altamirano.gps.core.Direction
import es.altamirano.gps.core.FollowEvent
import es.altamirano.gps.core.FollowFix
import es.altamirano.gps.core.FollowRoute
import es.altamirano.gps.core.FollowStatus
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.Gpx
import es.altamirano.gps.core.TrackFollower
import es.altamirano.gps.ui.MainActivity
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

/** El track que se sigue y cómo vas. [fix] es null hasta la primera posición. */
data class FollowState(val name: String, val file: File, val reversed: Boolean, val route: FollowRoute, val fix: FollowFix? = null)

/**
 * Seguir un track: guía por un GPX (grabado o importado) diciendo cuánto queda, el desnivel que falta
 * y la hora de llegada, y avisa (vibra y suena) si te sales, al volver y al llegar. Corre en un
 * servicio en primer plano, como la grabación, para que avise con el móvil en el bolsillo; es
 * independiente de grabar (se pueden hacer las dos cosas a la vez).
 */
object Following {
    private val _state = MutableStateFlow<FollowState?>(null)
    val state: StateFlow<FollowState?> = _state.asStateFlow()

    /** Sube al empezar a seguir: la app pasa al mapa y encuadra el track. */
    private val _fitRequest = MutableStateFlow(0)
    val fitRequest: StateFlow<Int> = _fitRequest.asStateFlow()

    /** null si se pudo; si no, lo que haya que decir. */
    fun start(context: Context, file: File, reversed: Boolean): String? {
        val track = runCatching { Gpx.parse(file.readText(), file.nameWithoutExtension) }.getOrNull()
        if (track == null || track.points.size < 2) return "El track no tiene puntos suficientes para seguirlo"
        val route = FollowRoute(track.points).let { if (reversed) it.reversed() else it }
        _state.value = FollowState(track.name, file, reversed, route)
        _fitRequest.value++
        Tracks.close()   // el track seguido ya se pinta; abierto saldría dos veces
        runCatching {
            context.startForegroundService(Intent(context, FollowService::class.java).setAction(FollowService.START))
        }.onFailure { _state.value = null; return "No se pudo empezar a seguir el track" }
        return null
    }

    fun stop(context: Context) {
        _state.value = null
        context.startService(Intent(context, FollowService::class.java).setAction(FollowService.STOP))
    }

    internal fun update(fix: FollowFix) { _state.value = _state.value?.copy(fix = fix) }
    internal fun clear() { _state.value = null }

    /** Lo que se enseña en una línea (notificación, coche): según estés en el track o no. */
    fun summary(st: FollowState): String {
        val f = st.fix ?: return "Esperando al GPS…"
        return when (f.status) {
            FollowStatus.ARRIVED -> "Has llegado al final del track"
            FollowStatus.APPROACHING -> "El track queda a ${Geo.formatDistance(f.match.offM)} al ${Direction.cardinal(f.bearingToTrack)}"
            FollowStatus.OFF_TRACK -> "Fuera del track: a ${Geo.formatDistance(f.match.offM)}, vuelve hacia el ${Direction.cardinalName(f.bearingToTrack)}"
            FollowStatus.ON_TRACK -> remainingText(f)
        }
    }

    /** "Quedan 4,2 km · ↑ 320 m ↓ 120 m · llegada 13:45". */
    fun remainingText(f: FollowFix): String = buildList {
        add("Quedan ${Geo.formatDistance(f.remainingM)}")
        val up = f.remainingAscentM; val down = f.remainingDescentM
        if (up != null && down != null) add("↑ ${Altitude.format(up)} ↓ ${Altitude.format(down)}")
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
    }

    private var scope: CoroutineScope? = null
    private var lastText = ""

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
        if (!ok) { Following.clear(); stopSelf(); return }

        // Seguir otro track con uno ya en marcha: se empieza de nuevo con el nuevo.
        scope?.cancel()
        nm.cancel(ALERT_ID)
        Gps.acquire(OWNER)
        val follower = TrackFollower(st.route)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            Gps.location.filterNotNull().collect { loc ->
                if (!Gps.isFresh(loc)) return@collect
                val cur = Following.state.value ?: return@collect
                if (cur.route !== st.route) return@collect
                val event = follower.update(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    loc.elapsedRealtimeNanos / 1e9)
                follower.last?.let { Following.update(it) }
                event?.let { alert(it, cur) }
                val text = Following.state.value?.let { Following.summary(it) } ?: return@collect
                if (text != lastText) { lastText = text; nm.notify(NOTIFICATION_ID, notification(st.name, text)) }
            }
        }
    }

    private fun alert(e: FollowEvent, st: FollowState) {
        val nm = getSystemService(NotificationManager::class.java)
        when (e) {
            FollowEvent.LEFT_TRACK -> nm.notify(ALERT_ID, alertNotification("Te has salido del track", Following.summary(st.copy(fix = Following.state.value?.fix))))
            FollowEvent.ARRIVED -> nm.notify(ALERT_ID, alertNotification("Has llegado", "Final de «${st.name}»"))
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
        Following.clear()
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
            .setContentTitle("Siguiendo «$name»")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Dejar de seguir", stop).build())
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

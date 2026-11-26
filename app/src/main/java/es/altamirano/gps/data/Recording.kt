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
import android.os.SystemClock
import es.altamirano.gps.R
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.Track
import es.altamirano.gps.core.TrackPoint
import es.altamirano.gps.core.TrackRecorder
import es.altamirano.gps.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Lo que enseña la interfaz del track que se graba. */
data class RecordingState(
    val active: Boolean = false,
    val points: List<TrackPoint> = emptyList(),
    val lengthM: Double = 0.0,
    val startedElapsed: Long = 0L,   // SystemClock.elapsedRealtime() al empezar
    val file: File? = null,
)

/**
 * La grabación de un track. Corre en un servicio en primer plano (tipo `location`) para que siga con
 * la pantalla apagada o en otra app; el GPX se reescribe cada minuto (temporal + rename) y al parar,
 * así que si Android mata la app se pierde como mucho el último minuto.
 */
object Recording {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun start(context: Context) {
        if (_state.value.active) return
        context.startForegroundService(Intent(context, TrackService::class.java).setAction(TrackService.START))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, TrackService::class.java).setAction(TrackService.STOP))
    }

    internal fun set(s: RecordingState) { _state.value = s }
}

class TrackService : Service() {
    companion object {
        const val START = "start"
        const val STOP = "stop"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val OWNER = "recording"
    }

    private var scope: CoroutineScope? = null
    private val recorder = TrackRecorder()
    private var name = ""
    private var file: File? = null
    private var lastSave = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> begin()
            STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (scope != null) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Grabación de tracks", NotificationManager.IMPORTANCE_LOW))
        // Desde el coche, con la app sin ventana en el móvil, Android puede negarlo: mejor no grabar que caerse.
        val ok = runCatching {
            startForeground(NOTIFICATION_ID, notification("Esperando al GPS…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.isSuccess
        if (!ok) { stopSelf(); return }

        val now = LocalDateTime.now()
        name = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        file = File(Tracks.dir(), now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + ".gpx")
        recorder.reset()
        val started = SystemClock.elapsedRealtime()
        Recording.set(RecordingState(true, emptyList(), 0.0, started, file))
        lastSave = started

        Gps.acquire(OWNER)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        // Lo que ya tenía el GPS de antes de pulsar «Grabar» no vale: la hora sería vieja.
        val since = SystemClock.elapsedRealtimeNanos()
        s.launch {
            Gps.location.filterNotNull().collect { loc ->
                if (loc.elapsedRealtimeNanos < since) return@collect
                val p = TrackPoint(loc.latitude, loc.longitude,
                    loc.altitudeMsl(),
                    Instant.ofEpochMilli(loc.time).truncatedTo(ChronoUnit.SECONDS).toString())
                val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                if (recorder.offer(p, acc, loc.elapsedRealtimeNanos / 1e9)) {
                    Recording.set(Recording.state.value.copy(points = recorder.track.toList(), lengthM = recorder.lengthM))
                }
                val t = SystemClock.elapsedRealtime()
                if (t - lastSave >= 60_000) { save(); lastSave = t }
            }
        }
        s.launch { tickNotification(started) }
    }

    private suspend fun tickNotification(started: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            delay(10_000)
            val st = Recording.state.value
            val text = "${Geo.formatDistance(st.lengthM)} · ${Geo.formatDuration((SystemClock.elapsedRealtime() - started) / 1000)}" +
                if (!Gps.isFresh(Gps.location.value)) " · sin señal GPS" else ""
            nm.notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun save() {
        val f = file ?: return
        if (recorder.track.isEmpty()) return
        runCatching { Tracks.write(f, Track(name, recorder.track.toList())) }
    }

    private fun finish() {
        scope?.cancel(); scope = null
        Gps.release(OWNER)
        save()
        Tracks.changed()
        Recording.set(RecordingState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Si el sistema lo para por su cuenta, que no se pierda lo grabado.
        if (scope != null) finish()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Grabando track")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }
}


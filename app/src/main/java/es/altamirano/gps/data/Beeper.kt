package es.altamirano.gps.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * El pitido de los radares: «bip-bip», dos tonos cortos y suaves (sin aristas: suben y bajan de
 * volumen), como guía de navegación (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`): con Android Auto sale
 * por el coche y baja un momento la música en vez de cortarla. Bruno no quiere voz; esto sí.
 */
object Beeper {
    private const val RATE = 22_050
    private const val VOLUME = 0.35f

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Los dos tonos y el silencio entre ellos, ya calculados (16 bits, mono). */
    private val pcm: ShortArray by lazy {
        fun tone(freq: Double, ms: Int): ShortArray {
            val n = RATE * ms / 1000
            val fade = RATE * 15 / 1000   // 15 ms de entrada y salida: sin chasquido
            return ShortArray(n) { i ->
                val env = minOf(1.0, i / fade.toDouble(), (n - i) / fade.toDouble())
                (sin(2 * PI * freq * i / RATE) * env * Short.MAX_VALUE * 0.8).toInt().toShort()
            }
        }
        tone(880.0, 110) + ShortArray(RATE * 70 / 1000) + tone(1175.0, 130)
    }

    private val main = Handler(Looper.getMainLooper())

    fun beep(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        runCatching {
            am.requestAudioFocus(focus)
            val data = pcm
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.setVolume(VOLUME)
            track.play()
            // Al acabar (con algo de margen), soltar el pitido y devolver la música a su volumen.
            main.postDelayed({
                runCatching { track.stop(); track.release() }
                am.abandonAudioFocusRequest(focus)
            }, data.size * 1000L / RATE + 150)
        }.onFailure { am.abandonAudioFocusRequest(focus) }
    }
}

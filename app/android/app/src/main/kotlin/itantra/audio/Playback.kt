package itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/** One thing to play: synthesized speech, tagged so the UI can highlight it. */
class PlayItem(
    val id: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val alert: Boolean,
    val repeat: Int = 1,
)

/**
 * Serial speech playback. Normal messages use the voice-call stream; alerts
 * take exclusive focus, go out on the ALARM stream at max volume, are
 * prefixed with a siren and repeated until acknowledged.
 */
class Playback(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onStarted(item: PlayItem)
        fun onFinished(item: PlayItem)
        fun onAlertState(active: Boolean, id: String)
    }

    private val queue = LinkedBlockingQueue<PlayItem>()
    private val running = AtomicBoolean(false)
    private val current = AtomicReference<PlayItem?>(null)
    private val cancelCurrent = AtomicBoolean(false)
    private val acknowledged = AtomicBoolean(false)
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var thread: Thread? = null
    private var savedAlarmVolume = -1

    fun isPlaying() = current.get() != null
    fun currentId() = current.get()?.id
    fun isAlertActive() = current.get()?.alert == true

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "playback").also { it.start() }
    }

    fun stop() {
        running.set(false)
        cancelCurrent.set(true)
        queue.clear()
        thread?.interrupt()
    }

    fun enqueue(item: PlayItem) {
        if (item.alert) {
            // Alerts jump the queue and interrupt whatever normal speech is playing.
            val rest = ArrayList<PlayItem>(); queue.drainTo(rest)
            queue.add(item); queue.addAll(rest)
            if (current.get()?.alert == false) cancelCurrent.set(true)
        } else queue.add(item)
    }

    /** Skip the current item (normal speech) or silence the alert. */
    fun skip() { acknowledged.set(true); cancelCurrent.set(true) }

    fun clearQueue() { queue.clear() }

    private fun loop() {
        while (running.get()) {
            val item = try { queue.take() } catch (_: InterruptedException) { continue }
            current.set(item)
            cancelCurrent.set(false)
            acknowledged.set(false)
            listener.onStarted(item)
            try {
                if (item.alert) playAlert(item) else playNormal(item)
            } catch (_: Throwable) {
            } finally {
                current.set(null)
                listener.onFinished(item)
            }
        }
    }

    private fun playNormal(item: PlayItem) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus)
        try { writeSamples(item.samples, item.sampleRate, attrs) } finally { am.abandonAudioFocusRequest(focus) }
    }

    private fun playAlert(item: PlayItem) {
        listener.onAlertState(true, item.id)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus)
        savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        vibrate()
        try {
            val siren = siren(item.sampleRate)
            var n = 0
            while (n < item.repeat && !acknowledged.get() && running.get()) {
                cancelCurrent.set(false)
                writeSamples(siren, item.sampleRate, attrs)
                if (acknowledged.get()) break
                writeSamples(item.samples, item.sampleRate, attrs)
                n++
                Thread.sleep(400)
            }
        } finally {
            if (savedAlarmVolume >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0) }
            am.abandonAudioFocusRequest(focus)
            listener.onAlertState(false, item.id)
        }
    }

    private fun writeSamples(samples: FloatArray, sampleRate: Int, attrs: AudioAttributes) {
        val format = AudioFormat.Builder().setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, 4096 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            track.play()
            var off = 0
            val chunk = 2048
            while (off < samples.size && !cancelCurrent.get()) {
                val n = minOf(chunk, samples.size - off)
                val w = track.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
                if (w < 0) break
                off += w
            }
            if (!cancelCurrent.get()) {
                // let the tail drain
                val tailMs = (minBuf.toLong() / 4 * 1000 / sampleRate).coerceIn(50, 400)
                Thread.sleep(tailMs)
            }
        } finally {
            runCatching { track.pause(); track.flush(); track.release() }
        }
    }

    private fun siren(sampleRate: Int): FloatArray {
        val secs = 1.2
        val n = (sampleRate * secs).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val f = 700 + 500 * sin(2 * PI * 2.5 * t) // sweep 200..1200 Hz
            out[i] = (0.8 * sin(2 * PI * f * t)).toFloat()
        }
        return out
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 800), -1)) }
    }
}

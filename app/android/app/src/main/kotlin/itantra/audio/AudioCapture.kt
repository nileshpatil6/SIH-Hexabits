package itantra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * 16 kHz mono PCM16 microphone capture on its own thread. Emits float frames
 * of [frameSize] samples (512 = Silero VAD window) plus an RMS level.
 */
@SuppressLint("MissingPermission")
class AudioCapture(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512,
    private val onFrame: (FloatArray) -> Unit,
    private val onLevel: (Double) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** When true frames are dropped (half-duplex guard while TTS plays). */
    @Volatile var muted: Boolean = false

    fun isRunning() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(minBuf, frameSize * 2 * 4)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            running.set(false)
            throw IllegalStateException("AudioRecord init failed")
        }
        record = r
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        r.startRecording()
        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(frameSize)
            val f = FloatArray(frameSize)
            var levelTick = 0
            while (running.get()) {
                val n = r.read(pcm, 0, frameSize)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = pcm[i] / 32768f; f[i] = v; sum += v * v }
                for (i in n until frameSize) f[i] = 0f
                if (++levelTick % 3 == 0) onLevel(sqrt(sum / n))
                if (!muted) onFrame(f.copyOf())
            }
        }, "audio-capture").also { it.start() }
        Log.i(TAG, "capture started, buffer=$buf aec=${aec != null} ns=${ns != null}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(500)
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { aec?.release() }; runCatching { ns?.release() }
        record = null; aec = null; ns = null
    }

    companion object { private const val TAG = "AudioCapture" }
}

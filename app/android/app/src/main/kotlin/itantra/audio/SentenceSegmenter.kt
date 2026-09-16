package itantra.audio

import java.io.File
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Turns the microphone stream into complete utterances using Silero VAD.
 * A pause of [trailingSilenceSec] ends a sentence; [maxSpeechSec] hard-cuts
 * a monologue so the receiver starts hearing it early.
 */
class SentenceSegmenter(
    modelPath: String,
    private val onUtterance: (FloatArray) -> Unit,
    trailingSilenceSec: Float = 0.45f,
    minSpeechSec: Float = 0.3f,
    maxSpeechSec: Float = 12f,
    private val sampleRate: Int = 16000,
) {
    init {
        // sherpa-onnx aborts the whole process on a missing model file, so check first.
        require(File(modelPath).isFile) { "VAD model missing at $modelPath" }
    }

    private val vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = 0.5f,
                minSilenceDuration = trailingSilenceSec,
                minSpeechDuration = minSpeechSec,
                windowSize = 512,
                maxSpeechDuration = maxSpeechSec,
            ),
            sampleRate = sampleRate, numThreads = 1,
        ),
    )

    @Volatile var speaking: Boolean = false
        private set

    @Synchronized
    fun accept(frame: FloatArray) {
        vad.acceptWaveform(frame)
        speaking = vad.isSpeechDetected()
        drain()
    }

    /** Call when the PTT button is released: emits whatever is buffered. */
    @Synchronized
    fun flush() {
        vad.flush()
        drain()
        vad.reset()
        speaking = false
    }

    @Synchronized
    fun reset() { vad.clear(); vad.reset(); speaking = false }

    private fun drain() {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            if (seg.samples.size >= sampleRate * 0.25) onUtterance(seg.samples)
        }
    }

    fun release() = vad.release()
}

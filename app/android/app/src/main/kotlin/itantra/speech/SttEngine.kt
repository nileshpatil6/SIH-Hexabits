package itantra.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.concurrent.Executors

data class SttResult(val text: String, val decodeMs: Long, val audioMs: Long) {
    val rtf: Double get() = if (audioMs > 0) decodeMs.toDouble() / audioMs else 0.0
}

/**
 * One resident IndicConformer (NeMo CTC ONNX) at a time. All native calls run
 * on a single dedicated thread so we never decode two utterances concurrently
 * on a 4-core phone.
 */
class SttEngine(private val packs: ModelPackManager, private val numThreads: Int = 2) {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "stt").apply { priority = Thread.MAX_PRIORITY } }
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile var loadedLang: String? = null
        private set
    @Volatile var lastRtf: Double = 0.0
        private set

    fun isLoaded(lang: String) = loadedLang == lang && recognizer != null

    /** Blocking. Throws if the pack is missing. */
    fun load(lang: String) {
        if (isLoaded(lang)) return
        val m = packs.find(lang, PackKind.STT) ?: throw IllegalStateException("No STT pack installed for '$lang'")
        exec.submit {
            recognizer?.release(); recognizer = null; loadedLang = null
            val model = m.file("model")?.absolutePath ?: error("pack ${m.name} has no model")
            val tokens = m.file("tokens")?.absolutePath ?: error("pack ${m.name} has no tokens")
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = m.sampleRate, featureDim = 80),
                modelConfig = when (m.engine) {
                    "transducer" -> OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = model,
                            decoder = m.file("decoder")?.absolutePath ?: "",
                            joiner = m.file("joiner")?.absolutePath ?: "",
                        ),
                        tokens = tokens, numThreads = numThreads, modelType = "transducer",
                    )
                    else -> OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = model),
                        tokens = tokens, numThreads = numThreads, modelType = "nemo_ctc",
                    )
                },
                decodingMethod = "greedy_search",
            )
            val t0 = System.currentTimeMillis()
            recognizer = OfflineRecognizer(config = cfg)
            loadedLang = lang
            Log.i(TAG, "loaded ${m.name} in ${System.currentTimeMillis() - t0} ms")
        }.get()
    }

    fun unload() {
        exec.submit { recognizer?.release(); recognizer = null; loadedLang = null }.get()
    }

    /** Decode one utterance (16 kHz float PCM in [-1, 1]). Blocking, serialized. */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): SttResult {
        return exec.submit<SttResult> {
            val r = recognizer ?: error("STT not loaded")
            val t0 = System.currentTimeMillis()
            val stream = r.createStream()
            val text = try {
                stream.acceptWaveform(samples, sampleRate)
                r.decode(stream)
                r.getResult(stream).text
            } finally {
                stream.release()
            }
            val decodeMs = System.currentTimeMillis() - t0
            val audioMs = samples.size * 1000L / sampleRate
            val res = SttResult(TextCleaner.clean(text), decodeMs, audioMs)
            lastRtf = res.rtf
            res
        }.get()
    }

    companion object { private const val TAG = "SttEngine" }
}

/** CTC greedy output is already collapsed; we trim, squash spaces and drop obvious stutters. */
object TextCleaner {
    fun clean(raw: String): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        // "à¤œà¤²à¥à¤¦à¥€ à¤œà¤²à¥à¤¦à¥€ à¤œà¤²à¥à¤¦à¥€" -> keep two (people do repeat for emphasis, three is noise)
        t = t.replace(Regex("(\\b\\S+\\b)( \\1\\b){2,}"), "$1 $1")
        return t
    }
}

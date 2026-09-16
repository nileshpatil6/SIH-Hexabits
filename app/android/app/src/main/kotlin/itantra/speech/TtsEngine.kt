package itantra.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors

data class TtsAudio(val samples: FloatArray, val sampleRate: Int, val synthMs: Long) {
    val audioMs: Long get() = samples.size * 1000L / sampleRate
    val rtf: Double get() = if (audioMs > 0) synthMs.toDouble() / audioMs else 0.0
}

/**
 * VITS voices via sherpa-onnx. Keeps up to [maxResident] models loaded (the
 * rasa13 multi-language pack plus one MMS pack covers most conversations).
 */
class TtsEngine(private val packs: ModelPackManager, private val maxResident: Int = 2, private val numThreads: Int = 2) {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "tts") }

    private class Loaded(val manifest: PackManifest, val tts: OfflineTts, var lastUsed: Long)

    private val resident = LinkedHashMap<String, Loaded>() // pack name -> model
    @Volatile var lastRtf: Double = 0.0
        private set

    fun loadedNames(): List<String> = synchronized(resident) { resident.keys.toList() }

    fun hasVoice(lang: String) = packs.find(lang, PackKind.TTS) != null

    /** Ensures a voice for [lang] is resident. Blocking. */
    fun load(lang: String): PackManifest {
        val m = packs.find(lang, PackKind.TTS) ?: throw IllegalStateException("No TTS pack installed for '$lang'")
        exec.submit { ensure(m) }.get()
        return m
    }

    private fun ensure(m: PackManifest): Loaded {
        synchronized(resident) {
            resident[m.name]?.let { it.lastUsed = System.currentTimeMillis(); return it }
            while (resident.size >= maxResident) {
                val victim = resident.minByOrNull { it.value.lastUsed }!!
                victim.value.tts.release()
                resident.remove(victim.key)
            }
        }
        val model = m.file("model")?.absolutePath ?: error("pack ${m.name} has no model")
        val tokens = m.file("tokens")?.absolutePath ?: error("pack ${m.name} has no tokens")
            require(java.io.File(model).isFile && java.io.File(tokens).isFile) { "pack ${m.name} is incomplete, re-download it" }
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model,
                    tokens = tokens,
                    lexicon = m.file("lexicon")?.absolutePath ?: "",
                    dataDir = m.file("dataDir")?.absolutePath ?: "",
                    noiseScale = 0.667f, noiseScaleW = 0.8f, lengthScale = 1.0f,
                ),
                numThreads = numThreads,
            ),
            maxNumSentences = 1,
        )
        val t0 = System.currentTimeMillis()
        val tts = OfflineTts(config = cfg)
        Log.i(TAG, "loaded ${m.name} in ${System.currentTimeMillis() - t0} ms, ${tts.numSpeakers()} speakers")
        val l = Loaded(m, tts, System.currentTimeMillis())
        synchronized(resident) { resident[m.name] = l }
        return l
    }

    /** Synthesize [text] in [lang]. Blocking, serialized. */
    fun synthesize(text: String, lang: String, speed: Float = 1.0f): TtsAudio {
        val m = packs.find(lang, PackKind.TTS) ?: throw IllegalStateException("No TTS pack installed for '$lang'")
        return exec.submit<TtsAudio> {
            val l = ensure(m)
            val t0 = System.currentTimeMillis()
            val sid = m.speakerFor(lang).coerceIn(0, maxOf(0, l.tts.numSpeakers() - 1))
            val g = l.tts.generate(text, sid = sid, speed = speed)
            val a = TtsAudio(g.samples, g.sampleRate, System.currentTimeMillis() - t0)
            lastRtf = a.rtf
            a
        }.get()
    }

    fun unloadAll() {
        exec.submit { synchronized(resident) { resident.values.forEach { it.tts.release() }; resident.clear() } }.get()
    }

    companion object { private const val TAG = "TtsEngine" }
}

package itantra.core

import android.content.Context
import android.os.Debug
import android.util.Log
import itantra.audio.AudioCapture
import itantra.audio.PlayItem
import itantra.audio.Playback
import itantra.audio.SentenceSegmenter
import itantra.mesh.AckPayload
import itantra.mesh.BleMeshTransport
import itantra.mesh.Identity
import itantra.mesh.Link
import itantra.mesh.MeshRouter
import itantra.mesh.Packet
import itantra.mesh.Peer
import itantra.mesh.TextPayload
import itantra.mesh.WifiDirectTransport
import itantra.speech.ModelPackManager
import itantra.speech.SttEngine
import itantra.speech.TtsEngine
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class TalkMode { PUSH_TO_TALK, CONTINUOUS }

/** Everything the UI needs to know about one received sentence. */
data class Inbound(
    val id: String,
    val senderId: String,
    val senderNick: String,
    val lang: String,
    val text: String,
    val alert: Boolean,
    val sentAtMs: Long,
    val receivedAtMs: Long,
    val hops: Int,
    val link: Link,
    val bytesOnWire: Int,
)

data class Transcript(val text: String, val isFinal: Boolean, val lang: String, val utteranceMs: Int, val decodeMs: Int)

/**
 * Process-wide singleton. Owns the router, the engines and the audio path.
 * The Flutter bridge and the foreground service are thin shells around it.
 */
class ItantraCore private constructor(private val app: Context) : MeshRouter.Handler, Playback.Listener {

    interface Events {
        fun onTranscript(t: Transcript)
        fun onMessage(m: Inbound)
        fun onPeersChanged(peers: List<Peer>)
        fun onSpeakingChanged(speaking: Boolean, messageId: String)
        fun onAlertState(active: Boolean, messageId: String)
        fun onCaptureLevel(rms: Double)
        fun onError(code: String, message: String)
    }

    @Volatile var events: Events? = null

    val identity: Identity = Identity.loadOrCreate(app)
    val packs = ModelPackManager(app)
    val stt = SttEngine(packs)
    val tts = TtsEngine(packs)
    private val prefs = app.getSharedPreferences("itantra", Context.MODE_PRIVATE)

    var nickname: String = prefs.getString("nick", null) ?: "Phone-${identity.peerIdHex.take(4)}"
        set(v) { field = v; prefs.edit().putString("nick", v).apply(); router.nickname = v }
    var lang: String = prefs.getString("lang", null) ?: "hi"
        set(v) { field = v; prefs.edit().putString("lang", v).apply(); router.lang = v }
    @Volatile var talkMode: TalkMode = TalkMode.PUSH_TO_TALK

    val router: MeshRouter = MeshRouter(identity, this).also { r ->
        r.nickname = nickname
        r.lang = lang
        // Wi-Fi Direct first so the router prefers the fast link when both exist.
        r.attach(WifiDirectTransport(app, r))
        r.attach(BleMeshTransport(app, r))
    }

    private val playback = Playback(app, this).also { it.start() }
    private val work = Executors.newSingleThreadExecutor { Thread(it, "core-work") }
    private var capture: AudioCapture? = null
    private var segmenter: SentenceSegmenter? = null
    private val pendingUtterances = AtomicInteger()
    @Volatile var lastE2eLatencyMs: Long = 0
        private set

    init { packs.installBundledAssets() }

    // ---- mesh --------------------------------------------------------------

    fun startMesh() = router.start()
    fun stopMesh() = router.stop()
    fun meshRunning() = router.running

    // ---- capture / STT -----------------------------------------------------

    fun ensureStt(lang: String) = stt.load(lang)
    fun ensureTts(lang: String) = tts.load(lang)

    @Synchronized
    fun startCapture() {
        if (capture?.isRunning() == true) return
        if (!stt.isLoaded(lang)) {
            runCatching { stt.load(lang) }.onFailure { events?.onError("stt_missing", it.message ?: "STT pack missing"); return }
        }
        val seg = segmenter ?: SentenceSegmenter(app.assets, onUtterance = ::onUtterance).also { segmenter = it }
        seg.reset()
        val cap = AudioCapture(onFrame = { f -> seg.accept(f) }, onLevel = { events?.onCaptureLevel(it) })
        runCatching { cap.start() }.onFailure { events?.onError("mic", it.message ?: "mic failed"); return }
        cap.muted = playback.isPlaying() && talkMode == TalkMode.CONTINUOUS
        capture = cap
    }

    @Synchronized
    fun stopCapture() {
        val cap = capture ?: return
        cap.stop()
        capture = null
        segmenter?.flush()
        events?.onCaptureLevel(0.0)
    }

    fun isCapturing() = capture?.isRunning() == true

    private fun onUtterance(samples: FloatArray) {
        pendingUtterances.incrementAndGet()
        work.execute {
            try {
                val res = stt.transcribe(samples)
                val text = res.text
                if (text.isBlank()) return@execute
                val e = Transcript(text, true, lang, res.audioMs.toInt(), res.decodeMs.toInt())
                events?.onTranscript(e)
                router.sendText(text, res.audioMs.toInt(), alert = false)
            } catch (t: Throwable) {
                Log.e(TAG, "transcribe failed", t)
                events?.onError("stt", t.message ?: "transcribe failed")
            } finally {
                pendingUtterances.decrementAndGet()
            }
        }
    }

    fun sendTyped(text: String, alert: Boolean) {
        work.execute {
            val p = router.sendText(text, 0, alert)
            events?.onTranscript(Transcript(text, true, lang, 0, 0))
            if (alert) {
                // Speak our own alert locally too so the sender knows it went out.
                speak(text, lang, alert = true, id = p.senderId.toHex() + p.timestampMs)
            }
        }
    }

    // ---- TTS / playback ----------------------------------------------------

    fun speak(text: String, lang: String, alert: Boolean, id: String = UUID.randomUUID().toString()) {
        work.execute {
            try {
                val voiceLang = if (tts.hasVoice(lang)) lang else this.lang.takeIf { tts.hasVoice(it) } ?: run {
                    events?.onError("tts_missing", "No voice installed for $lang"); return@execute
                }
                val a = tts.synthesize(text, voiceLang)
                playback.enqueue(PlayItem(id, a.samples, a.sampleRate, alert, repeat = if (alert) 3 else 1))
            } catch (t: Throwable) {
                Log.e(TAG, "tts failed", t)
                events?.onError("tts", t.message ?: "tts failed")
            }
        }
    }

    fun stopSpeaking() { playback.clearQueue(); playback.skip() }
    fun acknowledgeAlert() = playback.skip()
    fun isSpeaking() = playback.isPlaying()

    override fun onStarted(item: PlayItem) {
        // Half-duplex guard: in continuous mode do not transcribe our own speaker output.
        if (talkMode == TalkMode.CONTINUOUS) capture?.muted = true
        events?.onSpeakingChanged(true, item.id)
    }

    override fun onFinished(item: PlayItem) {
        capture?.muted = false
        events?.onSpeakingChanged(false, item.id)
    }

    override fun onAlertState(active: Boolean, id: String) { events?.onAlertState(active, id) }

    // ---- router callbacks --------------------------------------------------

    override fun onText(from: Peer, payload: TextPayload, packet: Packet, isAlert: Boolean, link: Link, bytesOnWire: Int) {
        val now = System.currentTimeMillis()
        lastE2eLatencyMs = (now - packet.timestampMs).coerceAtLeast(0)
        val id = packet.senderId.toHex() + packet.timestampMs
        val m = Inbound(id, from.peerId, from.nickname, payload.lang, payload.text, isAlert, packet.timestampMs, now, from.hops, link, bytesOnWire)
        events?.onMessage(m)
        speak(payload.text, payload.lang, alert = isAlert, id = id)
    }

    override fun onPeersChanged(peers: List<Peer>) { events?.onPeersChanged(peers) }
    override fun onAck(from: Peer, ack: AckPayload) { Log.d(TAG, "ack from ${from.nickname}") }

    fun nativeHeapMb(): Double = Debug.getNativeHeapAllocatedSize() / 1048576.0

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "ItantraCore"
        @Volatile private var instance: ItantraCore? = null
        fun get(context: Context): ItantraCore = instance ?: synchronized(this) {
            instance ?: ItantraCore(context.applicationContext).also { instance = it }
        }
    }
}

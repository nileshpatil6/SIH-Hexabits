package itantra.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import itantra.core.Inbound
import itantra.core.ItantraCore
import itantra.core.Transcript
import itantra.mesh.Link
import itantra.mesh.Peer
import itantra.service.MeshForegroundService
import itantra.speech.PackKind as CorePackKind
import itantra.core.TalkMode as CoreTalkMode
import java.util.concurrent.Executors

private fun Link.toKind() = when (this) {
    Link.BLE -> LinkKind.BLE
    Link.WIFI_DIRECT -> LinkKind.WIFI_DIRECT
    Link.LOCAL -> LinkKind.LOCAL
}

private fun PackKind.toCore() = when (this) {
    PackKind.STT -> CorePackKind.STT
    PackKind.TTS -> CorePackKind.TTS
    PackKind.VAD -> CorePackKind.VAD
}

private fun CorePackKind.toBridge() = when (this) {
    CorePackKind.STT -> PackKind.STT
    CorePackKind.TTS -> PackKind.TTS
    CorePackKind.VAD -> PackKind.VAD
}

private fun Peer.toInfo() = PeerInfo(peerId, nickname, lang, rssi.toLong(), hops.toLong(), link.toKind(), lastSeenMs)

/** Forwards core events to Dart on the main thread. */
class EventsForwarder(private val events: EventsApi) : ItantraCore.Events {
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) { main.post { runCatching(block) } }

    override fun onTranscript(t: Transcript) = ui {
        events.onTranscript(TranscriptEvent(t.text, t.isFinal, t.lang, t.utteranceMs.toLong(), t.decodeMs.toLong())) {}
    }

    override fun onMessage(m: Inbound) = ui {
        events.onMessage(
            IncomingMessage(
                m.id, m.senderId, m.senderNick, m.lang, m.text,
                if (m.alert) Priority.ALERT else Priority.NORMAL,
                m.sentAtMs, m.receivedAtMs, m.hops.toLong(), m.link.toKind(), m.bytesOnWire.toLong(),
            )
        ) {}
    }

    override fun onPeersChanged(peers: List<Peer>) = ui { events.onPeersChanged(peers.map { it.toInfo() }) {} }
    override fun onSpeakingChanged(speaking: Boolean, messageId: String) = ui { events.onSpeakingChanged(speaking, messageId) {} }
    override fun onAlertState(active: Boolean, messageId: String) = ui { events.onAlertState(active, messageId) {} }
    override fun onCaptureLevel(rms: Double) = ui { events.onCaptureLevel(rms) {} }
    override fun onError(code: String, message: String) = ui { events.onError(code, message) {} }
}

class SpeechApiImpl(private val core: ItantraCore) : SpeechApi {
    private val bg = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    private fun <T> async(callback: (Result<T>) -> Unit, block: () -> T) {
        bg.execute {
            val r = runCatching(block)
            main.post { callback(r) }
        }
    }

    override fun loadStt(lang: String, callback: (Result<Unit>) -> Unit) = async(callback) { core.ensureStt(lang) }
    override fun loadTts(lang: String, callback: (Result<Unit>) -> Unit) = async(callback) { core.ensureTts(lang); Unit }
    override fun setTalkMode(mode: TalkMode) {
        core.talkMode = if (mode == TalkMode.CONTINUOUS) CoreTalkMode.CONTINUOUS else CoreTalkMode.PUSH_TO_TALK
    }
    override fun startCapture() { bg.execute { core.startCapture() } }
    override fun stopCapture() { bg.execute { core.stopCapture() } }
    override fun speak(text: String, lang: String, priority: Priority, callback: (Result<Unit>) -> Unit) =
        async(callback) { core.speak(text, lang, alert = priority == Priority.ALERT) }
    override fun stopSpeaking() = core.stopSpeaking()
    override fun sendText(text: String, priority: Priority) = core.sendTyped(text, alert = priority == Priority.ALERT)
    override fun isSpeaking(): Boolean = core.isSpeaking()
}

class MeshApiImpl(private val context: Context, private val core: ItantraCore) : MeshApi {
    override fun getIdentity() = Identity(core.identity.peerIdHex, core.nickname, core.lang)
    override fun setNickname(nickname: String) { core.nickname = nickname }
    override fun setLanguage(lang: String) { core.lang = lang }
    override fun start() = MeshForegroundService.start(context)
    override fun stop() = MeshForegroundService.stop(context)
    override fun isRunning(): Boolean = core.meshRunning()
    override fun peers(): List<PeerInfo> = core.router.peers().map { it.toInfo() }
    override fun acknowledgeAlert() = core.acknowledgeAlert()

    override fun diagnostics(): Diagnostics {
        val s = core.router.stats
        return Diagnostics(
            sttLoaded = core.stt.loadedLang ?: "",
            ttsLoaded = core.tts.loadedNames().joinToString(","),
            sttRtf = core.stt.lastRtf,
            ttsRtf = core.tts.lastRtf,
            nativeHeapMb = core.nativeHeapMb(),
            packetsSent = s.sent.get().toLong(),
            packetsReceived = s.received.get().toLong(),
            packetsRelayed = s.relayed.get().toLong(),
            bytesSent = s.bytesSent.get(),
            bytesReceived = s.bytesReceived.get(),
            blePeers = core.router.neighbourCount(Link.BLE).toLong(),
            wifiPeers = core.router.neighbourCount(Link.WIFI_DIRECT).toLong(),
            lastE2eLatencyMs = core.lastE2eLatencyMs,
        )
    }
}

class ModelApiImpl(private val core: ItantraCore, private val events: EventsApi) : ModelApi {
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun listPacks(): List<ModelPackInfo> = core.packs.installed().flatMap { m ->
        m.langs.map { lang ->
            ModelPackInfo(lang, m.kind.toBridge(), m.engine, true, m.sizeBytes(), m.version, m.dir.absolutePath)
        }
    }

    override fun importPack(uriOrPath: String, callback: (Result<ModelPackInfo>) -> Unit) {
        bg.execute {
            val r = runCatching {
                val m = core.packs.import(uriOrPath) { p ->
                    main.post { events.onPackProgress("", PackKind.STT, p) {} }
                }
                ModelPackInfo(m.langs.joinToString(","), m.kind.toBridge(), m.engine, true, m.sizeBytes(), m.version, m.dir.absolutePath)
            }
            main.post { callback(r) }
        }
    }

    override fun deletePack(lang: String, kind: PackKind) = core.packs.delete(lang, kind.toCore())
    override fun packsDir(): String = core.packs.root.absolutePath
}

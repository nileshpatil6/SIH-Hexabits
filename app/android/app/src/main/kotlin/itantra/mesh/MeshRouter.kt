package itantra.mesh

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A peer we know about, possibly several hops away. */
data class Peer(
    val peerId: String,
    var nickname: String,
    var lang: String,
    var dhPublic: ByteArray,
    var signPublic: ByteArray,
    var hops: Int,
    var rssi: Int,
    var link: Link,
    var lastSeenMs: Long,
)

data class MeshStats(
    val sent: AtomicInteger = AtomicInteger(),
    val received: AtomicInteger = AtomicInteger(),
    val relayed: AtomicInteger = AtomicInteger(),
    val bytesSent: AtomicLong = AtomicLong(),
    val bytesReceived: AtomicLong = AtomicLong(),
)

/**
 * Flood router with TTL, dedup, fragmentation, signature checks and an outbox.
 * Owns all transports. Everything above this layer deals in decoded payloads.
 */
class MeshRouter(
    private val identity: Identity,
    private val handler: Handler,
) : Transport.Listener {

    private val transports = java.util.concurrent.CopyOnWriteArrayList<Transport>()

    /** Attach a radio before [start]. Transports call back into this router. */
    fun attach(t: Transport) { transports.add(t) }

    interface Handler {
        fun onText(from: Peer, payload: TextPayload, packet: Packet, isAlert: Boolean, link: Link, bytesOnWire: Int)
        fun onPeersChanged(peers: List<Peer>)
        fun onAck(from: Peer, ack: AckPayload)
    }

    var nickname: String = "peer"
    var lang: String = "hi"

    private val dedup = DedupCache()
    private val fragmenter = Fragmenter()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val seq = AtomicInteger()
    val stats = MeshStats()

    /** Outbox: packets we sent while no neighbour was up. Retried on neighbour change. */
    private val outbox = ArrayDeque<Pair<Packet, Long>>()
    private val outboxTtlMs = 24 * 3600_000L
    private val outboxMax = 100

    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        running = true
        transports.forEach { it.start() }
        exec.scheduleWithFixedDelay({ runCatching { announce() } }, 1, 15, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ runCatching { expirePeers() } }, 30, 30, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        transports.forEach { runCatching { it.stop() } }
    }

    fun peers(): List<Peer> = peers.values.sortedBy { it.hops }
    fun linkCount(): Int = transports.sumOf { it.neighbours().size }
    fun neighbourCount(link: Link) = transports.filter { it.link == link }.sumOf { it.neighbours().size }

    // ---- sending -------------------------------------------------------

    fun sendText(text: String, utteranceMs: Int, alert: Boolean): Packet {
        val payload = TextPayload(lang, seq.incrementAndGet(), utteranceMs, text).encode()
        val p = identity.signPacket(
            Packet(
                type = if (alert) PacketType.ALERT else PacketType.TEXT,
                ttl = Packet.MAX_TTL,
                timestampMs = System.currentTimeMillis(),
                senderId = identity.peerId,
                recipientId = null,
                payload = payload,
            )
        )
        dedup.checkAndAdd(p) // never relay our own packet back to ourselves
        transmit(p, exclude = null, store = true)
        return p
    }

    fun sendDm(peerId: String, text: String, utteranceMs: Int): Packet? {
        val peer = peers[peerId] ?: return null
        val key = identity.sessionKey(peer.dhPublic)
        val plain = TextPayload(lang, seq.incrementAndGet(), utteranceMs, text).encode()
        val p = identity.signPacket(
            Packet(
                type = PacketType.DM,
                ttl = Packet.MAX_TTL,
                timestampMs = System.currentTimeMillis(),
                senderId = identity.peerId,
                recipientId = peerId.hexToBytes(),
                payload = Aead.seal(key, plain, aad = identity.peerId + peerId.hexToBytes()),
            )
        )
        dedup.checkAndAdd(p)
        transmit(p, exclude = null, store = true)
        return p
    }

    fun announce() {
        val payload = AnnouncePayload(nickname, lang, identity.dhPublic, identity.signPublic).encode()
        val p = identity.signPacket(
            Packet(PacketType.ANNOUNCE, Packet.MAX_TTL, System.currentTimeMillis(), identity.peerId, null, payload)
        )
        dedup.checkAndAdd(p)
        transmit(p, exclude = null, store = false)
    }

    private fun sendAck(to: Packet) {
        val ack = Packet(
            PacketType.ACK, Packet.MAX_TTL, System.currentTimeMillis(), identity.peerId, to.senderId,
            AckPayload(to.senderId, to.timestampMs).encode(),
        )
        dedup.checkAndAdd(ack)
        transmit(ack, exclude = null, store = false)
    }

    private fun transmit(p: Packet, exclude: String?, store: Boolean) {
        val anyNeighbour = transports.any { it.neighbours().isNotEmpty() }
        if (!anyNeighbour && store) {
            synchronized(outbox) {
                outbox.addLast(p to System.currentTimeMillis())
                while (outbox.size > outboxMax) outbox.removeFirst()
            }
            return
        }
        for (t in transports) {
            if (t.neighbours().isEmpty()) continue
            val pieces = if (PacketCodec.encode(p).size > t.mtuPayload()) fragmenter.split(p) else listOf(p)
            for (piece in pieces) {
                val bytes = PacketCodec.encode(piece)
                t.broadcast(bytes, exclude)
                stats.sent.incrementAndGet()
                stats.bytesSent.addAndGet(bytes.size.toLong())
            }
        }
    }

    private fun flushOutbox() {
        val now = System.currentTimeMillis()
        val pending = synchronized(outbox) {
            val list = outbox.filter { now - it.second < outboxTtlMs }.map { it.first }
            outbox.clear()
            list
        }
        pending.forEach { transmit(it, null, store = true) }
    }

    // ---- receiving -----------------------------------------------------

    override fun onReceived(transport: Transport, r: Received) {
        exec.execute { runCatching { handleBytes(transport, r) }.onFailure { Log.w(TAG, "rx failed", it) } }
    }

    private fun handleBytes(transport: Transport, r: Received) {
        stats.received.incrementAndGet()
        stats.bytesReceived.addAndGet(r.bytes.size.toLong())
        var p = PacketCodec.decode(r.bytes) ?: return
        val wire = r.bytes.size

        if (p.type == PacketType.FRAGMENT) {
            // Relay fragments as-is so intermediate nodes need not reassemble.
            if (!dedup.checkAndAdd(p)) relay(p, transport, r.from.linkId)
            p = fragmenter.accept(p) ?: return
        }
        if (dedup.checkAndAdd(p)) return
        if (p.senderId.contentEquals(identity.peerId)) return

        when (p.type) {
            PacketType.ANNOUNCE -> {
                val a = AnnouncePayload.decode(p.payload) ?: return
                val sig = p.signature ?: return
                if (!Identity.verify(a.signPublicKey, p.signableBytes(), sig)) return
                if (!Identity.peerIdFor(a.signPublicKey).contentEquals(p.senderId)) return
                val hops = Packet.MAX_TTL - p.ttl + 1
                val id = p.senderId.toHex()
                val existing = peers[id]
                if (existing == null || hops <= existing.hops) {
                    peers[id] = Peer(id, a.nickname, a.lang, a.dhPublicKey, a.signPublicKey, hops, r.from.rssi, transport.link, System.currentTimeMillis())
                } else {
                    existing.lastSeenMs = System.currentTimeMillis()
                }
                handler.onPeersChanged(peers())
                relay(p, transport, r.from.linkId)
            }
            PacketType.TEXT, PacketType.ALERT -> {
                val peer = peers[p.senderId.toHex()]
                val verified = peer != null && p.signature != null && Identity.verify(peer.signPublic, p.signableBytes(), p.signature)
                // Unknown sender: still relay (someone else may know them) and deliver
                // unverified so a first alert is never dropped; UI marks it unverified.
                val text = TextPayload.decode(p.payload) ?: return
                val from = peer ?: Peer(p.senderId.toHex(), "?" , text.lang, ByteArray(32), ByteArray(32), Packet.MAX_TTL - p.ttl + 1, r.from.rssi, transport.link, System.currentTimeMillis())
                if (verified || peer == null) handler.onText(from, text, p, p.type == PacketType.ALERT, transport.link, wire)
                if (p.type == PacketType.ALERT) sendAck(p)
                relay(p, transport, r.from.linkId)
            }
            PacketType.DM -> {
                val rc = p.recipientId ?: return
                if (!rc.contentEquals(identity.peerId)) { relay(p, transport, r.from.linkId); return }
                val peer = peers[p.senderId.toHex()] ?: return
                if (p.signature == null || !Identity.verify(peer.signPublic, p.signableBytes(), p.signature)) return
                val key = identity.sessionKey(peer.dhPublic)
                val plain = Aead.open(key, p.payload, aad = p.senderId + rc) ?: return
                val text = TextPayload.decode(plain) ?: return
                handler.onText(peer, text, p, isAlert = false, link = transport.link, bytesOnWire = wire)
                sendAck(p)
            }
            PacketType.ACK -> {
                val rc = p.recipientId
                if (rc != null && !rc.contentEquals(identity.peerId)) { relay(p, transport, r.from.linkId); return }
                val ack = AckPayload.decode(p.payload) ?: return
                peers[p.senderId.toHex()]?.let { handler.onAck(it, ack) }
            }
            else -> relay(p, transport, r.from.linkId)
        }
    }

    private fun relay(p: Packet, from: Transport, fromLinkId: String) {
        val ttl = TtlPolicy.clampForRelay(p.ttl, linkCount(), p.type == PacketType.ALERT)
        if (ttl <= 0) return
        val next = p.withTtl(ttl)
        exec.schedule({
            for (t in transports) {
                if (t.neighbours().isEmpty()) continue
                val bytes = PacketCodec.encode(next)
                // Do not echo back on the link we got it from; other links get everything.
                t.broadcast(bytes, exclude = if (t === from) fromLinkId else null)
                stats.relayed.incrementAndGet()
                stats.bytesSent.addAndGet(bytes.size.toLong())
            }
        }, TtlPolicy.jitterMs(), TimeUnit.MILLISECONDS)
    }

    override fun onNeighboursChanged(transport: Transport) {
        exec.execute {
            if (transport.neighbours().isNotEmpty()) {
                announce()
                flushOutbox()
            }
            handler.onPeersChanged(peers())
        }
    }

    override fun onLog(tag: String, msg: String) { Log.d(tag, msg) }

    private fun expirePeers() {
        val now = System.currentTimeMillis()
        val removed = peers.entries.removeAll { now - it.value.lastSeenMs > 90_000 }
        if (removed) handler.onPeersChanged(peers())
    }

    companion object { private const val TAG = "MeshRouter" }
}

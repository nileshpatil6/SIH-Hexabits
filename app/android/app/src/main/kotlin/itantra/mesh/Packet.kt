package itantra.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format (big endian), inspired by the BitChat whitepaper but written from scratch.
 *
 *   version(1) type(1) ttl(1) flags(1) timestampMs(8) senderId(8)
 *   [recipientId(8) if HAS_RECIPIENT] payloadLen(2) payload[payloadLen]
 *   [signature(64) if SIGNED]
 *
 * Header is 22 bytes without recipient, 30 with. Text messages are tiny, so a
 * single BLE write (MTU 512 -> 469 B fragment budget) carries a whole sentence.
 */
object PacketType {
    const val ANNOUNCE: Byte = 1     // presence + nickname + lang + pubkeys
    const val TEXT: Byte = 2         // spoken/typed sentence, broadcast
    const val ALERT: Byte = 3        // high priority, always relayed, never dropped
    const val ACK: Byte = 4          // delivery ack for DM / alert
    const val FRAGMENT: Byte = 5     // piece of a larger packet
    const val DM: Byte = 6           // encrypted TEXT for one recipient
    const val PACK_OFFER: Byte = 7   // model pack sharing (Wi-Fi Direct only)
    const val PACK_CHUNK: Byte = 8
}

object PacketFlags {
    const val HAS_RECIPIENT = 0x01
    const val SIGNED = 0x02
    const val COMPRESSED = 0x04 // reserved
}

data class Packet(
    val type: Byte,
    val ttl: Int,
    val timestampMs: Long,
    val senderId: ByteArray,          // 8 bytes
    val recipientId: ByteArray?,      // 8 bytes or null
    val payload: ByteArray,
    val signature: ByteArray? = null, // 64 bytes or null
    val version: Int = VERSION,
) {
    val flags: Int
        get() = (if (recipientId != null) PacketFlags.HAS_RECIPIENT else 0) or
            (if (signature != null) PacketFlags.SIGNED else 0)

    fun withTtl(newTtl: Int) = copy(ttl = newTtl)

    /** Stable identity used for dedup: sender + timestamp + type + payload digest. */
    fun dedupKey(): DedupKey = DedupKey(senderId.toHex(), timestampMs, type, payload.contentHashCode())

    /** Bytes covered by the signature: everything except the signature itself. */
    fun signableBytes(): ByteArray = PacketCodec.encode(copy(signature = null))

    override fun equals(other: Any?): Boolean = other is Packet && PacketCodec.encode(this).contentEquals(PacketCodec.encode(other))
    override fun hashCode(): Int = PacketCodec.encode(this).contentHashCode()

    companion object {
        const val VERSION = 1
        const val ID_LEN = 8
        const val SIG_LEN = 64
        const val MAX_TTL = 7
    }
}

data class DedupKey(val sender: String, val ts: Long, val type: Byte, val payloadHash: Int)

object PacketCodec {
    const val MIN_HEADER = 1 + 1 + 1 + 1 + 8 + 8 + 2
    const val MAX_PAYLOAD = 0xFFFF

    fun encode(p: Packet): ByteArray {
        require(p.senderId.size == Packet.ID_LEN) { "senderId must be 8 bytes" }
        require(p.recipientId == null || p.recipientId.size == Packet.ID_LEN) { "recipientId must be 8 bytes" }
        require(p.signature == null || p.signature.size == Packet.SIG_LEN) { "signature must be 64 bytes" }
        require(p.payload.size <= MAX_PAYLOAD) { "payload too large" }
        val size = MIN_HEADER + (if (p.recipientId != null) 8 else 0) + p.payload.size + (if (p.signature != null) 64 else 0)
        val b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        b.put(p.version.toByte())
        b.put(p.type)
        b.put(p.ttl.coerceIn(0, 255).toByte())
        b.put(p.flags.toByte())
        b.putLong(p.timestampMs)
        b.put(p.senderId)
        p.recipientId?.let { b.put(it) }
        b.putShort(p.payload.size.toShort())
        b.put(p.payload)
        p.signature?.let { b.put(it) }
        return b.array()
    }

    fun decode(bytes: ByteArray): Packet? {
        if (bytes.size < MIN_HEADER) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = b.get().toInt() and 0xFF
        if (version != Packet.VERSION) return null
        val type = b.get()
        val ttl = b.get().toInt() and 0xFF
        val flags = b.get().toInt() and 0xFF
        val ts = b.long
        val sender = ByteArray(8).also { b.get(it) }
        val recipient = if (flags and PacketFlags.HAS_RECIPIENT != 0) {
            if (b.remaining() < 8) return null
            ByteArray(8).also { b.get(it) }
        } else null
        if (b.remaining() < 2) return null
        val len = b.short.toInt() and 0xFFFF
        if (b.remaining() < len) return null
        val payload = ByteArray(len).also { b.get(it) }
        val sig = if (flags and PacketFlags.SIGNED != 0) {
            if (b.remaining() < 64) return null
            ByteArray(64).also { b.get(it) }
        } else null
        return Packet(type, ttl, ts, sender, recipient, payload, sig, version)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

package itantra.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Payload bodies. Deliberately tiny hand-rolled binary encodings; a 100-char
 * Devanagari sentence is ~300 B of UTF-8 so this whole thing fits one fragment.
 */

/** ANNOUNCE payload: nick, lang, X25519 pub (32), Ed25519 pub (32). */
data class AnnouncePayload(
    val nickname: String,
    val lang: String,
    val dhPublicKey: ByteArray,
    val signPublicKey: ByteArray,
) {
    fun encode(): ByteArray {
        val nick = nickname.toByteArray(StandardCharsets.UTF_8).take(64).toByteArray()
        val lg = lang.toByteArray(StandardCharsets.US_ASCII).take(8).toByteArray()
        val b = ByteBuffer.allocate(1 + nick.size + 1 + lg.size + 32 + 32).order(ByteOrder.BIG_ENDIAN)
        b.put(nick.size.toByte()).put(nick)
        b.put(lg.size.toByte()).put(lg)
        b.put(dhPublicKey, 0, 32)
        b.put(signPublicKey, 0, 32)
        return b.array()
    }

    companion object {
        fun decode(bytes: ByteArray): AnnouncePayload? = runCatching {
            val b = ByteBuffer.wrap(bytes)
            val nl = b.get().toInt() and 0xFF
            val nick = ByteArray(nl).also { b.get(it) }
            val ll = b.get().toInt() and 0xFF
            val lg = ByteArray(ll).also { b.get(it) }
            val dh = ByteArray(32).also { b.get(it) }
            val sg = ByteArray(32).also { b.get(it) }
            AnnouncePayload(String(nick, StandardCharsets.UTF_8), String(lg, StandardCharsets.US_ASCII), dh, sg)
        }.getOrNull()
    }
}

/**
 * TEXT / ALERT / (decrypted) DM payload.
 *   lang(1 len + ascii) seq(4) utteranceMs(2) textLen(2) utf8
 */
data class TextPayload(
    val lang: String,
    val seq: Int,
    val utteranceMs: Int,
    val text: String,
) {
    fun encode(): ByteArray {
        val lg = lang.toByteArray(StandardCharsets.US_ASCII).take(8).toByteArray()
        val txt = text.toByteArray(StandardCharsets.UTF_8)
        require(txt.size <= 0xFFFF)
        val b = ByteBuffer.allocate(1 + lg.size + 4 + 2 + 2 + txt.size).order(ByteOrder.BIG_ENDIAN)
        b.put(lg.size.toByte()).put(lg)
        b.putInt(seq)
        b.putShort(utteranceMs.coerceIn(0, 0xFFFF).toShort())
        b.putShort(txt.size.toShort())
        b.put(txt)
        return b.array()
    }

    companion object {
        fun decode(bytes: ByteArray): TextPayload? = runCatching {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val ll = b.get().toInt() and 0xFF
            val lg = ByteArray(ll).also { b.get(it) }
            val seq = b.int
            val utt = b.short.toInt() and 0xFFFF
            val tl = b.short.toInt() and 0xFFFF
            val txt = ByteArray(tl).also { b.get(it) }
            TextPayload(String(lg, StandardCharsets.US_ASCII), seq, utt, String(txt, StandardCharsets.UTF_8))
        }.getOrNull()
    }
}

/** ACK payload: the (senderId, timestamp) of the packet being acknowledged. */
data class AckPayload(val senderId: ByteArray, val timestampMs: Long) {
    fun encode(): ByteArray = ByteBuffer.allocate(16).put(senderId, 0, 8).putLong(timestampMs).array()

    companion object {
        fun decode(bytes: ByteArray): AckPayload? {
            if (bytes.size < 16) return null
            val b = ByteBuffer.wrap(bytes)
            val id = ByteArray(8).also { b.get(it) }
            return AckPayload(id, b.long)
        }
    }
}

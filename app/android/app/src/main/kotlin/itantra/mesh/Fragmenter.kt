package itantra.mesh

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Splits an encoded packet that exceeds the link MTU into FRAGMENT packets and
 * reassembles them on the other side.
 *
 * Fragment payload: fragId(8) index(1) total(1) data[...]
 *
 * BLE: MTU 512 minus ATT overhead (3), link length prefix (2), our header (22)
 * and fragment header (10) leaves 475 B; we use 469 B, matching BitChat's budget.
 */
class Fragmenter(
    private val maxLinkPayload: Int = DEFAULT_FRAGMENT_DATA,
    private val maxConcurrent: Int = 128,
    private val timeoutMs: Long = 30_000,
    private val maxAssembledBytes: Int = 1 shl 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val rng = SecureRandom()

    private class Assembly(val total: Int, val firstSeenMs: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        var size = 0
    }

    private val assemblies = LinkedHashMap<String, Assembly>()

    /** Returns the original packet as-is if it fits, otherwise a list of FRAGMENT packets. */
    fun split(packet: Packet, encodedSize: Int = PacketCodec.encode(packet).size): List<Packet> {
        val whole = PacketCodec.encode(packet)
        val budget = maxLinkPayload + FRAG_HEADER + PacketCodec.MIN_HEADER
        if (whole.size <= budget) return listOf(packet)

        val fragId = ByteArray(8).also { rng.nextBytes(it) }
        val chunks = whole.toList().chunked(maxLinkPayload)
        require(chunks.size <= 255) { "packet too large to fragment" }
        return chunks.mapIndexed { i, chunk ->
            val body = ByteBuffer.allocate(FRAG_HEADER + chunk.size)
                .put(fragId).put(i.toByte()).put(chunks.size.toByte()).put(chunk.toByteArray()).array()
            Packet(
                type = PacketType.FRAGMENT,
                ttl = packet.ttl,
                timestampMs = packet.timestampMs,
                senderId = packet.senderId,
                recipientId = packet.recipientId,
                payload = body,
            )
        }
    }

    /** Feed a FRAGMENT packet. Returns the reassembled packet when complete. */
    @Synchronized
    fun accept(fragment: Packet): Packet? {
        if (fragment.type != PacketType.FRAGMENT || fragment.payload.size < FRAG_HEADER) return null
        expire()
        val b = ByteBuffer.wrap(fragment.payload)
        val id = ByteArray(8).also { b.get(it) }
        val index = b.get().toInt() and 0xFF
        val total = b.get().toInt() and 0xFF
        if (total == 0 || index >= total) return null
        val data = ByteArray(b.remaining()).also { b.get(it) }
        val key = fragment.senderId.toHex() + id.toHex()

        val asm = assemblies.getOrPut(key) {
            if (assemblies.size >= maxConcurrent) assemblies.remove(assemblies.keys.first())
            Assembly(total, clock())
        }
        if (asm.total != total) { assemblies.remove(key); return null }
        if (asm.parts[index] == null) {
            asm.parts[index] = data
            asm.received++
            asm.size += data.size
            if (asm.size > maxAssembledBytes) { assemblies.remove(key); return null }
        }
        if (asm.received < total) return null
        assemblies.remove(key)
        val whole = ByteArray(asm.size)
        var off = 0
        for (p in asm.parts) { p!!.copyInto(whole, off); off += p.size }
        return PacketCodec.decode(whole)
    }

    private fun expire() {
        val now = clock()
        val it = assemblies.entries.iterator()
        while (it.hasNext()) if (now - it.next().value.firstSeenMs > timeoutMs) it.remove()
    }

    companion object {
        const val FRAG_HEADER = 10
        const val DEFAULT_FRAGMENT_DATA = 469
    }
}

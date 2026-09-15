package itantra.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val sender = ByteArray(8) { it.toByte() }
    private val recipient = ByteArray(8) { (0x80 + it).toByte() }

    @Test fun packetRoundTripWithRecipientAndSignature() {
        val p = Packet(PacketType.DM, 5, 1_726_000_000_123, sender, recipient, "hello".toByteArray(), ByteArray(64) { 7 })
        val decoded = PacketCodec.decode(PacketCodec.encode(p))
        assertNotNull(decoded)
        assertEquals(p, decoded)
        assertArrayEquals(recipient, decoded!!.recipientId)
        assertEquals(5, decoded.ttl)
    }

    @Test fun headerSizes() {
        assertEquals(22, PacketCodec.encode(Packet(PacketType.TEXT, 7, 1, sender, null, ByteArray(0))).size)
        assertEquals(30, PacketCodec.encode(Packet(PacketType.DM, 7, 1, sender, recipient, ByteArray(0))).size)
    }

    @Test fun truncatedPacketIsRejected() {
        val bytes = PacketCodec.encode(Packet(PacketType.TEXT, 7, 1, sender, null, ByteArray(40)))
        assertNull(PacketCodec.decode(bytes.copyOf(bytes.size - 1)))
    }

    @Test fun textPayloadKeepsIndicScripts() {
        val t = TextPayload("ta", 42, 3100, "வெள்ளம் வருகிறது, உயரமான இடத்திற்கு செல்லுங்கள்")
        assertEquals(t, TextPayload.decode(t.encode()))
    }

    @Test fun fragmentationReassemblesOutOfOrder() {
        val big = ByteArray(3000) { (it % 251).toByte() }
        val p = Packet(PacketType.TEXT, 7, 99, sender, null, big)
        val f = Fragmenter()
        val parts = f.split(p)
        assertTrue(parts.size > 1)
        val rx = Fragmenter()
        var out: Packet? = null
        for (part in parts.reversed()) out = rx.accept(part) ?: out
        assertEquals(p, out)
    }

    @Test fun smallPacketIsNotFragmented() {
        val p = Packet(PacketType.TEXT, 7, 99, sender, null, ByteArray(100))
        assertEquals(listOf(p), Fragmenter().split(p))
    }

    @Test fun missingFragmentNeverCompletes() {
        val p = Packet(PacketType.TEXT, 7, 99, sender, null, ByteArray(2000))
        val parts = Fragmenter().split(p)
        val rx = Fragmenter()
        parts.drop(1).forEach { assertNull(rx.accept(it)) }
    }

    @Test fun dedupSeesDuplicatesAndExpires() {
        var now = 0L
        val d = DedupCache(ttlMs = 1000, clock = { now })
        val p = Packet(PacketType.TEXT, 7, 5, sender, null, "x".toByteArray())
        assertFalse(d.checkAndAdd(p))
        assertTrue(d.checkAndAdd(p.withTtl(6))) // relayed copy with lower TTL is still a duplicate
        now = 2000
        assertFalse(d.checkAndAdd(p))
    }

    @Test fun ttlPolicy() {
        assertEquals(0, TtlPolicy.clampForRelay(1, 3, false))
        assertEquals(5, TtlPolicy.clampForRelay(7, 8, false))
        assertEquals(6, TtlPolicy.clampForRelay(7, 1, false))
        assertEquals(6, TtlPolicy.clampForRelay(7, 8, true)) // alerts ignore density cap
    }

    @Test fun signatureAndDmEncryption() {
        val a = Identity.generate()
        val b = Identity.generate()
        val p = a.signPacket(Packet(PacketType.TEXT, 7, 1, a.peerId, null, "hi".toByteArray()))
        assertTrue(Identity.verify(a.signPublic, p.signableBytes(), p.signature!!))
        assertFalse(Identity.verify(b.signPublic, p.signableBytes(), p.signature!!))

        val kA = a.sessionKey(b.dhPublic)
        val kB = b.sessionKey(a.dhPublic)
        assertArrayEquals(kA, kB)
        val sealed = Aead.seal(kA, "मदद चाहिए".toByteArray(), aad = a.peerId)
        assertEquals("मदद चाहिए", String(Aead.open(kB, sealed, aad = a.peerId)!!))
        assertNull(Aead.open(kB, sealed, aad = b.peerId))
    }
}

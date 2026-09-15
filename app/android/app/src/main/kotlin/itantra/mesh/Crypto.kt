package itantra.mesh

import android.content.Context
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Per-install identity. peerId = first 8 bytes of SHA-256(Ed25519 pubkey).
 * Keys are stored in app-private prefs (adequate for a hackathon; Keystore later).
 */
class Identity private constructor(
    val signPrivate: Ed25519PrivateKeyParameters,
    val dhPrivate: X25519PrivateKeyParameters,
) {
    val signPublic: ByteArray = signPrivate.generatePublicKey().encoded
    val dhPublic: ByteArray = dhPrivate.generatePublicKey().encoded
    val peerId: ByteArray = peerIdFor(signPublic)
    val peerIdHex: String = peerId.toHex()

    fun sign(data: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, signPrivate)
        s.update(data, 0, data.size)
        return s.generateSignature()
    }

    fun signPacket(p: Packet): Packet = p.copy(signature = sign(p.signableBytes()))

    /** Shared secret with a peer's X25519 public key, run through HKDF. */
    fun sessionKey(peerDhPublic: ByteArray): ByteArray {
        val agree = X25519Agreement()
        agree.init(dhPrivate)
        val shared = ByteArray(agree.agreementSize)
        agree.calculateAgreement(X25519PublicKeyParameters(peerDhPublic, 0), shared, 0)
        // Symmetric key must be the same on both sides regardless of who is "first".
        val a = dhPublic; val b = peerDhPublic
        val salt = if (a.toHex() < b.toHex()) a + b else b + a
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, salt, "itantra-dm-v1".toByteArray()))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
    }

    companion object {
        private const val PREFS = "itantra_identity"
        private val rng = SecureRandom()

        fun peerIdFor(signPublic: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(signPublic).copyOf(Packet.ID_LEN)

        fun loadOrCreate(context: Context): Identity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val sig = prefs.getString("sign", null)
            val dh = prefs.getString("dh", null)
            if (sig != null && dh != null) {
                return Identity(Ed25519PrivateKeyParameters(sig.hexToBytes(), 0), X25519PrivateKeyParameters(dh.hexToBytes(), 0))
            }
            val id = generate()
            prefs.edit().putString("sign", id.signPrivate.encoded.toHex()).putString("dh", id.dhPrivate.encoded.toHex()).apply()
            return id
        }

        fun generate(): Identity = Identity(Ed25519PrivateKeyParameters(rng), X25519PrivateKeyParameters(rng))

        fun verify(signPublic: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
            val v = Ed25519Signer()
            v.init(false, Ed25519PublicKeyParameters(signPublic, 0))
            v.update(data, 0, data.size)
            v.verifySignature(signature)
        }.getOrDefault(false)
    }
}

/** ChaCha20-Poly1305 with a random 12-byte nonce prefixed to the ciphertext. */
object Aead {
    private val rng = SecureRandom()

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return nonce + out
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? = runCatching {
        if (sealed.size < 12 + 16) return null
        val nonce = sealed.copyOfRange(0, 12)
        val ct = sealed.copyOfRange(12, sealed.size)
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(ct.size))
        val n = c.processBytes(ct, 0, ct.size, out, 0)
        val total = n + c.doFinal(out, n)
        out.copyOf(total)
    }.getOrNull()
}

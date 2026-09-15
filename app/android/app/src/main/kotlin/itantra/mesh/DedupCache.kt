package itantra.mesh

/** LRU seen-set: 1000 entries, 5 minute expiry, keyed on (sender, ts, type, payload digest). */
class DedupCache(
    private val capacity: Int = 1000,
    private val ttlMs: Long = 5 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seen = object : LinkedHashMap<DedupKey, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DedupKey, Long>) = size > capacity
    }

    /** Returns true if the packet was already seen (and refreshes it). */
    @Synchronized
    fun checkAndAdd(p: Packet): Boolean {
        val now = clock()
        val key = p.dedupKey()
        val prev = seen[key]
        if (prev != null && now - prev < ttlMs) return true
        seen[key] = now
        return false
    }

    @Synchronized
    fun size() = seen.size
}

/** TTL policy: dense meshes flood less, sparse chains relay to full depth. */
object TtlPolicy {
    fun clampForRelay(incomingTtl: Int, linkCount: Int, isAlert: Boolean): Int {
        val next = incomingTtl - 1
        if (next <= 0) return 0
        if (isAlert) return next // alerts always go the full distance
        val cap = when {
            linkCount >= 6 -> 5
            linkCount <= 2 -> Packet.MAX_TTL
            else -> 6
        }
        return minOf(next, cap)
    }

    /** Relay jitter so simultaneous relays from neighbours do not collide. */
    fun jitterMs(rng: kotlin.random.Random = kotlin.random.Random.Default): Long = 10L + rng.nextLong(211)
}

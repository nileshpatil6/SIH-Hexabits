package itantra.mesh

enum class Link { BLE, WIFI_DIRECT, LOCAL }

/** A directly connected neighbour on one radio. */
data class Neighbour(
    val linkId: String,      // BLE address or socket address
    val link: Link,
    val rssi: Int = 0,
    val peerId: String? = null, // learned from ANNOUNCE
)

data class Received(val bytes: ByteArray, val from: Neighbour)

/**
 * One radio. Both BLE and Wi-Fi Direct implement this so the router does not
 * care which one carried a packet.
 */
interface Transport {
    val link: Link
    val listener: Listener
    fun start()
    fun stop()
    /** Best-effort broadcast to every connected neighbour except [exclude]. */
    fun broadcast(bytes: ByteArray, exclude: String? = null)
    fun neighbours(): List<Neighbour>
    /** Payload bytes we can push in one write on this link. */
    fun mtuPayload(): Int

    interface Listener {
        fun onReceived(transport: Transport, r: Received)
        fun onNeighboursChanged(transport: Transport)
        fun onLog(tag: String, msg: String)
    }
}

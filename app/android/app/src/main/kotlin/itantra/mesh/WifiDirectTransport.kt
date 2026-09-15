package itantra.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.HandlerThread
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Wi-Fi Direct transport. The group owner runs a TCP server; clients connect
 * to it. Frames are [len(4)] + bytes. The GO relays between clients through the
 * router's normal broadcast (exclude = origin), so this is a star per group.
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(
    private val context: Context,
    override val listener: Transport.Listener,
    private val port: Int = 47474,
) : Transport {

    override val link = Link.WIFI_DIRECT

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val thread = HandlerThread("wifi-direct").apply { start() }
    private val handler = Handler(thread.looper)
    private var channel: WifiP2pManager.Channel? = null
    private val io = Executors.newCachedThreadPool()

    private class Conn(val socket: Socket, val out: DataOutputStream) {
        val addr: String = socket.inetAddress.hostAddress ?: socket.toString()
    }

    private val conns = ConcurrentHashMap<String, Conn>()
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var isGroupOwner = false
    private val attempted = ConcurrentHashMap<String, Long>()

    override fun start() {
        val m = manager ?: run { listener.onLog(TAG, "no Wi-Fi P2P"); return }
        if (running) return
        running = true
        channel = m.initialize(context, thread.looper, null)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        })
        discover()
        handler.postDelayed(rediscover, 30_000)
    }

    private val rediscover = object : Runnable {
        override fun run() {
            if (!running) return
            if (conns.isEmpty()) discover()
            handler.postDelayed(this, 30_000)
        }
    }

    private fun discover() {
        val ch = channel ?: return
        manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) { listener.onLog(TAG, "discoverPeers failed $reason") }
        })
    }

    override fun stop() {
        running = false
        runCatching { context.unregisterReceiver(receiver) }
        handler.removeCallbacks(rediscover)
        conns.values.forEach { runCatching { it.socket.close() } }
        conns.clear()
        runCatching { server?.close() }
        server = null
        val ch = channel ?: return
        manager?.removeGroup(ch, null)
        listener.onNeighboursChanged(this)
    }

    override fun neighbours(): List<Neighbour> = conns.keys.map { Neighbour(it, Link.WIFI_DIRECT, 0) }

    override fun mtuPayload(): Int = 60_000

    override fun broadcast(bytes: ByteArray, exclude: String?) {
        for ((addr, c) in conns) {
            if (addr == exclude) continue
            io.execute {
                runCatching {
                    synchronized(c.out) {
                        c.out.writeInt(bytes.size)
                        c.out.write(bytes)
                        c.out.flush()
                    }
                }.onFailure { drop(addr) }
            }
        }
    }

    private fun drop(addr: String) {
        conns.remove(addr)?.let { runCatching { it.socket.close() } }
        listener.onNeighboursChanged(this)
    }

    // ---- P2P framework events ------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val ch = channel ?: return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager?.requestPeers(ch) { list ->
                    list.deviceList.forEach { maybeConnect(it) }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> manager?.requestConnectionInfo(ch) { onConnectionInfo(it) }
            }
        }
    }

    private fun maybeConnect(dev: WifiP2pDevice) {
        if (conns.isNotEmpty()) return // already in a group
        if (dev.status != WifiP2pDevice.AVAILABLE) return
        val now = System.currentTimeMillis()
        val last = attempted[dev.deviceAddress] ?: 0
        if (now - last < 20_000) return
        attempted[dev.deviceAddress] = now
        val cfg = WifiP2pConfig().apply {
            deviceAddress = dev.deviceAddress
            // Let the framework negotiate GO; a deterministic tiebreak keeps two
            // equally eager phones from both refusing.
            groupOwnerIntent = if (dev.deviceAddress < localAddressHint()) 3 else 12
        }
        manager?.connect(channel ?: return, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) { listener.onLog(TAG, "connect failed $reason") }
        })
    }

    private var localAddress: String = ""
    private fun localAddressHint(): String = localAddress

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) {
            conns.values.forEach { runCatching { it.socket.close() } }
            conns.clear()
            runCatching { server?.close() }; server = null
            listener.onNeighboursChanged(this)
            return
        }
        isGroupOwner = info.isGroupOwner
        if (info.isGroupOwner) startServer() else connectToOwner(info.groupOwnerAddress)
    }

    private fun startServer() {
        if (server != null) return
        io.execute {
            runCatching {
                val ss = ServerSocket(port)
                server = ss
                while (running) {
                    val s = ss.accept()
                    accept(s)
                }
            }.onFailure { listener.onLog(TAG, "server ended: $it") }
        }
    }

    private fun connectToOwner(addr: InetAddress) {
        io.execute {
            var tries = 0
            while (running && tries < 10 && conns.isEmpty()) {
                tries++
                runCatching {
                    val s = Socket(addr, port)
                    accept(s)
                }.onFailure { Thread.sleep(1500) }
            }
        }
    }

    private fun accept(s: Socket) {
        s.tcpNoDelay = true
        s.keepAlive = true
        val c = Conn(s, DataOutputStream(s.getOutputStream().buffered()))
        conns[c.addr] = c
        listener.onNeighboursChanged(this)
        io.execute {
            runCatching {
                val din = DataInputStream(s.getInputStream().buffered())
                while (running && !s.isClosed) {
                    val len = din.readInt()
                    if (len <= 0 || len > 4_000_000) break
                    val buf = ByteArray(len)
                    din.readFully(buf)
                    listener.onReceived(this, Received(buf, Neighbour(c.addr, Link.WIFI_DIRECT, 0)))
                }
            }
            drop(c.addr)
        }
    }

    companion object { private const val TAG = "WifiDirect" }
}

package itantra.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE transport. Every phone advertises + hosts a GATT server (peripheral role)
 * AND scans + connects to others (central role), like BitChat.
 *
 * Link framing: each logical packet is sent as [len(2)] + bytes, chunked into
 * (mtu - 3) byte writes. Receivers accumulate per link until len is satisfied,
 * so we work even when MTU negotiation is refused (20 B chunks, slow but alive).
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(
    private val context: Context,
    override val listener: Transport.Listener,
    private val maxCentralLinks: Int = 6,
) : Transport {

    override val link = Link.BLE

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    private val thread = HandlerThread("ble-mesh").apply { start() }
    private val handler = Handler(thread.looper)

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile private var running = false

    /** Centrals subscribed to our characteristic (we notify them). */
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    /** Peripherals we connected to as a central (we write to them). */
    private val centralLinks = ConcurrentHashMap<String, CentralLink>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val rxBuffers = ConcurrentHashMap<String, RxBuffer>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val rssis = ConcurrentHashMap<String, Int>()

    private class CentralLink(val gatt: BluetoothGatt, var ch: BluetoothGattCharacteristic?, var ready: Boolean = false) {
        val queue = ArrayDeque<ByteArray>()
        var busy = false
    }

    private class RxBuffer {
        val buf = ByteArrayOutputStream()
        var expected = -1
    }

    override fun start() {
        if (running) return
        running = true
        handler.post {
            startServer()
            startAdvertising()
            startScanning()
        }
    }

    override fun stop() {
        running = false
        handler.post {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            centralLinks.values.forEach { runCatching { it.gatt.disconnect(); it.gatt.close() } }
            centralLinks.clear()
            subscribedCentrals.clear()
            runCatching { gattServer?.close() }
            gattServer = null
            listener.onNeighboursChanged(this)
        }
    }

    override fun neighbours(): List<Neighbour> {
        val out = LinkedHashMap<String, Neighbour>()
        subscribedCentrals.keys.forEach { out[it] = Neighbour(it, Link.BLE, rssis[it] ?: 0) }
        centralLinks.filter { it.value.ready }.keys.forEach { out[it] = Neighbour(it, Link.BLE, rssis[it] ?: 0) }
        return out.values.toList()
    }

    override fun mtuPayload(): Int = Fragmenter.DEFAULT_FRAGMENT_DATA + Fragmenter.FRAG_HEADER + PacketCodec.MIN_HEADER

    override fun broadcast(bytes: ByteArray, exclude: String?) {
        handler.post {
            val framed = frame(bytes)
            for ((addr, dev) in subscribedCentrals) {
                if (addr == exclude) continue
                notifyChunks(dev, framed, addr)
            }
            for ((addr, cl) in centralLinks) {
                if (addr == exclude || !cl.ready) continue
                cl.queue.addAll(chunk(framed, mtus[addr] ?: 23))
                pumpWrites(addr, cl)
            }
        }
    }

    // ---- framing -------------------------------------------------------

    private fun frame(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size + 2)
        out[0] = (bytes.size shr 8).toByte(); out[1] = bytes.size.toByte()
        bytes.copyInto(out, 2)
        return out
    }

    private fun chunk(framed: ByteArray, mtu: Int): List<ByteArray> {
        val size = (mtu - 3).coerceAtLeast(20)
        return framed.toList().chunked(size).map { it.toByteArray() }
    }

    private fun onChunk(addr: String, chunk: ByteArray) {
        val rb = rxBuffers.getOrPut(addr) { RxBuffer() }
        rb.buf.write(chunk)
        while (true) {
            val have = rb.buf.toByteArray()
            if (rb.expected < 0) {
                if (have.size < 2) return
                rb.expected = ((have[0].toInt() and 0xFF) shl 8) or (have[1].toInt() and 0xFF)
            }
            if (have.size < 2 + rb.expected) return
            val packet = have.copyOfRange(2, 2 + rb.expected)
            val rest = have.copyOfRange(2 + rb.expected, have.size)
            rb.buf.reset(); rb.buf.write(rest); rb.expected = -1
            listener.onReceived(this, Received(packet, Neighbour(addr, Link.BLE, rssis[addr] ?: 0)))
            if (rest.isEmpty()) return
        }
    }

    // ---- peripheral role ----------------------------------------------

    private fun startServer() {
        val server = btManager.openGattServer(context, serverCallback) ?: run {
            listener.onLog(TAG, "openGattServer failed"); return
        }
        val ch = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ,
        )
        ch.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val svc = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(ch)
        server.addService(svc)
        gattServer = server
        characteristic = ch
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCentrals.remove(device.address)
                rxBuffers.remove(device.address)
                listener.onNeighboursChanged(this@BleMeshTransport)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { mtus[device.address] = mtu }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (ch.uuid == CHAR_UUID) onChunk(device.address, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (descriptor.uuid == CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedCentrals[device.address] = device
                } else {
                    subscribedCentrals.remove(device.address)
                }
                listener.onNeighboursChanged(this@BleMeshTransport)
            }
        }
    }

    private fun notifyChunks(dev: BluetoothDevice, framed: ByteArray, addr: String) {
        val server = gattServer ?: return
        val ch = characteristic ?: return
        for (c in chunk(framed, mtus[addr] ?: 23)) {
            if (Build.VERSION.SDK_INT >= 33) {
                server.notifyCharacteristicChanged(dev, ch, false, c)
            } else {
                @Suppress("DEPRECATION")
                ch.value = c
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(dev, ch, false)
            }
            // Notifications are unacknowledged; a tiny gap keeps the stack from dropping them.
            Thread.sleep(4)
        }
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: run { listener.onLog(TAG, "no advertiser"); return }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build()
        adv.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { listener.onLog(TAG, "advertise failed $errorCode") }
    }

    // ---- central role ---------------------------------------------------

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: run { listener.onLog(TAG, "no scanner"); return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        // Duty cycle: after 20 s drop to balanced to save battery, keep discovering.
        handler.postDelayed({
            if (!running) return@postDelayed
            runCatching { scanner.stopScan(scanCallback) }
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanCallback)
        }, 20_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            rssis[dev.address] = result.rssi
            if (centralLinks.containsKey(dev.address) || subscribedCentrals.containsKey(dev.address)) return
            if (centralLinks.size >= maxCentralLinks) return
            if (!connecting.add(dev.address)) return
            handler.post { connectTo(dev) }
        }

        override fun onScanFailed(errorCode: Int) { listener.onLog(TAG, "scan failed $errorCode") }
    }

    private fun connectTo(dev: BluetoothDevice) {
        val gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        centralLinks[dev.address] = CentralLink(gatt, null)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                centralLinks.remove(addr)
                connecting.remove(addr)
                rxBuffers.remove(addr)
                runCatching { gatt.close() }
                listener.onNeighboursChanged(this@BleMeshTransport)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtus[gatt.device.address] = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) ?: run { gatt.disconnect(); return }
            val cl = centralLinks[gatt.device.address] ?: return
            cl.ch = ch
            gatt.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val addr = gatt.device.address
            centralLinks[addr]?.ready = true
            connecting.remove(addr)
            listener.onNeighboursChanged(this@BleMeshTransport)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onChunk(gatt.device.address, value)
        }

        @Deprecated("pre-33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") onChunk(gatt.device.address, ch.value ?: return)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val addr = gatt.device.address
            handler.post { centralLinks[addr]?.let { it.busy = false; pumpWrites(addr, it) } }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) { rssis[gatt.device.address] = rssi }
    }

    private fun pumpWrites(addr: String, cl: CentralLink) {
        if (cl.busy) return
        val next = cl.queue.removeFirstOrNull() ?: return
        val ch = cl.ch ?: return
        cl.busy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            cl.gatt.writeCharacteristic(ch, next, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            ch.value = next
            @Suppress("DEPRECATION")
            cl.gatt.writeCharacteristic(ch)
        }
        if (!ok) { cl.busy = false; handler.postDelayed({ pumpWrites(addr, cl) }, 20) }
    }

    companion object {
        private const val TAG = "BleMesh"
        val SERVICE_UUID: UUID = UUID.fromString("1a7a0001-6e73-4b5a-9c3a-1749a1d0c0de")
        val CHAR_UUID: UUID = UUID.fromString("1a7a0002-6e73-4b5a-9c3a-1749a1d0c0de")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

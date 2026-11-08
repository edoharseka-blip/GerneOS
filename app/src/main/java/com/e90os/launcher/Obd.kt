package com.e90os.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Read-only ELM327 OBD-II reader for values the KSW MCU does not report:
 * coolant (PID 05), intake air (PID 0F) and supply voltage (ATRV).
 * Only standard mode 01 requests and AT commands are sent; nothing is written to the car.
 *
 * Target: "bt:<MAC>" (paired Bluetooth SPP adapter), "ble:<MAC>" (Bluetooth LE adapter, the
 * "iOS + Android" kind), "wifi:<host>:<port>" (Wi-Fi ELM327) or "usb" (K+DCAN cable or USB
 * ELM327 on the head unit's USB port, see [ObdUsb]).
 * Raw adapter traffic is logged under the tag E90OBD.
 */
class Obd(private val context: Context, private val onChange: () -> Unit) {

    // Resolved on use: Obd is created as an Activity field, before the Activity has a base context.
    private val app: Context get() = context.applicationContext

    companion object {
        const val TAG = "E90OBD"
        const val WIFI_DEFAULT = "wifi:192.168.0.10:35000"
        const val USB = "usb"
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val STALE_MS = 15_000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Serial-over-BLE services used by ELM327 clones (FFF0, FFE0, 18F0), preferred in this order. */
        private val BLE_SERVICES = listOf("fff0", "ffe0", "18f0")
        private const val EOF_BYTE: Byte = -1

        /** "41 05 7B" → [0x41, 0x05, 0x7B]; skips echo, "SEARCHING..." and other text lines. */
        fun pidBytes(reply: String, pid: Int): IntArray? {
            val want = "41%02X".format(pid)
            for (line in reply.split('\r', '\n')) {
                val hex = line.replace(" ", "").uppercase()
                val at = hex.indexOf(want)
                if (at < 0) continue
                val data = hex.substring(at)
                if (data.length < 6 || data.any { it !in "0123456789ABCDEF" }) continue
                return IntArray(data.length / 2) { data.substring(it * 2, it * 2 + 2).toInt(16) }
            }
            return null
        }

        /** "12.6V" → 12.6 */
        fun volts(reply: String): Float? =
            Regex("""(\d{1,2}[.,]\d)\s*V""", RegexOption.IGNORE_CASE).find(reply)
                ?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
    }

    enum class State { OFF, CONNECTING, NO_ADAPTER, NO_ECU, OK }

    @Volatile var state = State.OFF; private set
    @Volatile var detail = ""; private set
    @Volatile private var coolantV: Int? = null
    @Volatile private var intakeV: Int? = null
    @Volatile private var voltageV: Float? = null
    @Volatile private var updated = 0L

    private fun fresh() = System.currentTimeMillis() - updated < STALE_MS
    val coolant: Int? get() = coolantV?.takeIf { fresh() }
    val intake: Int? get() = intakeV?.takeIf { fresh() }
    val voltage: Float? get() = voltageV?.takeIf { fresh() }

    @Volatile private var target: String? = null
    @Volatile private var conn: Closeable? = null
    private var thread: Thread? = null

    /** Start (or restart) polling [newTarget]; null stops. */
    @Synchronized fun start(newTarget: String?) {
        if (newTarget == target && thread?.isAlive == true) return
        stop()
        target = newTarget ?: return
        thread = Thread({ loop(newTarget) }, "obd").apply { isDaemon = true; start() }
    }

    /** Reconnect now (e.g. a USB adapter was just plugged in). */
    @Synchronized fun restart() {
        val t = target ?: return
        stop()
        start(t)
    }

    @Synchronized fun stop() {
        target = null
        runCatching { conn?.close() }
        thread?.interrupt()
        thread = null
        set(State.OFF, "")
    }

    private fun set(s: State, d: String) {
        if (s == state && d == detail) return
        state = s; detail = d
        onChange()
    }

    private fun active(t: String) = target == t && !Thread.currentThread().isInterrupted

    private fun loop(t: String) {
        var wait = 3_000L
        while (active(t)) {
            try {
                set(State.CONNECTING, "")
                open(t).use { io ->
                    conn = io
                    session(t, io)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.i(TAG, "connection: ${e.message}")
                if (active(t)) set(State.NO_ADAPTER, e.message ?: "")
            }
            conn = null
            try { Thread.sleep(wait) } catch (e: InterruptedException) { break }
            wait = (wait * 2).coerceAtMost(30_000L)
        }
    }

    private class Io(val input: InputStream, val output: OutputStream, val closer: Closeable,
                     val usb: ObdUsb.Port? = null) : Closeable {
        override fun close() = closer.close()
    }

    private fun open(t: String): Io = when {
        t.startsWith("bt:") -> {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("нет Bluetooth в Android")
            if (!adapter.isEnabled) error("Bluetooth выключен")
            val dev = adapter.getRemoteDevice(t.removePrefix("bt:"))
            runCatching { adapter.cancelDiscovery() }
            val sock: BluetoothSocket = try {
                dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
            } catch (e: Exception) {
                dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
            }
            Io(sock.inputStream, sock.outputStream, sock)
        }
        t.startsWith("ble:") -> openBle(t.removePrefix("ble:"))
        t.startsWith("wifi:") -> {
            val hp = t.removePrefix("wifi:")
            val host = hp.substringBeforeLast(':')
            val port = hp.substringAfterLast(':').toIntOrNull() ?: 35000
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 5_000)
            Io(s.getInputStream(), s.getOutputStream(), s)
        }
        t == USB -> ObdUsb.open(app).let { Io(it.input, it.output, it, it) }
        else -> error("неизвестный адаптер: $t")
    }

    /** ELM327 over a BLE GATT "serial" characteristic pair (notify in, write out). */
    private fun openBle(mac: String): Io {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("нет Bluetooth в Android")
        if (!adapter.isEnabled) error("Bluetooth выключен")
        val dev = adapter.getRemoteDevice(mac)
        val rx = LinkedBlockingQueue<Byte>()
        val ready = CountDownLatch(1)
        val written = java.util.concurrent.Semaphore(0)
        var failure: String? = null
        var tx: BluetoothGattCharacteristic? = null

        fun shortId(u: UUID) = u.toString().substring(4, 8)

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else { failure = "BLE отключился ($status)"; ready.countDown(); written.release(); rx.offer(EOF_BYTE) }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val services = g.services.sortedBy { sv ->
                    BLE_SERVICES.indexOf(shortId(sv.uuid)).let { if (it < 0) 99 else it }
                }
                for (sv in services) {
                    val chars = sv.characteristics
                    val notify = chars.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
                    val write = chars.firstOrNull {
                        it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    }
                    if (notify == null || write == null) continue
                    Log.i(TAG, "BLE service ${sv.uuid}: notify ${notify.uuid}, write ${write.uuid}")
                    tx = write
                    g.setCharacteristicNotification(notify, true)
                    val d = notify.getDescriptor(CCCD)
                    if (d != null) {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (g.writeDescriptor(d)) return
                    }
                    ready.countDown()
                    return
                }
                failure = "у адаптера нет последовательного BLE-канала"
                ready.countDown()
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { ready.countDown() }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                c.value?.forEach { rx.offer(it) }
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { written.release() }
        }

        val gatt = dev.connectGatt(app, false, cb, BluetoothDevice.TRANSPORT_LE) ?: error("BLE не подключается")
        val close = Closeable { runCatching { gatt.disconnect() }; runCatching { gatt.close() }; rx.offer(EOF_BYTE) }
        if (!ready.await(15, TimeUnit.SECONDS) || failure != null) {
            close.close()
            error(failure ?: "BLE-адаптер не ответил")
        }
        val out = tx!!
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int {
                if (closed) return -1
                val b = rx.take()
                if (b == EOF_BYTE && (closed || failure != null)) return -1
                return b.toInt() and 0xFF
            }
            override fun available(): Int = rx.size
        }
        val output = object : OutputStream() {
            private val buf = java.io.ByteArrayOutputStream()
            override fun write(b: Int) { buf.write(b) }
            override fun flush() {
                val all = buf.toByteArray(); buf.reset()
                for (i in all.indices step 20) {
                    out.value = all.copyOfRange(i, minOf(i + 20, all.size))
                    if (!gatt.writeCharacteristic(out)) error("BLE: запись не прошла")
                    written.tryAcquire(1, TimeUnit.SECONDS)
                    if (failure != null) error(failure!!)
                }
            }
        }
        return Io(input, output, Closeable { closed = true; close.close() })
    }

    private fun cmd(io: Io, c: String, timeoutMs: Long = 3_000L): String {
        while (io.input.available() > 0) io.input.read()
        io.output.write((c + "\r").toByteArray())
        io.output.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (io.input.available() > 0) {
                val b = io.input.read()
                if (b < 0) error("адаптер отключился")
                if (b == '>'.code) break
                sb.append(b.toChar())
            } else {
                Thread.sleep(20)
            }
        }
        val r = sb.toString().trim()
        Log.d(TAG, "$c -> ${r.replace('\r', '|')}")
        return r
    }

    private fun session(t: String, io: Io) {
        val usb = io.usb
        var tag = ""
        if (usb != null) {
            // FTDI is most likely the K+DCAN cable: try BMW-FAST first. Other chips are USB ELM327.
            if (usb.vid == ObdUsb.FTDI_VID && bmwFast(t, usb)) return
            if (!active(t)) return
            val baud = listOf(38_400, 115_200, 9_600).firstOrNull { b ->
                usb.baud(b)
                cmd(io, "ATZ", 2_000).contains("ELM", ignoreCase = true)
            }
            if (baud == null) {
                if (usb.vid != ObdUsb.FTDI_VID && bmwFast(t, usb)) return
                error("USB ${usb.name}: ни K+DCAN, ни ELM327 не ответили")
            }
            Log.i(TAG, "USB ELM327 at $baud baud")
            tag = "USB ELM327"
        }
        elm(t, io, tag)
    }

    /**
     * K+DCAN cable: OBD mode 01 PIDs in BMW-FAST telegrams to the DME (0x12), or the OBD functional
     * address (0x33) when the DME refuses. Returns false if nothing on the bus answered.
     */
    private fun bmwFast(t: String, usb: ObdUsb.Port): Boolean {
        usb.baud(115_200)
        // Identification read (service 1A 80) once, only for the log: shows that the DME talks.
        val ident = ObdUsb.request(usb, 0x12, intArrayOf(0x1A, 0x80), 2_000)
        var target = 0x12
        if (ObdUsb.pid(usb, 0x12, 0x00) == null) {
            if (ObdUsb.pid(usb, 0x33, 0x00) != null) target = 0x33
            else if (ident == null) return false
        }
        Log.i(TAG, "K+DCAN: DME answers, OBD via %02X".format(target))
        var misses = 0
        while (active(t)) {
            val c = ObdUsb.pid(usb, target, 0x05)
            val i = ObdUsb.pid(usb, target, 0x0F)
            val v = ObdUsb.pid(usb, target, 0x42)
            if (c != null) coolantV = c[0] - 40
            if (i != null) intakeV = i[0] - 40
            if (v != null && v.size >= 2) voltageV = ((v[0] shl 8) + v[1]) / 1000f
            updated = System.currentTimeMillis()
            if (c == null && i == null && v == null) {
                set(State.NO_ECU, "K+DCAN")
                if (++misses >= 5) return true
            } else {
                misses = 0
                set(State.OK, "K+DCAN")
            }
            Thread.sleep(1_000)
        }
        return true
    }

    private fun elm(t: String, io: Io, tag: String) {
        cmd(io, "ATZ", 5_000)
        for (c in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1")) cmd(io, c)
        // BMW E90 from 2007 uses D-CAN = ISO 15765-4, 11 bit, 500 kbit/s (protocol 6); fall back to auto.
        cmd(io, "ATSP6")
        if (pidBytes(cmd(io, "0100", 6_000), 0x00) == null) {
            cmd(io, "ATSP0")
            cmd(io, "0100", 10_000)
        }
        var misses = 0
        while (active(t)) {
            voltageV = volts(cmd(io, "ATRV"))
            val c = pidBytes(cmd(io, "0105"), 0x05)
            val i = pidBytes(cmd(io, "010F"), 0x0F)
            if (c != null) coolantV = c[2] - 40
            if (i != null) intakeV = i[2] - 40
            updated = System.currentTimeMillis()
            if (c == null && i == null) {
                // Adapter answers but the ECU does not: ignition off or wrong protocol.
                set(State.NO_ECU, tag)
                if (++misses >= 5) return
            } else {
                misses = 0
                set(State.OK, tag)
            }
            Thread.sleep(2_000)
        }
    }
}

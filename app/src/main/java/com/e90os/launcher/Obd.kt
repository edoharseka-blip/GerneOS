package com.e90os.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Read-only ELM327 OBD-II reader for values the KSW MCU does not report:
 * coolant (PID 05), intake air (PID 0F) and supply voltage (ATRV).
 * Only standard mode 01 requests and AT commands are sent; nothing is written to the car.
 *
 * Target: "bt:<MAC>" (paired Bluetooth SPP adapter) or "wifi:<host>:<port>" (Wi-Fi ELM327).
 * Raw adapter traffic is logged under the tag E90OBD.
 */
class Obd(private val onChange: () -> Unit) {

    companion object {
        const val TAG = "E90OBD"
        const val WIFI_DEFAULT = "wifi:192.168.0.10:35000"
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val STALE_MS = 15_000L

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

    private class Io(val input: InputStream, val output: OutputStream, val closer: Closeable) : Closeable {
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
        t.startsWith("wifi:") -> {
            val hp = t.removePrefix("wifi:")
            val host = hp.substringBeforeLast(':')
            val port = hp.substringAfterLast(':').toIntOrNull() ?: 35000
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 5_000)
            Io(s.getInputStream(), s.getOutputStream(), s)
        }
        else -> error("неизвестный адаптер: $t")
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
                set(State.NO_ECU, "")
                if (++misses >= 5) return
            } else {
                misses = 0
                set(State.OK, "")
            }
            Thread.sleep(2_000)
        }
    }
}

package com.e90os.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * USB serial OBD adapters on the head unit's USB host port:
 *  - K+DCAN cable (FTDI 0403:6001) for INPA/ISTA: BMW-FAST telegrams, the cable does D-CAN itself;
 *  - USB ELM327 (CH340, PL2303, CP210x or FTDI): the usual AT commands.
 * Read-only: only diagnostic read requests are sent (OBD mode 01, ECU identification).
 */
object ObdUsb {
    const val TAG = Obd.TAG
    const val FTDI_VID = 0x0403
    private const val PERMISSION = "com.e90os.launcher.USB_PERMISSION"

    /** Open USB serial port, with the reader thread feeding [input]. */
    class Port(val port: UsbSerialPort, val name: String, val vid: Int, val pid: Int) : Closeable {
        private val rx = LinkedBlockingQueue<Int>()
        @Volatile private var closed = false
        private val reader = Thread({
            val buf = ByteArray(256)
            while (!closed) {
                val n = try { port.read(buf, 200) } catch (e: Exception) { if (!closed) Log.i(TAG, "USB read: ${e.message}"); -1 }
                if (n < 0) { closed = true; rx.offer(-1); break }
                for (i in 0 until n) rx.offer(buf[i].toInt() and 0xFF)
            }
        }, "obd-usb-rx").apply { isDaemon = true; start() }

        fun baud(rate: Int) {
            port.setParameters(rate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.setDTR(false); port.setRTS(false) }
            Thread.sleep(50)
            rx.clear()
        }

        val input = object : InputStream() {
            override fun read(): Int = if (closed && rx.isEmpty()) -1 else rx.take()
            override fun available(): Int = rx.size
        }

        val output = object : OutputStream() {
            private val buf = java.io.ByteArrayOutputStream()
            override fun write(b: Int) { buf.write(b) }
            override fun flush() {
                val all = buf.toByteArray(); buf.reset()
                if (all.isNotEmpty()) port.write(all, 1_000)
            }
        }

        /** Next byte within [ms], or null. */
        fun next(ms: Long): Int? {
            val b = rx.poll(ms, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
            if (b < 0) error("USB-адаптер отключён")
            return b
        }

        fun clear() = rx.clear()

        override fun close() {
            closed = true
            runCatching { port.close() }
            rx.offer(-1)
        }
    }

    /** First USB serial adapter; asks for USB permission and waits up to 30 s for the answer. */
    fun open(context: Context): Port {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        if (drivers.isEmpty()) {
            val all = usb.deviceList.values.joinToString { "%04x:%04x".format(it.vendorId, it.productId) }
            error(if (all.isEmpty()) "USB-адаптер не подключён" else "неизвестное USB-устройство ($all)")
        }
        val driver = drivers.first()
        val dev = driver.device
        val id = "%04x:%04x".format(dev.vendorId, dev.productId)
        if (!usb.hasPermission(dev)) {
            Log.i(TAG, "USB $id: asking permission")
            val pi = PendingIntent.getBroadcast(context, 0,
                Intent(PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            usb.requestPermission(dev, pi)
            val end = System.currentTimeMillis() + 30_000
            while (!usb.hasPermission(dev) && System.currentTimeMillis() < end) Thread.sleep(300)
            if (!usb.hasPermission(dev)) error("нет разрешения на USB, подтвердите на экране")
        }
        val conn = usb.openDevice(dev) ?: error("USB не открывается ($id)")
        val port = driver.ports.first()
        port.open(conn)
        val name = driver.javaClass.simpleName.removeSuffix("SerialDriver")
        Log.i(TAG, "USB $id opened, driver $name")
        return Port(port, name, dev.vendorId, dev.productId)
    }

    // ---- BMW-FAST (K+DCAN cable in D-CAN mode, 115200 8N1) ----

    /** Telegram: 0x80|len, target, source 0xF1, data, checksum (sum of all bytes). */
    fun telegram(target: Int, data: IntArray): ByteArray {
        val head = if (data.size < 0x40) intArrayOf(0x80 or data.size, target, 0xF1)
                   else intArrayOf(0x80, target, 0xF1, data.size)
        val all = head + data
        return (all + (all.sum() and 0xFF)).map { it.toByte() }.toByteArray()
    }

    private fun hex(b: IntArray) = b.joinToString(" ") { "%02X".format(it) }

    /** One telegram from the cable within [ms]; null on timeout. */
    private fun readTelegram(p: Port, ms: Long): IntArray? {
        val end = System.currentTimeMillis() + ms
        fun left() = (end - System.currentTimeMillis()).coerceAtLeast(1)
        var fmt: Int
        do { fmt = p.next(left()) ?: return null } while (fmt and 0x80 == 0)   // resync on a format byte
        val tgt = p.next(left()) ?: return null
        val src = p.next(left()) ?: return null
        var len = fmt and 0x3F
        val head = mutableListOf(fmt, tgt, src)
        if (len == 0) { len = p.next(left()) ?: return null; head += len }
        val body = IntArray(len)
        for (i in 0 until len) body[i] = p.next(left()) ?: return null
        val cs = p.next(left()) ?: return null
        val all = head.toIntArray() + body
        if ((all.sum() and 0xFF) != cs) Log.d(TAG, "BMW-FAST bad checksum: ${hex(all)} $cs")
        return all
    }

    /**
     * Send [data] to ECU [target] and return the reply data (service byte first),
     * skipping the cable's echo and "response pending" (7F xx 78); null when the ECU is silent.
     */
    fun request(p: Port, target: Int, data: IntArray, ms: Long = 1_000): IntArray? {
        p.clear()
        val out = telegram(target, data)
        p.output.write(out); p.output.flush()
        val sent = out.map { it.toInt() and 0xFF }.dropLast(1)
        var end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val t = readTelegram(p, end - System.currentTimeMillis()) ?: break
            if (t.toList() == sent) continue                       // echo of our own request
            val body = t.copyOfRange(if (t[0] and 0x3F == 0) 4 else 3, t.size)
            Log.d(TAG, "${hex(data)} @%02X -> ${hex(t)}".format(target))
            if (t[2] != target && target != 0x33 && target != 0xDF) continue
            if (body.size >= 3 && body[0] == 0x7F && body[2] == 0x78) { end = System.currentTimeMillis() + 5_000; continue }
            return body
        }
        Log.d(TAG, "${hex(data)} @%02X -> (no answer)".format(target))
        return null
    }

    /** OBD mode 01 PID through BMW-FAST; reply "41 pid A B..." → [A, B...]. */
    fun pid(p: Port, target: Int, pid: Int): IntArray? {
        val r = request(p, target, intArrayOf(0x01, pid)) ?: return null
        if (r.size < 3 || r[0] != 0x41 || r[1] != pid) return null
        return r.copyOfRange(2, r.size)
    }
}

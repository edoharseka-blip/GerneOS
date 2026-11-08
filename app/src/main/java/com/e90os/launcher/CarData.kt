package com.e90os.launcher

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.json.JSONObject

/** Values the KSW head unit's MCU decodes from the car's CAN bus. Null = not reported. */
data class CarState(
    val speed: Int?,
    val rpm: Int?,
    val outsideTemp: Float?,
    val range: Int?,
    val consumption: Float?,
    val fuel: Int?,
    val handbrake: Boolean?,
    /** Average speed from the on-board computer, km/h (carData.averSpeed). */
    val avgSpeed: Float?,
    /** Door/boot bitmask (carData.carDoor); 0 = all closed. Bit meaning still being mapped on the car. */
    val doors: Int?,
    /** carData.safetyBelt as reported by the MCU; polarity not yet confirmed on the car. */
    val belt: Boolean?,
)

/**
 * Read-only access to the KSW system service "wits_pms" (com.wits.pms.IPowerManagerAppService).
 * The service keeps the last MCU frame as JSON under the status key "mcuJson"
 * (McuStatus with a nested carData object). Only getStatusString (transaction 9) is used:
 * it never changes anything on the head unit.
 */
object KswCar {
    private const val DESCRIPTOR = "com.wits.pms.IPowerManagerAppService"
    private const val TX_GET_STATUS_STRING = 9
    private var binder: IBinder? = null

    private fun service(): IBinder? {
        binder?.let { if (it.isBinderAlive) return it }
        binder = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, "wits_pms") as IBinder?
        }.getOrNull()
        return binder
    }

    fun available(): Boolean = service() != null

    fun statusString(key: String): String? {
        val b = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(key)
            b.transact(TX_GET_STATUS_STRING, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private const val TX_SEND_COMMAND = 1

    /** KSW WitsCommand: {"command":type,"subCommand":sub}. BT music: type 3, prev 100, next 101, play 103, pause 104. */
    fun sendCommand(type: Int, sub: Int): Boolean {
        val b = service() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString("{\"command\":$type,\"subCommand\":$sub,\"jsonArg\":\"\",\"needResult\":false}")
            b.transact(TX_SEND_COMMAND, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    const val BT_PREV = 100
    const val BT_NEXT = 101
    const val BT_PLAY = 103
    const val BT_PAUSE = 104
    fun bt(sub: Int) = sendCommand(3, sub)

    /** Logcat tag for the raw MCU frame: `adb logcat -s E90CAN` gives a full mcuJson dump. */
    const val LOG_TAG = "E90CAN"
    private var lastJson: String? = null

    private var dumpThread: android.os.HandlerThread? = null

    /**
     * Read the MCU frame once a second in the background, so the E90CAN log keeps recording
     * while another app or the car's own screen is in front (read() logs every change).
     */
    fun startDumpLog() {
        if (dumpThread != null) return
        val t = android.os.HandlerThread("e90can-dump").apply { start() }
        dumpThread = t
        val h = android.os.Handler(t.looper)
        h.post(object : Runnable {
            override fun run() {
                runCatching { read() }
                h.postDelayed(this, 1000)
            }
        })
    }

    fun stopDumpLog() {
        dumpThread?.quitSafely()
        dumpThread = null
    }

    @Synchronized fun read(): CarState? {
        val json = statusString("mcuJson") ?: return null
        if (json != lastJson) {
            lastJson = json
            Log.i(LOG_TAG, json)
        }
        return runCatching {
            val car = JSONObject(json).optJSONObject("carData") ?: return null
            fun int(k: String) = if (car.has(k)) car.optInt(k) else null
            fun flt(k: String) = if (car.has(k)) car.optDouble(k).toFloat() else null
            CarState(
                speed = int("speed"),
                rpm = int("engineTurnS"),
                outsideTemp = flt("airTemperature"),
                range = int("mileage"),
                consumption = flt("oilWear"),
                fuel = int("oilSum"),
                handbrake = if (car.has("handbrake")) car.optBoolean("handbrake") else null,
                avgSpeed = flt("averSpeed"),
                doors = int("carDoor"),
                belt = if (car.has("safetyBelt")) car.optBoolean("safetyBelt") else null,
            )
        }.getOrNull()
    }
}

/** Route guidance text taken from the navigator's ongoing notification. */
data class RouteInfo(val pkg: String, val title: String, val text: String, val time: Long)

object RouteState {
    @Volatile var info: RouteInfo? = null
    val navPackages = setOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.google.android.apps.maps")

    private val km = Regex("""(\d+[.,]?\d*)\s*(км|km)""", RegexOption.IGNORE_CASE)
    private val m = Regex("""(\d+)\s*(м|m)\b""", RegexOption.IGNORE_CASE)
    private val hours = Regex("""(\d+)\s*(ч|h)\b""", RegexOption.IGNORE_CASE)
    private val mins = Regex("""(\d+)\s*(мин|min)""", RegexOption.IGNORE_CASE)
    private val clock = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")

    /** Parsed pieces: distance, duration, arrival time, plus the raw line for the maneuver. */
    fun parse(r: RouteInfo): Triple<String?, String?, String?> {
        val all = r.title + "  " + r.text
        val dist = km.find(all)?.value ?: m.find(all)?.value
        val dur = listOfNotNull(hours.find(all)?.value, mins.find(all)?.value).joinToString(" ").ifEmpty { null }
        val eta = clock.find(all)?.value
        return Triple(dist, dur, eta)
    }
}

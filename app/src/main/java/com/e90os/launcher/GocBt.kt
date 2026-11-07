package com.e90os.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Phone connection status from the head unit's Bluetooth app (KSW, com.wits.ksw.bt).
 * Its GOC Bluetooth service is not exported, but the app answers the EasyConnection
 * status query: we send "net.easyconn.bt.checkstatus" and it replies with
 * "net.easyconn.bt.connected" or "net.easyconn.bt.notconnected". Read-only.
 */
class GocBt(private val context: Context, private val onChange: () -> Unit) {

    companion object {
        const val PKG = "com.wits.ksw.bt"
        private const val CHECK = "net.easyconn.bt.checkstatus"
        private const val CONNECTED = "net.easyconn.bt.connected"
        private const val NOT_CONNECTED = "net.easyconn.bt.notconnected"
    }

    /** null = no answer yet (old firmware or the BT app is not running). */
    var connected: Boolean? = null; private set
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = when (i.action) {
                CONNECTED -> true
                NOT_CONNECTED -> false
                else -> return
            }
            android.util.Log.i("E90OS.Bt", "status ${i.action}")
            if (now != connected) { connected = now; onChange() }
        }
    }

    fun start() {
        if (registered) return
        context.registerReceiver(receiver, IntentFilter().apply { addAction(CONNECTED); addAction(NOT_CONNECTED) })
        registered = true
        query()
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun query() {
        runCatching { context.sendBroadcast(Intent(CHECK).setPackage(PKG)) }
    }
}

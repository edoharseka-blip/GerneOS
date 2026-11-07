package com.e90os.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Title of the song playing from the phone over the head unit's Bluetooth.
 *
 * The KSW Bluetooth app receives the track title from the phone but does not put it into
 * its media session; it only logs it ("gocImpl: onMusicInfo<title>"). With the
 * READ_LOGS permission (granted once over adb: pm grant com.e90os.launcher
 * android.permission.READ_LOGS) we follow that log line. Play/pause state comes from the
 * KSW app's broadcast to the voice assistant ("com.txznet.adapter.recv", action music.status).
 */
class BtMusic(private val context: Context, private val onChange: () -> Unit) {

    var title: String? = null; private set
    var playing: Boolean? = null; private set
    var updated = 0L; private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var proc: Process? = null
    private var receiverOn = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getStringExtra("action") != "music.status") return
            val s = i.getStringExtra("status") ?: return
            playing = s == "play"
            onChange()
        }
    }

    val canReadLogs: Boolean
        get() = context.checkSelfPermission("android.permission.READ_LOGS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!receiverOn) {
            runCatching { context.registerReceiver(statusReceiver, IntentFilter("com.txznet.adapter.recv")) }
            receiverOn = true
        }
        if (running || !canReadLogs) return
        running = true
        Thread({ follow() }, "bt-music-log").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { proc?.destroy() }
        if (receiverOn) { runCatching { context.unregisterReceiver(statusReceiver) }; receiverOn = false }
    }

    private fun handle(line: String) {
        val i = line.indexOf("onMusicInfo")
        if (i < 0) return
        val t = line.substring(i + "onMusicInfo".length).trim()
        if (t.isEmpty()) return
        main.post {
            if (t != title) { title = t; updated = System.currentTimeMillis(); onChange() }
        }
    }

    private fun follow() {
        try {
            // Last known title first, then follow new lines.
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "raw", "-s", "gocImpl:I")).let { p ->
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    lines.lastOrNull { it.contains("onMusicInfo") }?.let { handle(it) }
                }
            }
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "-v", "raw", "-s", "gocImpl:I"))
            proc = p
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (running) {
                    val line = r.readLine() ?: break
                    handle(line)
                }
            }
        } catch (e: Exception) {
            Log.w("E90OS.BtMusic", "log follow failed", e)
        } finally {
            running = false
        }
    }
}

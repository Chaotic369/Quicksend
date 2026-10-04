package com.quicksend.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared app state (survives Activity recreation; the Service writes, the UI reads). */
object Engine {
    val progress = MutableStateFlow(Progress())
    val logLines = MutableStateFlow<List<String>>(emptyList())
    val receiving = MutableStateFlow(false)
    val selection = MutableStateFlow<List<Entry>>(emptyList())
    @Volatile var pendingSend: SendRequest? = null
    private var recv: Tracker? = null

    fun log(msg: String) { logLines.update { (it + msg).takeLast(30) } }

    @Synchronized
    fun recvTracker(sid: Long, files: Int, total: Long, sender: String): Tracker {
        val cur = recv
        if (cur != null && cur.sessionId == sid) return cur
        val t = Tracker("receive", sid, files, total)
        recv = t
        t.publish(true)
        log("Receiving from $sender ($files files)")
        return t
    }

    @Synchronized
    fun failReceive(msg: String?) { recv?.fail(msg ?: "Receive failed") }
}

/** Progress counters, shared by all channels (Wi-Fi + Bluetooth) of one transfer. */
class Tracker(val direction: String, val sessionId: Long, val totalFiles: Int, val totalBytes: Long) {
    private val done = AtomicLong()
    private val wifi = AtomicLong()
    private val bt = AtomicLong()
    private val files = AtomicInteger()
    private val failed = AtomicInteger()
    private val start = SystemClock.elapsedRealtime()
    @Volatile private var last = 0L
    @Volatile var current = ""
    @Volatile var error: String? = null
    @Volatile var finished = false

    val failedCount get() = failed.get()

    fun add(n: Long, isBt: Boolean) {
        done.addAndGet(n)
        (if (isBt) bt else wifi).addAndGet(n)
        publish(false)
    }
    fun fileDone(): Int { val c = files.incrementAndGet(); publish(true); return c }
    fun fileFailed() { failed.incrementAndGet(); publish(true) }
    fun fail(msg: String) { error = msg; publish(true) }
    fun finish() { finished = true; publish(true) }

    fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - last < 250) return
        last = now
        val el = maxOf(1L, now - start)
        Engine.progress.value = Progress(
            active = !finished, direction = direction,
            totalBytes = totalBytes, doneBytes = done.get(),
            totalFiles = totalFiles, doneFiles = files.get(), failedFiles = failed.get(),
            current = current, speedBps = done.get() * 1000 / el,
            wifiBytes = wifi.get(), btBytes = bt.get(),
            finished = finished, error = error,
        )
    }
}

object Perms {
    fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun bt(ctx: Context) = Build.VERSION.SDK_INT < 31 || has(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    fun btScan(ctx: Context) =
        if (Build.VERSION.SDK_INT >= 31) has(ctx, Manifest.permission.BLUETOOTH_SCAN)
        else has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun needed(): Array<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_CONNECT
            l += Manifest.permission.BLUETOOTH_SCAN
        } else l += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l.toTypedArray()
    }
}

object Net {
    fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .map { "${it.hostAddress}  (${ni.name})" }
            }
    }.getOrDefault(emptyList())
}

package com.quicksend.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service: keeps transfers alive with the screen off.
 * Receiving = TCP server (+ mDNS advert) and Bluetooth RFCOMM server, both listening at once.
 */
@SuppressLint("MissingPermission")
class TransferService : Service() {
    companion object {
        const val ACTION_START_RECEIVE = "qs.START_RECEIVE"
        const val ACTION_STOP_RECEIVE = "qs.STOP_RECEIVE"
        const val ACTION_SEND = "qs.SEND"
        private const val CH = "quicksend"
        private const val NOTIF_ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tcp: ServerSocket? = null
    private var btServer: BluetoothServerSocket? = null
    private var recvActive = false
    private val activeSends = AtomicInteger(0)
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Transfers", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            Engine.progress.collect { p ->
                if (p.active && p.totalBytes > 0) {
                    val pct = (p.doneBytes * 100 / p.totalBytes).toInt()
                    val verb = if (p.direction == "send") "Sending" else "Receiving"
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIF_ID, notif("$verb ${p.doneFiles}/${p.totalFiles} files • $pct%", pct))
                    delay(1000)
                }
            }
        }
    }

    private fun notif(text: String, pct: Int = -1): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("QuickSend")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
        if (pct >= 0) b.setProgress(100, pct, false)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notif("QuickSend is running"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        when (intent?.action) {
            ACTION_START_RECEIVE -> startReceiving(
                intent.getBooleanExtra("wifi", true), intent.getBooleanExtra("bt", true))
            ACTION_STOP_RECEIVE -> { stopReceiving(); maybeStop() }
            ACTION_SEND -> startSend()
            else -> maybeStop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopReceiving()
        scope.cancel()
        super.onDestroy()
    }

    private fun maybeStop() {
        if (!recvActive && activeSends.get() == 0) stopSelf()
    }

    // ---------------- sending ----------------

    private fun startSend() {
        val req = Engine.pendingSend
        Engine.pendingSend = null
        if (req == null) { maybeStop(); return }
        activeSends.incrementAndGet()
        scope.launch {
            try {
                Sender.send(applicationContext, req)
            } catch (e: Exception) {
                Engine.log("Send failed: ${e.message}")
            } finally {
                activeSends.decrementAndGet()
                maybeStop()
            }
        }
    }

    // ---------------- receiving ----------------

    private fun startReceiving(wifi: Boolean, bt: Boolean) {
        if (recvActive) return
        recvActive = true
        Engine.receiving.value = true
        if (wifi) scope.launch { runTcp() }
        if (bt) scope.launch { runBt() }
        if (!wifi && !bt) { stopReceiving(); maybeStop() }
    }

    private fun runTcp() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(Proto.PORT))
            tcp = ss
            registerNsd()
            Engine.log("Wi-Fi listening on port ${Proto.PORT}")
            while (true) {
                val s = ss.accept()
                s.tcpNoDelay = true
                scope.launch { serve(Conn(s.getInputStream(), s.getOutputStream(), s), false) }
            }
        } catch (e: IOException) {
            if (recvActive) Engine.log("Wi-Fi server stopped: ${e.message}")
        }
    }

    private fun runBt() {
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled || !Perms.bt(this)) {
                Engine.log("Bluetooth receive unavailable (off or no permission)")
                return
            }
            val ss = adapter.listenUsingInsecureRfcommWithServiceRecord("QuickSend", Proto.BT_UUID)
            btServer = ss
            Engine.log("Bluetooth listening")
            while (true) {
                val s = ss.accept()
                scope.launch { serve(Conn(s.inputStream, s.outputStream, s), true) }
            }
        } catch (e: IOException) {
            if (recvActive) Engine.log("Bluetooth server stopped: ${e.message}")
        }
    }

    private fun serve(conn: Conn, isBt: Boolean) {
        try {
            Receiver.handle(applicationContext, conn, isBt)
        } catch (e: Exception) {
            Engine.log("Receive error: ${e.message}")
            Engine.failReceive(e.message)
        } finally {
            conn.close()
        }
    }

    private fun registerNsd() {
        runCatching {
            val mgr = getSystemService(NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "QuickSend-${Build.MODEL}"
                serviceType = Proto.SERVICE_TYPE
                port = Proto.PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = mgr
            regListener = l
        }
    }

    private fun stopReceiving() {
        recvActive = false
        runCatching { tcp?.close() }; tcp = null
        runCatching { btServer?.close() }; btServer = null
        regListener?.let { l -> runCatching { nsd?.unregisterService(l) } }
        regListener = null
        Engine.receiving.value = false
    }
}

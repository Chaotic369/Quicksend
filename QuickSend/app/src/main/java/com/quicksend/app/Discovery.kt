package com.quicksend.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Finds QuickSend receivers on the local Wi-Fi network (mDNS / NSD). */
class NsdScanner(ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var busy = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                synchronized(this@NsdScanner) { pending.add(i) }
                pump()
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                peers.update { l -> l.filter { it.name != i.serviceName } }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        val s = synchronized(this) {
            if (busy) return
            val n = pending.removeFirstOrNull() ?: return
            busy = true
            n
        }
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                synchronized(this@NsdScanner) { busy = false }
                pump()
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val host = i.host?.hostAddress
                if (host != null) peers.update { l ->
                    l.filter { it.name != i.serviceName } + Peer(i.serviceName, host, i.port)
                }
                synchronized(this@NsdScanner) { busy = false }
                pump()
            }
        })
    }
}

/** Lists paired Bluetooth devices and scans for nearby ones. */
@SuppressLint("MissingPermission")
class BtScanner(private val ctx: Context) {
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    val devices = MutableStateFlow<List<BtDev>>(emptyList())
    val scanning = MutableStateFlow(false)
    private var registered = false

    fun enabled() = adapter?.isEnabled == true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    d?.let { add(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning.value = false
            }
        }
    }

    fun refreshBonded() {
        if (adapter == null || !Perms.bt(ctx)) return
        runCatching { adapter.bondedDevices?.forEach { add(it) } }
    }

    fun scan() {
        if (adapter == null || !adapter.isEnabled || !Perms.btScan(ctx)) {
            Engine.log("Bluetooth scan unavailable (turn Bluetooth on / grant permission)")
            return
        }
        if (!registered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        adapter.cancelDiscovery()
        if (adapter.startDiscovery()) scanning.value = true
    }

    fun stop() {
        if (registered) { runCatching { ctx.unregisterReceiver(receiver) }; registered = false }
        runCatching { adapter?.cancelDiscovery() }
        scanning.value = false
    }

    private fun add(d: BluetoothDevice) {
        val name = runCatching { d.name }.getOrNull() ?: d.address
        devices.update { l -> if (l.any { it.address == d.address }) l else l + BtDev(name, d.address) }
    }
}

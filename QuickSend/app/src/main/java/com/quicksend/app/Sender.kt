package com.quicksend.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Sends a shared queue of files over one or two channels at the same time.
 * Each channel pulls the next file from the queue, so the faster link carries more.
 * If a channel dies, its current file goes back in the queue for the other channel.
 */
@SuppressLint("MissingPermission")
object Sender {
    suspend fun send(ctx: Context, req: SendRequest) = coroutineScope {
        val queue = ConcurrentLinkedQueue(req.entries)
        val total = req.entries.sumOf { it.size }
        val sid = Random.nextLong()
        val tracker = Tracker("send", sid, req.entries.size, total)
        tracker.publish(true)

        val jobs = mutableListOf<Deferred<Boolean>>()
        req.wifi?.let { p ->
            jobs += async(Dispatchers.IO) {
                runChannel(ctx, false, queue, tracker, sid) {
                    val s = Socket()
                    s.connect(InetSocketAddress(p.host, p.port), 8000)
                    s.tcpNoDelay = true
                    Conn(s.getInputStream(), s.getOutputStream(), s)
                }
            }
        }
        req.btAddress?.let { addr ->
            jobs += async(Dispatchers.IO) {
                runChannel(ctx, true, queue, tracker, sid) { btConn(ctx, addr) }
            }
        }
        jobs.awaitAll()

        val left = queue.size + tracker.failedCount
        if (left > 0) tracker.fail("$left file(s) could not be sent")
        tracker.finish()
        Engine.log(if (left > 0) "Finished with errors" else "Send complete")
    }

    private fun btConn(ctx: Context, addr: String): Conn {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("No Bluetooth on this device")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        adapter.cancelDiscovery()
        val dev = adapter.getRemoteDevice(addr)
        val s = dev.createInsecureRfcommSocketToServiceRecord(Proto.BT_UUID)
        try { s.connect() } catch (e: IOException) { runCatching { s.close() }; throw e }
        return Conn(s.inputStream, s.outputStream, s)
    }

    private fun runChannel(
        ctx: Context, isBt: Boolean,
        queue: ConcurrentLinkedQueue<Entry>, tracker: Tracker, sid: Long,
        open: () -> Conn,
    ): Boolean {
        val label = if (isBt) "Bluetooth" else "Wi-Fi"
        var conn: Conn? = null
        var current: Entry? = null
        var sentOfCurrent = 0L
        try {
            conn = open()
            Engine.log("$label connected")
            val out = DataOutputStream(BufferedOutputStream(conn.output, Proto.BUF))
            val inp = DataInputStream(conn.input)
            out.writeInt(Proto.MAGIC)
            out.writeLong(sid)
            out.writeInt(tracker.totalFiles)
            out.writeLong(tracker.totalBytes)
            out.writeUTF(Build.MODEL)
            out.flush()

            val buf = ByteArray(Proto.BUF)
            while (true) {
                val e = queue.poll() ?: break
                current = e
                sentOfCurrent = 0
                tracker.current = e.path
                val ins = ctx.contentResolver.openInputStream(e.uri)
                    ?: throw FileNotFoundException(e.path)
                ins.use {
                    out.writeByte(1)
                    out.writeUTF(e.path)
                    out.writeLong(e.size)
                    var remaining = e.size
                    while (remaining > 0) {
                        val n = it.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) throw EOFException("File changed while sending: ${e.path}")
                        out.write(buf, 0, n)
                        remaining -= n
                        sentOfCurrent += n
                        tracker.add(n.toLong(), isBt)
                    }
                }
                tracker.fileDone()
                current = null
            }
            out.writeByte(0)
            out.flush()
            inp.readByte() // receiver ACK
            Engine.log("$label channel done")
            return true
        } catch (ex: Exception) {
            Engine.log("$label error: ${ex.message}")
            current?.let {
                tracker.add(-sentOfCurrent, isBt)
                if (++it.tries < 3) queue.add(it) else tracker.fileFailed()
            }
            return false
        } finally {
            conn?.close()
        }
    }
}

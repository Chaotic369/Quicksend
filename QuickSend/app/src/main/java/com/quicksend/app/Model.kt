package com.quicksend.app

import android.net.Uri
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

object Proto {
    const val MAGIC = 0x51534E44            // "QSND"
    const val PORT = 48555
    const val SERVICE_TYPE = "_quicksend._tcp."
    val BT_UUID: UUID = UUID.fromString("b3d4f6a0-5c1e-4a52-9f0e-7d2a1c9e5b11")
    const val BUF = 64 * 1024
}

/** One file to send. [path] is the relative path (keeps folder structure on the receiver). */
class Entry(val uri: Uri, val path: String, val size: Long) {
    @Volatile var tries = 0
}

data class Peer(val name: String, val host: String, val port: Int)
data class BtDev(val name: String, val address: String)

/** A connected byte stream (TCP socket or Bluetooth RFCOMM socket). */
class Conn(val input: InputStream, val output: OutputStream, private val closer: Closeable) : Closeable {
    override fun close() { runCatching { closer.close() } }
}

data class SendRequest(val entries: List<Entry>, val wifi: Peer?, val btAddress: String?)

data class Progress(
    val active: Boolean = false,
    val direction: String = "",       // "send" | "receive" | ""
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val totalFiles: Int = 0,
    val doneFiles: Int = 0,
    val failedFiles: Int = 0,
    val current: String = "",
    val speedBps: Long = 0,
    val wifiBytes: Long = 0,
    val btBytes: Long = 0,
    val finished: Boolean = false,
    val error: String? = null,
)

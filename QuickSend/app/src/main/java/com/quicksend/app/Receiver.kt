package com.quicksend.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * Wire protocol (same over Wi-Fi TCP and Bluetooth RFCOMM):
 *   Hello : int MAGIC | long sessionId | int totalFiles | long totalBytes | UTF senderName
 *   File  : byte 1 | UTF relativePath | long size | <size raw bytes>      (repeated)
 *   End   : byte 0   -> receiver answers with byte 1
 */
object Receiver {
    fun handle(ctx: Context, conn: Conn, isBt: Boolean) {
        val inp = DataInputStream(BufferedInputStream(conn.input, Proto.BUF))
        val out = DataOutputStream(conn.output)
        if (inp.readInt() != Proto.MAGIC) throw IOException("Not a QuickSend connection")
        val sid = inp.readLong()
        val files = inp.readInt()
        val total = inp.readLong()
        val sender = inp.readUTF()
        val tracker = Engine.recvTracker(sid, files, total, sender)
        val buf = ByteArray(Proto.BUF)

        while (true) {
            val type = inp.readByte().toInt()
            if (type == 0) break
            if (type != 1) throw IOException("Protocol error")
            val path = clean(inp.readUTF())
            val size = inp.readLong()
            tracker.current = path

            val target = Saver.create(ctx, path)
            var ok = false
            var got = 0L
            try {
                target.out.use { os ->
                    var remaining = size
                    while (remaining > 0) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) throw EOFException("Connection lost")
                        os.write(buf, 0, n)
                        remaining -= n
                        got += n
                        tracker.add(n.toLong(), isBt)
                    }
                }
                ok = true
            } finally {
                if (ok) Saver.commit(ctx, target.uri)
                else { Saver.discard(ctx, target.uri); tracker.add(-got, isBt) }
            }
            val done = tracker.fileDone()
            if (done + tracker.failedCount >= tracker.totalFiles) tracker.finish()
        }
        out.writeByte(1)
        out.flush()
    }

    private fun clean(p: String): String =
        p.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/").ifEmpty { "file" }
}

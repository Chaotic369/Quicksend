package com.quicksend.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.OutputStream

/** Writes received files into Downloads/QuickSend/<relative path> (no storage permission needed). */
object Saver {
    class Target(val uri: Uri, val out: OutputStream)

    fun create(ctx: Context, path: String): Target {
        val name = path.substringAfterLast('/')
        val dir = path.substringBeforeLast('/', "")
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val rel = if (dir.isEmpty()) "Download/QuickSend" else "Download/QuickSend/$dir"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("Cannot create $path")
        val os = ctx.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open $path")
        return Target(uri, os)
    }

    fun commit(ctx: Context, uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, v, null, null)
    }

    fun discard(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }
}

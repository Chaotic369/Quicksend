package com.quicksend.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Turns picked files / folders into a flat list of [Entry] (folders are walked recursively). */
object FileSource {
    private fun persist(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
    }

    fun fromFiles(ctx: Context, uris: List<Uri>): List<Entry> = uris.mapNotNull { u ->
        persist(ctx, u)
        val d = DocumentFile.fromSingleUri(ctx, u) ?: return@mapNotNull null
        Entry(u, d.name ?: "file_${u.lastPathSegment}", d.length())
    }

    fun fromTree(ctx: Context, tree: Uri): List<Entry> {
        persist(ctx, tree)
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val out = ArrayList<Entry>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.add(root to (root.name ?: "folder"))
        while (stack.isNotEmpty()) {
            val (dir, prefix) = stack.removeLast()
            for (c in dir.listFiles()) {
                val n = c.name ?: continue
                if (c.isDirectory) stack.add(c to "$prefix/$n")
                else if (c.isFile) out.add(Entry(c.uri, "$prefix/$n", c.length()))
            }
        }
        return out
    }
}

package com.example.peersync.data

import com.example.peersync.AppInfo
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.example.peersync.proto.FileNames
import com.example.peersync.proto.ReadHandle
import com.example.peersync.proto.SendItem
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

private const val TAG = "${AppInfo.NAME}/SendItems"

/**
 * SendItem for a content URI (SAF file, photo picker photo/video, file in a SAF tree).
 * Reads via openFileDescriptor -> FileChannel (to seek when resuming).
 * If the provider can't seek, reopen the InputStream and skip.
 */
class ContentSendItem(
    context: Context,
    private val uri: Uri,
    override val relPath: String,
    override val size: Long,
    override val mtime: Long,
) : SendItem {
    private val resolver = context.applicationContext.contentResolver

    override fun open(): ReadHandle {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open $uri")
        val stream = FileInputStream(pfd.fileDescriptor)
        val channel = stream.channel
        val len = pfd.statSize.takeIf { it >= 0 } ?: size
        Log.d(TAG, "open $relPath size=$len")
        return object : ReadHandle {
            private var fallback: InputStream? = null
            override val length: Long = len

            override fun seek(pos: Long) {
                try {
                    channel.position(pos)
                } catch (e: IOException) {
                    // Provider can't seek (pipe etc.): reopen and skip ahead
                    Log.w(TAG, "seek unsupported for $uri; skipping $pos bytes instead")
                    val s = resolver.openInputStream(uri) ?: throw e
                    var left = pos
                    while (left > 0) {
                        val n = s.skip(left)
                        if (n <= 0) break
                        left -= n
                    }
                    fallback = s
                }
            }

            override fun read(buf: ByteArray, off: Int, len: Int): Int =
                fallback?.read(buf, off, len) ?: channel.read(ByteBuffer.wrap(buf, off, len))

            override fun close() {
                runCatching { fallback?.close() }
                runCatching { stream.close() }
                runCatching { pfd.close() }
            }
        }
    }
}

/** Builds SendItems from picker results (content URIs). Call on an IO thread. */
object AndroidSendItems {

    /** Multiple files, photos, videos: relPath is just each display name. Unreadable items are skipped. */
    fun ofUris(context: Context, uris: List<Uri>): List<SendItem> {
        val out = ArrayList<SendItem>()
        val used = HashSet<String>()
        for (uri in uris) {
            val meta = queryMeta(context, uri) ?: run {
                Log.w(TAG, "skip (no metadata): $uri")
                continue
            }
            // Disambiguate duplicates with "name (1).ext" (duplicates within the send list, separate from overwrite avoidance on the other side)
            val name = FileNames.firstFree(meta.name) { it in used }
            used += name
            out += ContentSendItem(context, uri, name, meta.size, meta.mtime)
        }
        Log.d(TAG, "ofUris: ${uris.size} picked -> ${out.size} items")
        return out
    }

    /**
     * SAF tree: expands contents recursively as "<folder name>/<relative path>" (empty folders aren't sent).
     */
    fun ofTree(context: Context, treeUri: Uri): List<SendItem> {
        val resolver = context.contentResolver
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        var rootName = "folder"
        resolver.query(rootUri, columns, null, null, null)?.use { c ->
            if (c.moveToFirst()) rootName = c.getString(1) ?: rootName
        }
        val out = ArrayList<SendItem>()

        fun walk(docId: String, prefix: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(childrenUri, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val mtime = if (c.isNull(4)) System.currentTimeMillis() else c.getLong(4)
                    val rel = "$prefix/$name"
                    if (mime == Document.MIME_TYPE_DIR) {
                        walk(id, rel)
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        out += ContentSendItem(context, uri, rel, size, mtime)
                    }
                }
            }
        }
        walk(rootId, rootName)
        Log.d(TAG, "ofTree: $rootName -> ${out.size} items")
        return out
    }

    private class Meta(val name: String, val size: Long, val mtime: Long)

    private fun queryMeta(context: Context, uri: Uri): Meta? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        var mtime = 0L
        // Display name and size (every provider returns these)
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        // Modified time: last_modified for SAF documents, date_taken (ms) for the photo picker
        runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                resolver.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) mtime = c.getLong(0)
                }
            } else {
                resolver.query(uri, arrayOf(MediaStore.PickerMediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) mtime = c.getLong(0)
                }
            }
        }
        if (size < 0) {
            // Provider without SIZE: take it from the fd
            size = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            }.getOrDefault(-1L)
            if (size < 0) return null
        }
        if (mtime <= 0) mtime = System.currentTimeMillis()
        val n = name?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "file")
        return Meta(n.replace('/', '_'), size, mtime)
    }
}

package com.example.peersync.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.example.peersync.AppInfo
import com.example.peersync.proto.DownloadSink
import com.example.peersync.proto.DownloadTargets
import com.example.peersync.proto.FileDownloadTargets
import com.example.peersync.proto.FileNames
import com.example.peersync.proto.FileServer
import com.example.peersync.proto.SinkResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "${AppInfo.NAME}/SafSave"

/** Save folders on Android (0.2.32): Download/PeerSync by default, or a folder picked with SAF. */
class AndroidSaveFolderAccess(
    private val context: Context,
    private val defaultDir: () -> File,
) : SaveFolderAccess {
    override val defaultPath: String get() = defaultDir().path

    override fun defaultTargets(): DownloadTargets = FileDownloadTargets(defaultDir)

    private fun canWrite(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    override fun customPath(token: String): String? {
        val uri = Uri.parse(token)
        if (!canWrite(uri)) {
            Log.w(TAG, "no persisted write permission for $uri")
            return null
        }
        return treeRealPath(uri)
    }

    override fun customName(token: String): String = treeRealPath(Uri.parse(token)).substringAfterLast('/')

    override fun customTargets(token: String): DownloadTargets? {
        val uri = Uri.parse(token)
        return if (canWrite(uri)) SafDownloadTargets(context, uri) else null
    }

    override fun onCustomReplaced(oldToken: String?, newToken: String) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(Uri.parse(newToken), flags)
        if (oldToken != null && oldToken != newToken) {
            resolver.releasePersistableUriPermission(Uri.parse(oldToken), flags)
            Log.d(TAG, "released old save folder permission: $oldToken")
        }
    }

    companion object {
        /**
         * Real path of a tree on external storage: "primary:Download/X" -> /storage/emulated/0/Download/X,
         * "1234-5678:Music" -> /storage/1234-5678/Music. Other providers fall back to the URI.
         */
        fun treeRealPath(treeUri: Uri): String {
            if (treeUri.authority != "com.android.externalstorage.documents") return treeUri.toString()
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            val volume = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            val base = if (volume == "primary") Environment.getExternalStorageDirectory().path else "/storage/$volume"
            return if (rel.isEmpty()) base else "$base/$rel"
        }
    }
}

/** <tree>/<peer name>/<remote path> inside a SAF tree. */
class SafDownloadTargets(private val context: Context, private val treeUri: Uri) : DownloadTargets {
    override fun create(peerName: String, remotePath: String): SinkResult {
        val parts = remotePath.trim('/').split('/').filter { it.isNotEmpty() }
        return SinkResult.Ready(SafDownloadSink(context, treeUri, listOf(peerName) + parts.dropLast(1), parts.last()))
    }
}

/**
 * DownloadSink on a SAF tree. Same rules as FileDownloadSink: the partial file is
 * `<name>.peersync-part` (resumed next time) and is renamed on completion ("name (1).ext" when taken).
 * Writes through openFileDescriptor("rw"), which ExternalStorageProvider supports.
 */
class SafDownloadSink(
    context: Context,
    private val treeUri: Uri,
    private val dirNames: List<String>,
    private val fileName: String,
) : DownloadSink {
    private val resolver = context.contentResolver
    private val partName = fileName + FileServer.PART_SUFFIX
    private var dirUri: Uri? = null
    private var partUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var out: FileOutputStream? = null

    private data class Child(val uri: Uri, val name: String, val isDir: Boolean, val size: Long)

    private fun children(dir: Uri): List<Child> {
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(dir))
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val out = ArrayList<Child>()
        resolver.query(list, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                    name = c.getString(1) ?: "",
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                )
            }
        }
        return out
    }

    /** The folder for this file, creating <peer>/<sub folders> when missing. */
    private fun dir(): Uri {
        dirUri?.let { return it }
        var cur = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        for (name in dirNames) {
            cur = children(cur).firstOrNull { it.isDir && it.name == name }?.uri
                ?: DocumentsContract.createDocument(resolver, cur, Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Can't create the folder \"$name\"")
        }
        dirUri = cur
        return cur
    }

    override fun partLength(): Long = children(dir()).firstOrNull { !it.isDir && it.name == partName }?.size ?: 0L

    override fun prepare(start: Long) {
        val d = dir()
        val uri = children(d).firstOrNull { !it.isDir && it.name == partName }?.uri
            ?: DocumentsContract.createDocument(resolver, d, "application/octet-stream", partName)
            ?: throw IOException("Can't create \"$partName\"")
        val fd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Can't open \"$partName\"")
        val stream = FileOutputStream(fd.fileDescriptor)
        // When the server adjusted the offset (the source got smaller), match it
        stream.channel.truncate(start)
        stream.channel.position(start)
        partUri = uri
        pfd = fd
        out = stream
        Log.d(TAG, "prepare $partName at $start")
    }

    override fun write(chunk: ByteArray) {
        out!!.write(chunk)
    }

    override fun commit(): String {
        // Closed without runCatching: a failed flush on completion must surface as an error
        out?.close()
        pfd?.close()
        out = null
        pfd = null
        val d = dir()
        val taken = children(d).map { it.name }.toSet()
        val finalName = FileNames.firstFree(fileName) { it in taken }
        DocumentsContract.renameDocument(resolver, partUri!!, finalName)
        val path = (listOf(AndroidSaveFolderAccess.treeRealPath(treeUri)) + dirNames + finalName).joinToString("/")
        Log.d(TAG, "saved $path")
        return path
    }

    override fun abort() {
        // The partial file stays (resumed next time). Cleanup only: a close error must not hide the original one
        runCatching { out?.close() }
        runCatching { pfd?.close() }
        out = null
        pfd = null
    }
}

package com.example.peersync.data

import com.example.peersync.proto.DownloadSink
import com.example.peersync.proto.DownloadTargets
import com.example.peersync.proto.FileNames
import com.example.peersync.proto.FileServer
import com.example.peersync.proto.ProtocolException
import com.example.peersync.proto.ReadHandle
import com.example.peersync.proto.SendItem
import com.example.peersync.proto.SinkResult
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.fwrite
import platform.posix.rename
import platform.posix.truncate

/*
 * iOS storage. Directory operations (attributes, creation, existence) use NSFileManager;
 * file I/O uses POSIX (fopen / fread / fwrite / ftruncate / rename).
 * NSFileHandle is not used (with Xcode 26 SDK + Kotlin/Native,
 * members like seekToFileOffset don't resolve; switched to POSIX in 0.2.1).
 */

/** NSUserDefaults-based KeyValueStore (the old iOS version used settings.json; no migration, spec 2). */
class IosKeyValueStore : KeyValueStore {
    private val d = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = d.stringForKey(key)

    override fun putString(key: String, value: String?) {
        if (value == null) d.removeObjectForKey(key) else d.setObject(value, key)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (d.objectForKey(key) == null) default else d.boolForKey(key)

    override fun putBoolean(key: String, value: Boolean) {
        d.setBool(value, key)
    }
}

/** ReadHandle over fopen(path, "rb") (files picked for "Send"). */
@OptIn(ExperimentalForeignApi::class)
fun posixReadHandle(path: String, len: Long): ReadHandle {
    val file: CPointer<FILE> = fopen(path, "rb") ?: throw ProtocolException("cannot open")
    return object : ReadHandle {
        private var closed = false
        override val length: Long = len

        override fun seek(pos: Long) {
            fseeko(file, pos, SEEK_SET)
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = buf.usePinned { pinned ->
                fread(pinned.addressOf(off), 1.convert(), len.convert(), file)
            }.toLong()
            return if (n <= 0L) -1 else n.toInt()
        }

        override fun close() {
            if (!closed) {
                closed = true
                fclose(file)
            }
        }
    }
}

/** One file for "Send" (iOS). path is a readable real path prepared by the Swift picker. */
class IosFileSendItem(
    override val relPath: String,
    private val path: String,
    override val size: Long,
    override val mtime: Long,
) : SendItem {
    override fun open(): ReadHandle = posixReadHandle(path, size)
}

/**
 * POSIX-based DownloadSink (iOS).
 * Writes to the partial file <dest>.peersync-part and moves it to the final name on completion ("name (1).ext" on a clash).
 */
@OptIn(ExperimentalForeignApi::class)
class IosDownloadSink(private val destPath: String) : DownloadSink {
    private val fm = NSFileManager.defaultManager
    private val partPath = destPath + FileServer.PART_SUFFIX

    /** Error text when writing fails (used in 2 places, 0.2.30). */
    private fun cannotWrite() = "Can't write to the save location: $partPath"
    private var file: CPointer<FILE>? = null

    private fun parentDir(path: String): String = path.substringBeforeLast('/', "")

    private fun ensureParent() {
        fm.createDirectoryAtPath(
            parentDir(destPath),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    override fun partLength(): Long {
        ensureParent()
        val attrs = fm.attributesOfItemAtPath(partPath, null) ?: return 0L
        return (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
    }

    override fun prepare(start: Long) {
        ensureParent()
        // Open with "a" so the file is always created; if the server adjusted the offset
        // (e.g. the source shrank), truncate to start before writing
        fopen(partPath, "ab")?.let { fclose(it) }
            ?: throw ProtocolException(cannotWrite())
        truncate(partPath, start)
        val f = fopen(partPath, "r+b") ?: throw ProtocolException(cannotWrite())
        fseeko(f, start, SEEK_SET)
        file = f
    }

    override fun write(chunk: ByteArray) {
        val f = file ?: throw ProtocolException("sink not prepared")
        if (chunk.isEmpty()) return
        val n = chunk.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), chunk.size.convert(), f)
        }.toLong()
        if (n != chunk.size.toLong()) throw ProtocolException("Write failed. Check free storage.")
    }

    override fun commit(): String {
        file?.let {
            fflush(it)
            fclose(it)
        }
        file = null
        val finalPath = uniqueTarget(destPath)
        if (rename(partPath, finalPath) != 0) {
            throw ProtocolException("Couldn't move the file.")
        }
        return finalPath
    }

    override fun abort() {
        // Keep the partial file (the next attempt resumes)
        file?.let { runCatching { fclose(it) } }
        file = null
    }

    private fun uniqueTarget(dest: String): String {
        val dir = dest.substringBeforeLast('/')
        val name = FileNames.firstFree(dest.substringAfterLast('/')) { fm.fileExistsAtPath("$dir/$it") }
        return "$dir/$name"
    }
}

/**
 * iOS save location. root is decided by Swift's SaveDestinationStore (Default / Custom).
 * Returns Unavailable if the custom location isn't selected or accessible; the UI resolves it with a dialog.
 */
class IosDownloadTargets(
    private val rootPathProvider: () -> String?,
    private val unavailableReason: () -> String,
) : DownloadTargets {
    override fun create(peerName: String, remotePath: String): SinkResult {
        val root = rootPathProvider() ?: return SinkResult.Unavailable(unavailableReason())
        val segments = remotePath.split('/').filter { it.isNotEmpty() }
        val dest = (listOf(root.trimEnd('/'), peerName) + segments).joinToString("/")
        return SinkResult.Ready(IosDownloadSink(dest))
    }
}

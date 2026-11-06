package com.example.peersync.proto

import java.io.File
import java.io.RandomAccessFile

/**
 * java.io.File-based DownloadSink (Android Default, desktop) for received files:
 *  - partial file is <dest>.peersync-part
 *  - on completion, rename to the final name ("name (1).ext" on a clash)
 */
class FileDownloadSink(private val dest: File) : DownloadSink {
    private val part = File(dest.path + FileServer.PART_SUFFIX)
    private var raf: RandomAccessFile? = null

    override fun partLength(): Long {
        dest.parentFile?.mkdirs()
        return if (part.exists()) part.length() else 0L
    }

    override fun prepare(start: Long) {
        dest.parentFile?.mkdirs()
        val r = RandomAccessFile(part, "rw")
        // Follow the server if it adjusted the offset (e.g. the source file shrank)
        r.setLength(start)
        r.seek(start)
        raf = r
    }

    override fun write(chunk: ByteArray) {
        raf!!.write(chunk)
    }

    override fun commit(): String {
        raf?.close()
        raf = null
        val finalFile = uniqueTarget(dest)
        if (!part.renameTo(finalFile)) {
            part.copyTo(finalFile, overwrite = true)
            part.delete()
        }
        return finalFile.path
    }

    override fun abort() {
        // Keep the partial file (the next attempt resumes)
        runCatching { raf?.close() }
        raf = null
    }

    private fun uniqueTarget(dest: File): File =
        File(dest.parentFile, FileNames.firstFree(dest.name) { File(dest.parentFile, it).exists() })
}

/** DownloadTargets saving to <root>/<peer name>/<peer-side path> (Android, desktop). */
class FileDownloadTargets(private val rootProvider: () -> File) : DownloadTargets {
    override fun create(peerName: String, remotePath: String): SinkResult {
        val dest = File(File(rootProvider(), peerName), remotePath.trimStart('/').replace('/', File.separatorChar))
        return SinkResult.Ready(FileDownloadSink(dest))
    }
}

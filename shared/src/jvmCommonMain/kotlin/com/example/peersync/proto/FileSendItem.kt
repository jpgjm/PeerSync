package com.example.peersync.proto

import java.io.File
import java.io.RandomAccessFile

/** SendItem for a java.io.File (desktop, tests; Android content URIs live in androidApp). */
class FileSendItem(private val file: File, override val relPath: String) : SendItem {
    override val size: Long = file.length()
    override val mtime: Long = file.lastModified()

    override fun open(): ReadHandle {
        val raf = RandomAccessFile(file, "r")
        return object : ReadHandle {
            override val length: Long = raf.length()
            override fun seek(pos: Long) = raf.seek(pos)
            override fun read(buf: ByteArray, off: Int, len: Int): Int = raf.read(buf, off, len)
            override fun close() = raf.close()
        }
    }

    companion object {
        /** Multiple files: relPath is just each file name. */
        fun ofFiles(files: List<File>): List<SendItem> =
            files.filter { it.isFile }.map { FileSendItem(it, it.name) }

        /**
         * Folder: expands contents recursively as "<folder name>/<relative path>" (empty folders aren't sent).
         * Symlinks are not followed.
         */
        fun ofDirectory(dir: File): List<SendItem> {
            val root = dir.absoluteFile
            val out = ArrayList<SendItem>()
            root.walkTopDown()
                .onEnter { d -> !java.nio.file.Files.isSymbolicLink(d.toPath()) }
                .forEach { f ->
                    if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                        val rel = f.relativeTo(root).invariantSeparatorsPath
                        out += FileSendItem(f, "${root.name}/$rel")
                    }
                }
            return out
        }
    }
}

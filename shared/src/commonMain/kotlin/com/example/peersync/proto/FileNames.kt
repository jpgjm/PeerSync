package com.example.peersync.proto

/**
 * "name (1).ext" numbering to avoid name clashes (shared since 0.2.25).
 *
 * Up to 0.2.24 this was written separately in 4 places: on save (JVM FileDownloadSink, iOS IosDownloadSink) and when building
 * the send list (Android AndroidSendItems, iOS SendPickers.swift), with inconsistent extension
 * detection (the JVM one used File.nameWithoutExtension, so ".bashrc" became " (1).bashrc").
 * The extension starts at the last '.' that isn't the first character (".bashrc" -> ".bashrc (1)", "a.tar.gz" -> "a.tar (1).gz").
 * From Swift call `FileNames.shared.numbered(name:n:)` (n is Int32).
 */
object FileNames {
    /** [name] with number [n] appended. */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    /** Counts up (1), (2), ... until [exists] is false. Returns the original name if it's free. */
    inline fun firstFree(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        var i = 1
        while (true) {
            val candidate = numbered(name, i)
            if (!exists(candidate)) return candidate
            i++
        }
    }
}

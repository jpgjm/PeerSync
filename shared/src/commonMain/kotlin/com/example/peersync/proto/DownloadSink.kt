package com.example.peersync.proto

/**
 * Write target for received files ("Send" from another device; the name dates from the shared-folder
 * downloads removed in 0.2.36). Implemented per platform
 * (Android: Download/PeerSync, desktop: Downloads\PeerSync, iOS: the "Save to" folder).
 *
 * Also handles the partial file (<dest>.peersync-part). All calls are synchronous on Dispatchers.IO.
 */
interface DownloadSink {
    /** Length of the existing partial file, or 0. Used as the resume offset. */
    fun partLength(): Long

    /** Prepare to write: truncate the partial file to start and seek there. */
    fun prepare(start: Long)

    /** Append received bytes. */
    fun write(chunk: ByteArray)

    /**
     * Finish: move the partial file to its final name ("name (1).ext" on a name clash).
     * @return full path of the saved file (for display and MediaScanner)
     */
    fun commit(): String

    /** Abort. Keeps the partial file (the next attempt resumes where it stopped). */
    fun abort()
}

/** Result of creating a DownloadSink. */
sealed interface SinkResult {
    data class Ready(val sink: DownloadSink) : SinkResult

    /** Save location unusable (e.g. custom location not selected or not accessible). */
    data class Unavailable(val reason: String) : SinkResult
}

/** Creates a write target from (peer name, relative path sent by the peer). Implemented per platform. */
interface DownloadTargets {
    fun create(peerName: String, remotePath: String): SinkResult
}

package com.example.peersync.proto

/**
 * One file sent by "Send". Created by the platform picker
 * (Android: content URI / desktop: java.io.File / iOS: security-scoped path or temp file).
 * open() is called on Dispatchers.IO.
 */
interface SendItem {
    /** Relative path created on the other side ("/"-separated; "<folder name>/..." when sending a folder). */
    val relPath: String
    val size: Long
    val mtime: Long
    fun open(): ReadHandle

    val fileName: String get() = relPath.substringAfterLast('/')
}

/**
 * File handle for reading a file to send. Called synchronously on Dispatchers.IO.
 * (Moved here from ShareSource.kt in 0.2.36 when the shared folder was removed.)
 */
interface ReadHandle {
    val length: Long
    fun seek(pos: Long)

    /** Bytes read; -1 or 0 at end of file. */
    fun read(buf: ByteArray, off: Int, len: Int): Int
    fun close()
}

/**
 * Receiver-side (server) handler for "Send". FileServer gets one per connection (per peer).
 * Implemented in data/Incoming.kt (consent, save location, progress).
 */
interface PushHandler {
    /** Offer. Throws ProtocolException if refused (e.g. over a limit). texts are clipboard texts. */
    suspend fun offer(items: List<OfferItem>, texts: List<String>): OfferResponse

    /** Polls for consent. ProtocolException for an unknown token. */
    suspend fun offerStatus(token: String): OfferResponse

    /** Write target for a relPath in an accepted token. ProtocolException if invalid. */
    suspend fun sinkFor(token: String, relPath: String, size: Long): DownloadSink

    /** Progress. Returning false aborts receiving (cancelled on the receiving side). */
    fun onPutProgress(token: String, relPath: String, done: Long, size: Long): Boolean
    fun onPutDone(token: String, relPath: String, savedPath: String)
    fun onPutFailed(token: String, relPath: String, reason: String)
}

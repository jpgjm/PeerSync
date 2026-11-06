package com.example.peersync.proto

import com.example.peersync.AppInfo
import com.example.peersync.platform.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * Server side of the wire protocol (one request per stream): "Send" (OFFER2 / OFFER_STATUS / PUT),
 * pairing (PAIR / PAIR_STATUS / UNPAIR) and the presence check (PING).
 * 0.2.36: browsing and fetching the shared folder (LIST / GET) were removed with the shared folder.
 */
class FileServer(
    private val log: (String) -> Unit = {},
    /** "Send" receiver (per peer). Returning null rejects OFFER / PUT. */
    private val pushHandlerProvider: (peerId: String) -> PushHandler? = { null },
    /**
     * Whether the peer is on the allow list (0.2.14). If false, ops other than PAIR / PAIR_STATUS / UNPAIR
     * get ERR_NOT_PAIRED. The test default is "always registered".
     */
    private val isPaired: (peerId: String) -> Boolean = { true },
    /** Pairing receiver (per peer). Returning null rejects PAIR / PING. */
    private val pairHandlerProvider: (peerId: String) -> PairHandler? = { null },
    /**
     * Whether this device has unpaired the peer (0.2.21). If true, unknown peers get
     * ERR_UNPAIRED instead of ERR_NOT_PAIRED (so the peer removes us too).
     */
    private val isUnpaired: (peerId: String) -> Boolean = { false },
) {
    suspend fun handle(input: FrameIn, output: FrameOut, peerId: String = "") {
        try {
            handleInner(input, output, peerId)
        } finally {
            input.dispose()
            output.dispose()
        }
    }

    private suspend fun handleInner(input: FrameIn, output: FrameOut, peerId: String) {
        val req = try {
            Codec.decodeRequest(Decoder(input))
        } catch (e: Exception) {
            log("bad request: ${e.message}")
            output.write(Codec.encodeError("bad request: ${e.message}"))
            output.finish()
            return
        }
        // PING runs every 30 s per peer while Device Hub is open, so it isn't logged here (Pairing logs changes)
        if (req !is Request.Ping) {
            log(if (req is Request.Offer) "request Offer(${req.items.size} items, ${req.texts.size} texts)" else "request $req")
        }
        try {
            // Unknown peers may only send pairing requests (and poll them)
            // Unpair notices are accepted regardless of registration (the receiver checks)
            if (req !is Request.Pair && req !is Request.PairStatus && req !is Request.Unpair && !isPaired(peerId)) {
                throw ProtocolException(if (isUnpaired(peerId)) Wire.ERR_UNPAIRED else Wire.ERR_NOT_PAIRED)
            }
            when (req) {
                is Request.Offer ->
                    output.write(Codec.encodeOfferResponse(push(peerId).offer(req.items, req.texts)))
                is Request.OfferStatus ->
                    output.write(Codec.encodeOfferResponse(push(peerId).offerStatus(req.token)))
                is Request.Put -> {
                    receiveFile(req, input, output, push(peerId))
                    output.finish()
                    return
                }
                is Request.Pair ->
                    output.write(Codec.encodeOfferResponse(pair(peerId).request(req.name)))
                is Request.PairStatus ->
                    output.write(Codec.encodeOfferResponse(pair(peerId).status(req.token)))
                is Request.Unpair -> {
                    pair(peerId).unpair(req.name)
                    output.write(Codec.encodeOk())
                }
                is Request.Ping ->
                    output.write(Codec.encodePingResponse(pair(peerId).ping(req.name)))
            }
        } catch (e: ProtocolException) {
            log("request rejected: ${e.message}")
            output.write(Codec.encodeError(e.message ?: "error"))
        } catch (e: Exception) {
            log("request failed: ${e.message}")
            output.write(Codec.encodeError(e.message ?: "error"))
        }
        output.finish()
    }

    private fun push(peerId: String): PushHandler =
        pushHandlerProvider(peerId) ?: throw ProtocolException("This device doesn't support receiving.")

    private fun pair(peerId: String): PairHandler =
        pairHandlerProvider(peerId) ?: throw ProtocolException("This device doesn't support pairing requests.")

    /**
     * Receives a PUT. Replies with a header (bytes already held), receives raw bytes,
     * then writes a trailer (save result). If interrupted, the partial file is kept (next attempt resumes).
     */
    private suspend fun receiveFile(req: Request.Put, input: FrameIn, output: FrameOut, handler: PushHandler) {
        val sink = handler.sinkFor(req.token, req.relPath, req.size)
        val have = withContext(ioDispatcher) { sink.partLength() }
        // Partial file larger than the sender's file = sender's file replaced. Start over
        val offset = if (have in 0L..req.size) have else 0L
        log("receive ${req.relPath} size=${req.size} offset=$offset")
        output.write(Codec.encodePutHeader(offset))
        try {
            withContext(ioDispatcher) { sink.prepare(offset) }
            var left = req.size - offset
            var done = offset
            if (!handler.onPutProgress(req.token, req.relPath, done, req.size)) {
                throw ProtocolException(MSG_CANCELLED_BY_RECEIVER)
            }
            while (left > 0) {
                val n = minOf(Wire.CHUNK.toLong(), left).toInt()
                val chunk = input.readExact(n)
                withContext(ioDispatcher) { sink.write(chunk) }
                left -= n
                done += n
                if (!handler.onPutProgress(req.token, req.relPath, done, req.size)) {
                    throw ProtocolException(MSG_CANCELLED_BY_RECEIVER)
                }
            }
            val saved = withContext(ioDispatcher) { sink.commit() }
            log("receive done ${req.relPath} -> $saved")
            handler.onPutDone(req.token, req.relPath, saved)
            output.write(Codec.encodeTrailerOk())
        } catch (t: Throwable) {
            withContext(ioDispatcher + kotlinx.coroutines.NonCancellable) { runCatching { sink.abort() } }
            val reason = t.message ?: t.toString()
            log("receive failed ${req.relPath}: $reason")
            handler.onPutFailed(req.token, req.relPath, reason)
            runCatching { output.write(Codec.encodeError(reason)) }
            throw t
        }
    }

    companion object {
        /** Error text when sending stops because the receiver cancelled (used in 2 places, 0.2.30). */
        private const val MSG_CANCELLED_BY_RECEIVER = "Cancelled by the receiver."

        /** Partial file suffix (moved to AppInfo in 0.2.30; this alias keeps callers compatible). */
        const val PART_SUFFIX = AppInfo.PART_SUFFIX
    }
}

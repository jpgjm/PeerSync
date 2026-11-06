package com.example.peersync.proto

import com.example.peersync.platform.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * Client side. openStream opens one new bidirectional stream.
 */
class RemoteClient(
    private val openStream: suspend () -> Pair<FrameOut, FrameIn>,
) {
    // ---- "Send" (push) side ----

    suspend fun offer(items: List<OfferItem>, texts: List<String> = emptyList()): OfferResponse {
        val (out, input) = openStream()
        try {
            out.write(Codec.encodeRequest(Request.Offer(items, texts)))
            out.finish()
            return Codec.readOfferResponse(Decoder(input))
        } finally {
            out.dispose()
            input.dispose()
        }
    }

    suspend fun offerStatus(token: String): OfferResponse {
        val (out, input) = openStream()
        try {
            out.write(Codec.encodeRequest(Request.OfferStatus(token)))
            out.finish()
            return Codec.readOfferResponse(Decoder(input))
        } finally {
            out.dispose()
            input.dispose()
        }
    }

    // ---- Pairing request (0.2.14) ----

    /** Requests pairing with this device's name. On PENDING, poll with pairStatus. */
    suspend fun pair(name: String): OfferResponse {
        val (out, input) = openStream()
        try {
            out.write(Codec.encodeRequest(Request.Pair(name)))
            out.finish()
            return Codec.readOfferResponse(Decoder(input))
        } finally {
            out.dispose()
            input.dispose()
        }
    }

    suspend fun pairStatus(token: String): OfferResponse {
        val (out, input) = openStream()
        try {
            out.write(Codec.encodeRequest(Request.PairStatus(token)))
            out.finish()
            return Codec.readOfferResponse(Decoder(input))
        } finally {
            out.dispose()
            input.dispose()
        }
    }

    /**
     * Presence check (0.2.36). Sends this device's name and returns the other device's current name.
     * RemoteException if the other device answered with an error (e.g. not paired / unpaired).
     */
    suspend fun ping(name: String): String {
        val (out, input) = openStream()
        try {
            out.write(Codec.encodeRequest(Request.Ping(name)))
            out.finish()
            return Codec.readPingResponse(Decoder(input))
        } finally {
            out.dispose()
            input.dispose()
        }
    }

    /** Notifies the other device of unpairing (0.2.21). */
    suspend fun unpair(name: String) {
        val (out, input) = openStream()
        try {
            out.write(Codec.encodeRequest(Request.Unpair(name)))
            out.finish()
            Codec.readOk(Decoder(input))
        } finally {
            out.dispose()
            input.dispose()
        }
    }

    /**
     * Sends item with an accepted token, skipping what the other device already has (partial file).
     * onProgress's done is the byte count on the other side (starts at offset when resuming).
     */
    suspend fun put(
        token: String,
        item: SendItem,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val (out, input) = openStream()
        try {
            out.write(Codec.encodeRequest(Request.Put(token, item.relPath, item.size, item.mtime)))
            val d = Decoder(input)
            val offset = Codec.readPutHeader(d)
            val start = if (offset in 0L..item.size) offset else 0L
            val handle = withContext(ioDispatcher) { item.open() }
            try {
                withContext(ioDispatcher) { handle.seek(start) }
                var done = start
                onProgress(done, item.size)
                var left = item.size - start
                val buf = ByteArray(Wire.CHUNK)
                while (left > 0) {
                    val want = minOf(Wire.CHUNK.toLong(), left).toInt()
                    val n = withContext(ioDispatcher) { handle.read(buf, 0, want) }
                    if (n <= 0) {
                        out.abort()
                        throw ProtocolException("File got smaller while sending: ${item.fileName}")
                    }
                    out.write(buf.copyOf(n))
                    left -= n
                    done += n
                    onProgress(done, item.size)
                }
            } finally {
                withContext(ioDispatcher) { handle.close() }
            }
            out.finish()
            Codec.readTrailer(d)
        } finally {
            out.dispose()
            input.dispose()
        }
    }
}

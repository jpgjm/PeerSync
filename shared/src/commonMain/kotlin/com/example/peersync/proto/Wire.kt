package com.example.peersync.proto

/**
 * PeerSync wire protocol v0 (ALPN: "peersync/0").
 *
 * 1 request = 1 bidirectional stream.
 *  Request:   u8 op + fields -> sender calls finish()
 *  Response:  u8 status(0=OK,1=ERR) + body
 *
 *  ops 1 (LIST) and 2 (GET) browsed and fetched the shared folder. Removed in 0.2.36 with the shared
 *  folder; they are unused and answered with "unknown op".
 *
 * "Send" (push) ops added in 0.2.5. Require the receiver's consent:
 *  OFFER2       : u32 count + (str relPath, i64 size, i64 mtime) * count
 *                 + u32 count + (i32 len + UTF-8 body) * count (0 if no texts)
 *                                  -> str token, u8 offerStatus (immediate; PENDING while awaiting consent)
 *                 Bodies use an i32 length, so str's (u16) 64 KiB limit doesn't apply.
 *                 op 3 (old OFFER without texts) was removed in 0.2.26 and is unused (no compatibility with old versions).
 *  OFFER_STATUS : str token        -> str token, u8 offerStatus (polled by the sender while awaiting consent)
 *  PUT          : str token, str relPath, i64 size, i64 mtime
 *                                  -> i64 offset (bytes the receiver already has)
 *                 ... sender writes raw bytes from offset, then finish()
 *                                  -> trailing u8 status(+str) trailer (save result)
 *
 * Pairing requests added in 0.2.14 (when one side adds, the other gets a dialog):
 *  PAIR         : str name (requester's device name)
 *                                  -> str token, u8 pairStatus (immediate; PENDING while awaiting consent,
 *                                    ACCEPTED at once if the peer already has this device)
 *  PAIR_STATUS  : str token        -> str token, u8 pairStatus (polled by the requester while awaiting consent)
 *                 Connections from unknown peers are accepted, but ops other than PAIR / PAIR_STATUS
 *                 get ERR_NOT_PAIRED ("not paired") (up to 0.2.13 the connection itself was closed with 403).
 *
 * Unpair notice added in 0.2.21:
 *  UNPAIR       : str name (unpairing device's name)  -> u8 status(OK)
 *                 The receiver removes the sender too (0.2.22). Accepted regardless of registration.
 *                 The unpairing side records the peer ID as unpaired, and ops other than PAIR from that peer
 *                 get ERR_UNPAIRED ("unpaired") instead of ERR_NOT_PAIRED (fallback when UNPAIR didn't arrive
 *                 because the peer was offline; the peer sees this on its next request and removes us too).
 *
 * Presence check added in 0.2.36 (Device Hub shows whether each paired device is online):
 *  PING         : str name (sender's device name) -> u8 status(OK) + str name (receiver's device name)
 *                 Paired devices only (others get ERR_NOT_PAIRED / ERR_UNPAIRED). Both sides update the
 *                 name they keep for the other, so a renamed device shows its new name.
 *
 * All numbers are big-endian. str is u16 length + UTF-8.
 * No compatibility with old versions (0.2.26; users with an old peer are told it's incompatible).
 * Unknown ops get an "unknown op <n>" error.
 * Independent of iroh and platforms, so it's tested in common tests.
 */
object Wire {
    const val ALPN_STRING = "peersync/0"
    val ALPN: ByteArray = ALPN_STRING.encodeToByteArray()

    // 1 and 2 are unused (LIST / GET of the shared folder, removed in 0.2.36)
    // 3 is unused (old OFFER, removed in 0.2.26)
    const val OP_OFFER_STATUS = 4
    const val OP_PUT = 5
    const val OP_OFFER2 = 6
    const val OP_PAIR = 7
    const val OP_PAIR_STATUS = 8
    const val OP_UNPAIR = 9
    const val OP_PING = 10

    const val STATUS_OK = 0
    const val STATUS_ERR = 1

    /** Consent state in OFFER / OFFER_STATUS responses. */
    const val OFFER_PENDING = 0
    const val OFFER_ACCEPTED = 1
    const val OFFER_REJECTED = 2

    /** Consent state in PAIR / PAIR_STATUS responses (same values as OFFER_*). */
    const val PAIR_PENDING = OFFER_PENDING
    const val PAIR_ACCEPTED = OFFER_ACCEPTED
    const val PAIR_REJECTED = OFFER_REJECTED

    /** Error text when an unknown peer sends an op other than PAIR (the requester then requests automatically). */
    const val ERR_NOT_PAIRED = "not paired"

    /** Error text when the peer has unpaired this device (0.2.21; since 0.2.22 the receiver removes the peer too). */
    const val ERR_UNPAIRED = "unpaired"

    /** Max length (characters) of the device name sent with PAIR / UNPAIR / PING. */
    const val MAX_NAME_CHARS = 64

    const val CHUNK = 256 * 1024
    /** Max items / texts in one OFFER2. */
    const val MAX_ENTRIES = 200_000
}

/** Receiving end of a stream. Implemented by iroh RecvStream or a test pipe. */
interface FrameIn {
    suspend fun readExact(n: Int): ByteArray

    /** Releases native resources (iOS FFI handles; JVM relies on GC, so the default does nothing). */
    fun dispose() {}
}

/** Sending end of a stream. */
interface FrameOut {
    suspend fun write(bytes: ByteArray)
    suspend fun finish()
    /** Ends the peer's read with an error when sending can't continue. */
    suspend fun abort()

    /** Releases native resources (iOS FFI handles; JVM relies on GC, so the default does nothing). */
    fun dispose() {}
}

class ProtocolException(message: String) : Exception(message)
class RemoteException(message: String) : Exception(message)

/** Builds big-endian bytes (pure Kotlin, no java.io). */
class Encoder {
    private val buf = ArrayList<Byte>(64)

    fun u8(v: Int) = apply { buf.add((v and 0xFF).toByte()) }

    fun i32(v: Int) = apply {
        buf.add(((v ushr 24) and 0xFF).toByte())
        buf.add(((v ushr 16) and 0xFF).toByte())
        buf.add(((v ushr 8) and 0xFF).toByte())
        buf.add((v and 0xFF).toByte())
    }

    fun i64(v: Long) = apply {
        for (shift in 56 downTo 0 step 8) {
            buf.add(((v ushr shift) and 0xFF).toByte())
        }
    }

    /** i32 length + raw bytes (for long text bodies; avoids str's u16 limit). */
    fun blob(b: ByteArray) = apply {
        i32(b.size)
        b.forEach { buf.add(it) }
    }

    fun str(s: String) = apply {
        val b = s.encodeToByteArray()
        if (b.size > 0xFFFF) throw ProtocolException("string too long")
        buf.add(((b.size ushr 8) and 0xFF).toByte())
        buf.add((b.size and 0xFF).toByte())
        b.forEach { buf.add(it) }
    }

    fun bytes(): ByteArray = buf.toByteArray()
}

/** Reads big-endian values from FrameIn. */
class Decoder(private val src: FrameIn) {
    suspend fun u8(): Int = src.readExact(1)[0].toInt() and 0xFF

    suspend fun i32(): Int {
        val b = src.readExact(4)
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    suspend fun i64(): Long {
        val b = src.readExact(8)
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (b[i].toLong() and 0xFF)
        }
        return v
    }

    suspend fun blob(): ByteArray {
        val len = i32()
        if (len < 0) throw ProtocolException("bad blob length $len")
        return if (len == 0) ByteArray(0) else src.readExact(len)
    }

    suspend fun str(): String {
        val hi = u8()
        val lo = u8()
        val len = (hi shl 8) or lo
        return if (len == 0) "" else src.readExact(len).decodeToString()
    }
}

sealed interface Request {
    /**
     * "Send" offer. items are all files to send (folders already expanded into relPaths).
     * texts are passed as is (the receiver puts them on the clipboard, not in files).
     */
    data class Offer(val items: List<OfferItem>, val texts: List<String> = emptyList()) : Request

    /** Polls for consent. */
    data class OfferStatus(val token: String) : Request

    /** Sends one file from an accepted offer (token). */
    data class Put(val token: String, val relPath: String, val size: Long, val mtime: Long) : Request

    /** Pairing request (0.2.14). name is the requester's device name (used in the peer's dialog and as the saved name). */
    data class Pair(val name: String) : Request

    /** Polls a pairing request for consent. */
    data class PairStatus(val token: String) : Request

    /** Unpair notice (0.2.21). name is the unpairing device's name. */
    data class Unpair(val name: String) : Request

    /** Presence check (0.2.36). name is the sender's current device name. */
    data class Ping(val name: String) : Request
}

/** One file to send. relPath is "/"-separated ("<folder name>/..." when sending a folder). */
data class OfferItem(val relPath: String, val size: Long, val mtime: Long)

/** OFFER / OFFER_STATUS response (same shape for PAIR / PAIR_STATUS). */
data class OfferResponse(val token: String, val status: Int)

object Codec {
    fun encodeRequest(r: Request): ByteArray = when (r) {
        is Request.Offer -> {
            if (r.items.size > Wire.MAX_ENTRIES) throw ProtocolException("too many items")
            if (r.texts.size > Wire.MAX_ENTRIES) throw ProtocolException("too many texts")
            val e = Encoder().u8(Wire.OP_OFFER2).i32(r.items.size)
            for (x in r.items) e.str(x.relPath).i64(x.size).i64(x.mtime)
            e.i32(r.texts.size)
            for (t in r.texts) e.blob(t.encodeToByteArray())
            e.bytes()
        }
        is Request.OfferStatus -> Encoder().u8(Wire.OP_OFFER_STATUS).str(r.token).bytes()
        is Request.Put -> Encoder().u8(Wire.OP_PUT).str(r.token).str(r.relPath).i64(r.size).i64(r.mtime).bytes()
        is Request.Pair -> Encoder().u8(Wire.OP_PAIR).str(r.name.take(Wire.MAX_NAME_CHARS)).bytes()
        is Request.PairStatus -> Encoder().u8(Wire.OP_PAIR_STATUS).str(r.token).bytes()
        is Request.Unpair -> Encoder().u8(Wire.OP_UNPAIR).str(r.name.take(Wire.MAX_NAME_CHARS)).bytes()
        is Request.Ping -> Encoder().u8(Wire.OP_PING).str(r.name.take(Wire.MAX_NAME_CHARS)).bytes()
    }

    suspend fun decodeRequest(d: Decoder): Request = when (val op = d.u8()) {
        Wire.OP_OFFER2 -> {
            val n = d.i32()
            if (n < 0 || n > Wire.MAX_ENTRIES) throw ProtocolException("bad item count $n")
            val items = List(n) { OfferItem(d.str(), d.i64(), d.i64()) }
            val t = d.i32()
            if (t < 0 || t > Wire.MAX_ENTRIES) throw ProtocolException("bad text count $t")
            Request.Offer(items, List(t) { d.blob().decodeToString() })
        }
        Wire.OP_OFFER_STATUS -> Request.OfferStatus(d.str())
        Wire.OP_PUT -> Request.Put(d.str(), d.str(), d.i64(), d.i64())
        Wire.OP_PAIR -> Request.Pair(d.str().take(Wire.MAX_NAME_CHARS))
        Wire.OP_PAIR_STATUS -> Request.PairStatus(d.str())
        Wire.OP_UNPAIR -> Request.Unpair(d.str().take(Wire.MAX_NAME_CHARS))
        Wire.OP_PING -> Request.Ping(d.str().take(Wire.MAX_NAME_CHARS))
        else -> throw ProtocolException("unknown op $op")
    }

    // ---- Send (push) ops ----

    fun encodeOfferResponse(r: OfferResponse): ByteArray =
        Encoder().u8(Wire.STATUS_OK).str(r.token).u8(r.status).bytes()

    suspend fun readOfferResponse(d: Decoder): OfferResponse {
        expectOk(d)
        val token = d.str()
        val status = d.u8()
        if (status !in Wire.OFFER_PENDING..Wire.OFFER_REJECTED) throw ProtocolException("bad offer status $status")
        return OfferResponse(token, status)
    }

    /** PUT response header: bytes the receiver already has (send from here). */
    fun encodePutHeader(offset: Long): ByteArray = Encoder().u8(Wire.STATUS_OK).i64(offset).bytes()

    suspend fun readPutHeader(d: Decoder): Long {
        expectOk(d)
        val offset = d.i64()
        if (offset < 0) throw ProtocolException("bad offset $offset")
        return offset
    }

    /** PUT trailer (save result). */
    fun encodeTrailerOk(): ByteArray = Encoder().u8(Wire.STATUS_OK).bytes()

    suspend fun readTrailer(d: Decoder) = expectOk(d)

    /** OK response without a body (UNPAIR). */
    fun encodeOk(): ByteArray = Encoder().u8(Wire.STATUS_OK).bytes()

    suspend fun readOk(d: Decoder) = expectOk(d)

    /** PING response: OK + the receiver's device name (0.2.36). */
    fun encodePingResponse(name: String): ByteArray =
        Encoder().u8(Wire.STATUS_OK).str(name.take(Wire.MAX_NAME_CHARS)).bytes()

    suspend fun readPingResponse(d: Decoder): String {
        expectOk(d)
        return d.str().take(Wire.MAX_NAME_CHARS)
    }

    fun encodeError(message: String): ByteArray =
        Encoder().u8(Wire.STATUS_ERR).str(message.take(1000)).bytes()

    private suspend fun expectOk(d: Decoder) {
        when (val s = d.u8()) {
            Wire.STATUS_OK -> Unit
            Wire.STATUS_ERR -> throw RemoteException(d.str())
            else -> throw ProtocolException("unknown status $s")
        }
    }
}

package com.example.peersync.proto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tests pairing requests (PAIR / PAIR_STATUS) and handling of unknown peers over in-memory pipes (0.2.14). */
class PairProtocolTest {

    private class Pipe : FrameIn, FrameOut {
        private val ch = Channel<ByteArray>(Channel.UNLIMITED)
        private var buf = ByteArray(0)
        private var pos = 0

        override suspend fun write(bytes: ByteArray) { ch.send(bytes.copyOf()) }
        override suspend fun finish() { ch.close() }
        override suspend fun abort() { ch.close(IllegalStateException("reset")) }
        override fun dispose() {}

        override suspend fun readExact(n: Int): ByteArray {
            val out = ByteArray(n)
            var filled = 0
            while (filled < n) {
                if (pos == buf.size) {
                    buf = ch.receiveCatching().getOrNull() ?: throw IllegalStateException("stream ended early")
                    pos = 0
                    continue
                }
                val k = minOf(n - filled, buf.size - pos)
                System.arraycopy(buf, pos, out, filled, k)
                pos += k
                filled += k
            }
            return out
        }
    }

    /** Minimal receiver: records requests; status is changed from outside. */
    private class Handler : PairHandler {
        var requestedName: String? = null
        var pingedBy: String? = null
        var status = Wire.PAIR_PENDING

        override suspend fun ping(name: String): String {
            pingedBy = name
            return "Pixel 9 Pro XL"
        }

        override suspend fun request(name: String): OfferResponse {
            requestedName = name
            return OfferResponse("ptok", status)
        }

        override suspend fun status(token: String): OfferResponse {
            if (token != "ptok") throw ProtocolException("unknown token")
            return OfferResponse(token, status)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    private fun client(server: FileServer): RemoteClient = RemoteClient {
        val toServer = Pipe()
        val toClient = Pipe()
        scope.launch { server.handle(toServer, toClient, "peer") }
        toServer to toClient
    }

    @Test
    fun pairRequestThenPollUntilAccepted() = runBlocking {
        val handler = Handler()
        val server = FileServer(isPaired = { false }, pairHandlerProvider = { handler })
        val c = client(server)
        val first = c.pair("Gerät-Ä")
        assertEquals("Gerät-Ä", handler.requestedName)
        assertEquals(Wire.PAIR_PENDING, first.status)
        assertEquals("ptok", first.token)
        assertEquals(Wire.PAIR_PENDING, c.pairStatus("ptok").status)
        handler.status = Wire.PAIR_ACCEPTED
        assertEquals(Wire.PAIR_ACCEPTED, c.pairStatus("ptok").status)
    }

    @Test
    fun unpairedPeerGetsNotPairedForOtherOps() = runBlocking {
        val server = FileServer(isPaired = { false }, pairHandlerProvider = { Handler() })
        val c = client(server)
        val e = assertFailsWith<RemoteException> { c.ping("me") }
        assertEquals(Wire.ERR_NOT_PAIRED, e.message)
        // only pairing requests get through
        assertEquals(Wire.PAIR_PENDING, c.pair("Gerät-Ä").status)
    }

    @Test
    fun pingReturnsNameBothWays() = runBlocking {
        // 0.2.36: PING carries the sender's name and returns the receiver's
        val handler = Handler()
        val c = client(FileServer(isPaired = { true }, pairHandlerProvider = { handler }))
        assertEquals("Pixel 9 Pro XL", c.ping("Gerät-Ä"))
        assertEquals("Gerät-Ä", handler.pingedBy)
        // name capped on the wire
        c.ping("y".repeat(Wire.MAX_NAME_CHARS + 9))
        assertEquals(Wire.MAX_NAME_CHARS, handler.pingedBy?.length)
    }

    @Test
    fun removedSharedFolderOpsAreUnknown() = runBlocking {
        // 0.2.36: ops 1 (LIST) and 2 (GET) were removed with the shared folder
        val server = FileServer(isPaired = { true })
        for (op in listOf(1, 2)) {
            val toServer = Pipe()
            val toClient = Pipe()
            toServer.write(Encoder().u8(op).str("").bytes())
            toServer.finish()
            server.handle(toServer, toClient, "peer")
            assertEquals(Wire.STATUS_ERR, Decoder(toClient).u8())
            assertEquals("bad request: unknown op $op", Decoder(toClient).str())
        }
    }

    @Test
    fun pairNameIsCappedOnTheWire() = runBlocking {
        val handler = Handler()
        val server = FileServer(isPaired = { false }, pairHandlerProvider = { handler })
        val c = client(server)
        c.pair("x".repeat(Wire.MAX_NAME_CHARS + 50))
        assertEquals(Wire.MAX_NAME_CHARS, handler.requestedName?.length)
    }
}

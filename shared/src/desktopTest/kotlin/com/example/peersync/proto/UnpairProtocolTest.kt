package com.example.peersync.proto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tests unpairing (UNPAIR) and the "unpaired" reply (ERR_UNPAIRED) over in-memory pipes (0.2.21). */
class UnpairProtocolTest {

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

    private class Handler : PairHandler {
        val unpairedBy = mutableListOf<String>()
        override suspend fun request(name: String) = OfferResponse("t", Wire.PAIR_PENDING)
        override suspend fun status(token: String) = OfferResponse(token, Wire.PAIR_PENDING)
        override suspend fun unpair(name: String) { unpairedBy += name }
        override suspend fun ping(name: String) = "server"
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    private fun client(server: FileServer): RemoteClient = RemoteClient {
        val toServer = Pipe()
        val toClient = Pipe()
        scope.launch { server.handle(toServer, toClient, "peer") }
        toServer to toClient
    }

    @Test
    fun unpairReachesHandlerWhenPaired() = runBlocking {
        val h = Handler()
        val c = client(FileServer(isPaired = { true }, pairHandlerProvider = { h }))
        c.unpair("Gerät-Ä")
        assertEquals(listOf("Gerät-Ä"), h.unpairedBy)
    }

    @Test
    fun unpairIsAcceptedEvenWhenNotPaired() = runBlocking {
        // What to do with it (e.g. ignore) is up to PairHandler. FileServer does not return ERR_NOT_PAIRED
        val h = Handler()
        val c = client(FileServer(isPaired = { false }, pairHandlerProvider = { h }))
        c.unpair("Gerät-Ä")
        assertEquals(1, h.unpairedBy.size)
    }

    @Test
    fun unpairedPeerGetsUnpairedError() = runBlocking {
        val c = client(FileServer(isPaired = { false }, pairHandlerProvider = { Handler() }, isUnpaired = { true }))
        val e = assertFailsWith<RemoteException> { c.ping("me") }
        assertEquals(Wire.ERR_UNPAIRED, e.message)
        // pairing requests still go through
        assertEquals(Wire.PAIR_PENDING, c.pair("Gerät-Ä").status)
    }

    @Test
    fun neverPairedPeerStillGetsNotPaired() = runBlocking {
        val c = client(FileServer(isPaired = { false }, pairHandlerProvider = { Handler() }, isUnpaired = { false }))
        val e = assertFailsWith<RemoteException> { c.ping("me") }
        assertEquals(Wire.ERR_NOT_PAIRED, e.message)
    }

    @Test
    fun unpairNameIsCappedOnTheWire() = runBlocking {
        val h = Handler()
        val c = client(FileServer(isPaired = { true }, pairHandlerProvider = { h }))
        c.unpair("x".repeat(Wire.MAX_NAME_CHARS + 10))
        assertEquals(Wire.MAX_NAME_CHARS, h.unpairedBy.single().length)
    }
}

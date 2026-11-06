package com.example.peersync.proto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Tests "Send" (OFFER / OFFER_STATUS / PUT) over in-memory pipes. */
class PushProtocolTest {

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

    /** Minimal receiver: fixed token, instant consent, saves to outDir. */
    private inner class Handler : PushHandler {
        var autoAccept = true
        var status = Wire.OFFER_PENDING
        var offered: List<OfferItem> = emptyList()
        var offeredTexts: List<String> = emptyList()
        val progress = mutableListOf<Long>()
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()

        override suspend fun offer(items: List<OfferItem>, texts: List<String>): OfferResponse {
            offered = items
            offeredTexts = texts
            status = if (autoAccept) Wire.OFFER_ACCEPTED else Wire.OFFER_PENDING
            return OfferResponse("tok", status)
        }

        override suspend fun offerStatus(token: String): OfferResponse {
            if (token != "tok") throw ProtocolException("unknown token")
            return OfferResponse(token, status)
        }

        override suspend fun sinkFor(token: String, relPath: String, size: Long): DownloadSink {
            if (token != "tok" || status != Wire.OFFER_ACCEPTED) throw ProtocolException("not accepted")
            if (offered.none { it.relPath == relPath }) throw ProtocolException("not in offer: $relPath")
            return FileDownloadSink(File(outDir, relPath.replace('/', File.separatorChar)))
        }

        override fun onPutProgress(token: String, relPath: String, done: Long, size: Long): Boolean {
            progress += done
            return true
        }

        override fun onPutDone(token: String, relPath: String, savedPath: String) { done += savedPath }
        override fun onPutFailed(token: String, relPath: String, reason: String) { failed += reason }
    }

    private lateinit var srcDir: File
    private lateinit var outDir: File
    private lateinit var handler: Handler
    private lateinit var client: RemoteClient
    private val scope = CoroutineScope(Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        srcDir = Files.createTempDirectory("src").toFile()
        outDir = Files.createTempDirectory("out").toFile()
        handler = Handler()
        val server = FileServer(pushHandlerProvider = { handler })
        client = RemoteClient {
            val toServer = Pipe()
            val toClient = Pipe()
            scope.launch { server.handle(toServer, toClient, "peer") }
            toServer to toClient
        }
    }

    private fun src(name: String, data: ByteArray): File = File(srcDir, name).also {
        it.parentFile.mkdirs()
        it.writeBytes(data)
    }

    @Test
    fun offerThenPutWholeFile() = runBlocking {
        val data = Random(1).nextBytes(Wire.CHUNK * 2 + 55)
        val f = src("a.bin", data)
        val items = FileSendItem.ofFiles(listOf(f))
        val resp = client.offer(items.map { OfferItem(it.relPath, it.size, it.mtime) })
        assertEquals(Wire.OFFER_ACCEPTED, resp.status)
        var last = -1L
        client.put(resp.token, items[0]) { done, total -> last = done; assertEquals(data.size.toLong(), total) }
        assertEquals(data.size.toLong(), last)
        assertContentEquals(data, File(outDir, "a.bin").readBytes())
        assertEquals(1, handler.done.size)
        assertTrue(!File(outDir, "a.bin" + FileServer.PART_SUFFIX).exists())
    }

    @Test
    fun putResumesFromReceiverPart() = runBlocking {
        val data = Random(2).nextBytes(Wire.CHUNK + 999)
        val f = src("r.bin", data)
        val item = FileSendItem(f, "r.bin")
        client.offer(listOf(OfferItem(item.relPath, item.size, item.mtime)))
        File(outDir, "r.bin" + FileServer.PART_SUFFIX).writeBytes(data.copyOf(70_000))
        var first = -1L
        client.put("tok", item) { done, _ -> if (first < 0) first = done }
        assertEquals(70_000L, first)
        assertContentEquals(data, File(outDir, "r.bin").readBytes())
    }

    @Test
    fun folderItemsKeepRelativePaths() = runBlocking {
        val dir = File(srcDir, "Photos").also { it.mkdirs() }
        File(dir, "2026/a.jpg").also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2))
        File(dir, "b.txt").writeText("b")
        File(dir, "empty").mkdirs()
        val items = FileSendItem.ofDirectory(dir)
        assertEquals(setOf("Photos/2026/a.jpg", "Photos/b.txt"), items.map { it.relPath }.toSet())
        client.offer(items.map { OfferItem(it.relPath, it.size, it.mtime) })
        for (it in items) client.put("tok", it)
        assertContentEquals(byteArrayOf(1, 2), File(outDir, "Photos/2026/a.jpg").readBytes())
        assertEquals("b", File(outDir, "Photos/b.txt").readText())
    }

    @Test
    fun pendingThenAcceptedViaStatus() = runBlocking {
        handler.autoAccept = false
        val f = src("p.bin", byteArrayOf(7))
        val item = FileSendItem(f, "p.bin")
        val resp = client.offer(listOf(OfferItem(item.relPath, item.size, item.mtime)))
        assertEquals(Wire.OFFER_PENDING, resp.status)
        assertEquals(Wire.OFFER_PENDING, client.offerStatus("tok").status)
        handler.status = Wire.OFFER_ACCEPTED
        assertEquals(Wire.OFFER_ACCEPTED, client.offerStatus("tok").status)
        client.put("tok", item)
        assertContentEquals(byteArrayOf(7), File(outDir, "p.bin").readBytes())
    }

    @Test
    fun putNotInOfferIsRejected() = runBlocking {
        val f = src("x.bin", byteArrayOf(1))
        client.offer(listOf(OfferItem("x.bin", 1, 0)))
        try {
            client.put("tok", FileSendItem(f, "other.bin"))
            fail("should reject")
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains("not in offer"))
        }
    }

    @Test
    fun serverWithoutPushHandlerRejectsOffer() = runBlocking {
        val server = FileServer() // no "Send" receiver
        val c = RemoteClient {
            val toServer = Pipe()
            val toClient = Pipe()
            scope.launch { server.handle(toServer, toClient, "peer") }
            toServer to toClient
        }
        try {
            c.offer(listOf(OfferItem("a", 1, 0)))
            fail()
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains("support"))
        }
    }

    @Test
    fun emptyFilePut() = runBlocking {
        val f = src("e", ByteArray(0))
        val item = FileSendItem(f, "e")
        client.offer(listOf(OfferItem("e", 0, 0)))
        client.put("tok", item)
        assertEquals(0L, File(outDir, "e").length())
    }

    @Test
    fun textOnlyOfferUsesOffer2AndCarriesLongText() = runBlocking {
        // text longer than the str (u16) limit of 64 KiB also goes through
        val long = buildString { repeat(20_000) { append("€✓★☂♫") } } // 100,000 chars (300,000 bytes)
        val resp = client.offer(emptyList(), listOf(long, "zweite Ü"))
        assertEquals(Wire.OFFER_ACCEPTED, resp.status)
        assertEquals(listOf(long, "zweite Ü"), handler.offeredTexts)
        assertTrue(handler.offered.isEmpty())
    }

    @Test
    fun offerAlwaysUsesOffer2() {
        // 0.2.26: always OFFER2, with or without text (the old OFFER, op 3, is retired)
        val bytes = Codec.encodeRequest(Request.Offer(listOf(OfferItem("a", 1, 0))))
        assertEquals(Wire.OP_OFFER2, bytes[0].toInt())
        val bytes2 = Codec.encodeRequest(Request.Offer(listOf(OfferItem("a", 1, 0)), listOf("t")))
        assertEquals(Wire.OP_OFFER2, bytes2[0].toInt())
    }

    @Test
    fun retiredOfferOpIsUnknown() = runBlocking {
        val pipe = Pipe()
        pipe.write(byteArrayOf(3, 0, 0, 0, 0))
        pipe.finish()
        try {
            Codec.decodeRequest(Decoder(pipe))
            fail()
        } catch (e: ProtocolException) {
            assertEquals("unknown op 3", e.message)
        }
    }

    @Test
    fun offerRoundTripEncoding() = runBlocking {
        val items = listOf(OfferItem("Bilder-café/Saber&Nero.jpg", 123L, 456L), OfferItem("b", 0L, 0L))
        for (texts in listOf(emptyList(), listOf("Grüße\nZeile 2 ✓", ""))) {
            val bytes = Codec.encodeRequest(Request.Offer(items, texts))
            val pipe = Pipe()
            pipe.write(bytes)
            pipe.finish()
            val decoded = Codec.decodeRequest(Decoder(pipe))
            assertEquals(Request.Offer(items, texts), decoded)
        }
    }
}

package com.example.peersync.net

import com.example.peersync.proto.FrameIn
import com.example.peersync.proto.FrameOut
import com.example.peersync.proto.Wire
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.RecvStream
import computer.iroh.RelayMap
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import computer.iroh.SendStream
import computer.iroh.presetN0

/**
 * PsEndpoint implementation shared by Android and desktop.
 * Uses the official iroh Kotlin bindings computer.iroh:iroh 1.2.0 (UniFFI/JNA).
 * On Android, computer.iroh:iroh-android provides libiroh_ffi.so for each ABI.
 * The iroh-specific part of the old Android net/IrohNode.kt, moved as is.
 */
private class IrohIn(private val r: RecvStream) : FrameIn {
    override suspend fun readExact(n: Int): ByteArray = r.readExact(n.toUInt())
}

private class IrohOut(private val s: SendStream) : FrameOut {
    override suspend fun write(bytes: ByteArray) = s.writeAll(bytes)
    override suspend fun finish() = s.finish()
    override suspend fun abort() = s.reset(1uL)
}

private class JvmPsConnection(
    private val conn: Connection,
    override val remoteId: String,
) : PsConnection {

    override suspend fun openBi(): Pair<FrameOut, FrameIn> {
        val bi = conn.openBi()
        return IrohOut(bi.send()) to IrohIn(bi.recv())
    }

    override suspend fun acceptBi(): Pair<FrameIn, FrameOut>? {
        val bi = try {
            conn.acceptBi()
        } catch (_: Throwable) {
            return null // connection closed
        }
        return IrohIn(bi.recv()) to IrohOut(bi.send())
    }

    override fun isClosed(): Boolean = conn.closeReason() != null

    override fun paths(): List<PathInfo>? {
        val paths = runCatching { conn.paths() }.getOrNull() ?: return null
        return paths.map { p ->
            PathInfo(
                kind = when {
                    p.isRelay -> PathKind.Relay
                    p.isIp -> PathKind.Direct
                    else -> PathKind.Other
                },
                rttMs = p.rttMs.toLong(),
                selected = p.isSelected,
            )
        }
    }

    override suspend fun close(code: Long, reason: String) {
        conn.close(code, reason.encodeToByteArray())
    }
}

private class JvmPsEndpoint(private val ep: Endpoint) : PsEndpoint {

    override suspend fun waitOnline(timeoutMs: Long): Boolean {
        ep.online()
        return true
    }

    override fun homeRelayUrl(): String? = runCatching { ep.addr().relayUrl() }.getOrNull()

    override suspend fun accept(): PsConnection? {
        while (true) {
            val incoming = ep.acceptNext() ?: return null
            try {
                val accepting = incoming.accept()
                if (!accepting.alpn().contentEquals(Wire.ALPN)) continue
                val conn = accepting.connect()
                return JvmPsConnection(conn, conn.remoteId().toString())
            } catch (t: Throwable) {
                throw t
            }
        }
    }

    override suspend fun connect(peerId: String): PsConnection {
        val addr = EndpointAddr(EndpointId.fromString(peerId), null, emptyList())
        val conn = ep.connect(addr, Wire.ALPN)
        return JvmPsConnection(conn, peerId)
    }

    override suspend fun shutdown() {
        ep.shutdown()
    }
}

actual suspend fun bindPsEndpoint(secretKey: ByteArray, relayUrl: String?): PsEndpoint {
    val ep = Endpoint.bind(
        EndpointOptions(
            preset = presetN0(),
            secretKey = secretKey,
            alpns = listOf(Wire.ALPN),
            relayMode = if (relayUrl.isNullOrBlank()) null
            else RelayMode.custom(RelayMap.fromUrls(listOf(relayUrl))),
        ),
    )
    return JvmPsEndpoint(ep)
}

actual fun generateSecretKey(): ByteArray = SecretKey.generate().toBytes()

actual fun endpointIdFromSecret(secretKey: ByteArray): String =
    SecretKey.fromBytes(secretKey).public().toString()

package com.example.peersync.net

import com.example.peersync.AppInfo
import com.example.peersync.proto.FrameIn
import com.example.peersync.proto.FrameOut
import com.example.peersync.proto.ProtocolException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import com.example.peersync.log.PsLog
import com.example.peersync.platform.ioDispatcher
import com.example.peersync.platform.CallGate
import com.example.peersync.platform.epochMillis
import kotlinx.coroutines.withContext
import cnames.structs.PsConn
import cnames.structs.PsNode
import cnames.structs.PsRecv
import cnames.structs.PsSend
import peersync.iroh.ps_conn_accept_bi
import peersync.iroh.ps_conn_close
import peersync.iroh.ps_conn_free
import peersync.iroh.ps_conn_is_closed
import peersync.iroh.ps_conn_open_bi
import peersync.iroh.ps_conn_path_summary
import peersync.iroh.ps_conn_remote_id
import peersync.iroh.ps_id_from_secret
import peersync.iroh.ps_last_error
import peersync.iroh.ps_node_accept
import peersync.iroh.ps_node_connect
import peersync.iroh.ps_node_home_relay
import peersync.iroh.ps_node_close
import peersync.iroh.ps_node_free
import peersync.iroh.ps_node_start
import peersync.iroh.ps_node_wait_online
import peersync.iroh.ps_recv_free
import peersync.iroh.ps_recv_read_exact
import peersync.iroh.ps_secret_generate
import peersync.iroh.ps_send_finish
import peersync.iroh.ps_send_free
import peersync.iroh.ps_send_reset
import peersync.iroh.ps_send_write
import peersync.iroh.ps_string_free

/*
 * iOS PsEndpoint implementation. Calls our Rust FFI (native/peersync-iroh, iroh 1.x) via cinterop.
 * All FFI calls block, so they run on Dispatchers.IO.
 * ps_last_error is thread-local, so the call and error fetch happen in the same block (same thread).
 * Corresponds to the FFI wrapper in the old iOS Net/IrohNode.swift. Handles are released
 * explicitly via dispose()/close() (Kotlin/Native has no deinit).
 */

private const val TAG = "${AppInfo.NAME}/Endpoint"

@OptIn(ExperimentalForeignApi::class)
private fun takeString(p: CPointer<ByteVar>?): String? {
    if (p == null) return null
    val s = p.toKString()
    ps_string_free(p)
    return s
}

@OptIn(ExperimentalForeignApi::class)
private fun lastError(): String? = takeString(ps_last_error())

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private class IosIn(handle: CPointer<PsRecv>) : FrameIn {
    private val ref = AtomicReference<CPointer<PsRecv>?>(handle)

    override suspend fun readExact(n: Int): ByteArray {
        if (n < 0) throw ProtocolException("bad length $n")
        val buf = ByteArray(n)
        if (n == 0) return buf
        val h = ref.load() ?: throw ProtocolException("stream closed")
        return withContext(ioDispatcher) {
            val rc = buf.usePinned { pinned ->
                ps_recv_read_exact(h, pinned.addressOf(0).reinterpret(), n.convert())
            }
            if (rc != 0) throw ProtocolException(lastError() ?: "read_exact failed ($rc)")
            buf
        }
    }

    override fun dispose() {
        ref.exchange(null)?.let { ps_recv_free(it) }
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private class IosOut(handle: CPointer<PsSend>) : FrameOut {
    private val ref = AtomicReference<CPointer<PsSend>?>(handle)

    override suspend fun write(bytes: ByteArray) {
        val h = ref.load() ?: throw ProtocolException("stream closed")
        if (bytes.isEmpty()) return
        withContext(ioDispatcher) {
            val rc = bytes.usePinned { pinned ->
                ps_send_write(h, pinned.addressOf(0).reinterpret(), bytes.size.convert())
            }
            if (rc != 0) throw ProtocolException(lastError() ?: "write failed ($rc)")
        }
    }

    override suspend fun finish() {
        val h = ref.load() ?: throw ProtocolException("stream closed")
        withContext(ioDispatcher) {
            val rc = ps_send_finish(h)
            if (rc != 0) throw ProtocolException(lastError() ?: "finish failed ($rc)")
        }
    }

    override suspend fun abort() {
        val h = ref.load() ?: return
        withContext(ioDispatcher) { ps_send_reset(h, 1uL) }
    }

    override fun dispose() {
        ref.exchange(null)?.let { ps_send_free(it) }
    }
}

/**
 * Connection handle. 0.2.37: every call goes through a [CallGate], and close() is "close, wait for the calls
 * inside to return, then free" (up to 0.2.36 it freed right after closing while acceptBi / paths could still
 * be running on other threads: use-after-free).
 */
@OptIn(ExperimentalForeignApi::class)
private class IosPsConnection(
    private val h: CPointer<PsConn>,
    override val remoteId: String,
) : PsConnection {
    private val gate = CallGate()

    override suspend fun openBi(): Pair<FrameOut, FrameIn> = withContext(ioDispatcher) {
        gate.enter {
            memScoped {
                val send = allocPointerTo<PsSend>()
                val recv = allocPointerTo<PsRecv>()
                val rc = ps_conn_open_bi(h, send.ptr, recv.ptr)
                val s = send.value
                val r = recv.value
                if (rc < 0 || s == null || r == null) {
                    throw ProtocolException(lastError() ?: "Couldn't open a stream.")
                }
                Pair<FrameOut, FrameIn>(IosOut(s), IosIn(r))
            }
        } ?: throw ProtocolException("The connection is closed.")
    }

    override suspend fun acceptBi(): Pair<FrameIn, FrameOut>? = withContext(ioDispatcher) {
        gate.enter {
            memScoped {
                val send = allocPointerTo<PsSend>()
                val recv = allocPointerTo<PsRecv>()
                val rc = ps_conn_accept_bi(h, send.ptr, recv.ptr)
                val s = send.value
                val r = recv.value
                if (rc < 0 || s == null || r == null) {
                    null // connection closed
                } else {
                    Pair<FrameIn, FrameOut>(IosIn(r), IosOut(s))
                }
            }
        }
    }

    override fun isClosed(): Boolean = gate.enter { ps_conn_is_closed(h) != 0 } ?: true

    override fun paths(): List<PathInfo>? {
        val raw = gate.enter { takeString(ps_conn_path_summary(h)) } ?: return null
        // "<kind>|<rtt_ms>" (kind: direct / relay / other / none)
        val parts = raw.split('|', limit = 2)
        val kind = parts.getOrNull(0) ?: "none"
        val rtt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return when (kind) {
            "direct" -> listOf(PathInfo(PathKind.Direct, rtt, selected = true))
            "relay" -> listOf(PathInfo(PathKind.Relay, rtt, selected = true))
            "other" -> listOf(PathInfo(PathKind.Other, rtt, selected = true))
            else -> emptyList()
        }
    }

    override suspend fun close(code: Long, reason: String) {
        if (!gate.close()) return
        // close first so a blocked accept_bi returns, wait for every call inside, then free
        withContext(ioDispatcher) { ps_conn_close(h, code.convert(), reason) }
        gate.awaitIdle()
        withContext(ioDispatcher) { ps_conn_free(h) }
    }
}

/**
 * Node handle. 0.2.37: every call goes through a [CallGate]; shutdown() is ps_node_close (blocked accept
 * returns null) -> wait for the calls inside -> ps_node_free. waitOnline() waits in short slices so a
 * shutdown doesn't have to wait for the whole timeout.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosPsEndpoint(private val node: CPointer<PsNode>) : PsEndpoint {
    private val gate = CallGate()

    override suspend fun waitOnline(timeoutMs: Long): Boolean = withContext(ioDispatcher) {
        var left = timeoutMs
        while (left > 0) {
            val slice = minOf(left, WAIT_ONLINE_SLICE_MS)
            val rc = gate.enter { ps_node_wait_online(node, slice.convert()) } ?: return@withContext false // stopped
            if (rc == 1) return@withContext true
            if (rc < 0) return@withContext false
            left -= slice
        }
        false
    }

    // 0.2.12: added ps_node_home_relay to the FFI (same as the JVM endpoint.addr().relayUrl())
    override fun homeRelayUrl(): String? = gate.enter { takeString(ps_node_home_relay(node)) }
        .also { PsLog.d(TAG, "home relay: ${it ?: "(none)"}") }

    override suspend fun accept(): PsConnection? = withContext(ioDispatcher) {
        // null: stopped (gate closed, or ps_node_close made the blocked accept return)
        val conn = gate.enter { ps_node_accept(node) } ?: return@withContext null
        val remote = takeString(ps_conn_remote_id(conn))
        if (remote == null) {
            ps_conn_free(conn)
            throw ProtocolException("Couldn't get the other device's ID.")
        }
        IosPsConnection(conn, remote)
    }

    override suspend fun connect(peerId: String): PsConnection = withContext(ioDispatcher) {
        val conn = gate.enter {
            ps_node_connect(node, peerId) ?: throw ProtocolException(lastError() ?: "Connection failed.")
        } ?: throw ProtocolException("The node isn't running.")
        IosPsConnection(conn, peerId)
    }

    override suspend fun shutdown() {
        if (!gate.close()) return
        withContext(ioDispatcher) { ps_node_close(node) } // blocked accept / connect return
        val waitStart = epochMillis()
        gate.awaitIdle()
        PsLog.d(TAG, "node calls finished in ${epochMillis() - waitStart} ms; freeing")
        withContext(ioDispatcher) { ps_node_free(node) }
    }

    private companion object {
        /** waitOnline asks the FFI in slices this long, so shutdown waits at most about this long for it. */
        const val WAIT_ONLINE_SLICE_MS = 500L
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun bindPsEndpoint(secretKey: ByteArray, relayUrl: String?): PsEndpoint =
    withContext(ioDispatcher) {
        val relay = relayUrl?.trim()?.ifBlank { null }
        val node = secretKey.usePinned { pinned ->
            ps_node_start(pinned.addressOf(0).reinterpret(), relay)
        } ?: throw ProtocolException(lastError() ?: "Bind failed.")
        IosPsEndpoint(node)
    }

@OptIn(ExperimentalForeignApi::class)
actual fun generateSecretKey(): ByteArray {
    val key = ByteArray(32)
    val rc = key.usePinned { ps_secret_generate(it.addressOf(0).reinterpret()) }
    check(rc == 0) { lastError() ?: "Key generation failed." }
    return key
}

@OptIn(ExperimentalForeignApi::class)
actual fun endpointIdFromSecret(secretKey: ByteArray): String = memScoped {
    val out = allocPointerTo<ByteVar>()
    val rc = secretKey.usePinned { ps_id_from_secret(it.addressOf(0).reinterpret(), out.ptr) }
    check(rc == 0) { lastError() ?: "Device ID calculation failed." }
    takeString(out.value) ?: error("Couldn't get the device ID.")
}

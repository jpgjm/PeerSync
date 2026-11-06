package com.example.peersync.net

import com.example.peersync.data.Settings
import com.example.peersync.log.PsLog
import com.example.peersync.platform.nowTimeString
import com.example.peersync.proto.FileServer
import com.example.peersync.proto.PairHandler
import com.example.peersync.proto.PushHandler
import com.example.peersync.proto.RemoteClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "IrohNode"

/**
 * Owns one iroh Endpoint and
 *  - receiving: accepts connections and serves FileServer ("Send", pairing, presence checks)
 *  - sending: opens a Connection per peer and provides a RemoteClient
 * Shared version of the old Android net/IrohNode.kt. iroh is accessed via PsEndpoint (expect/actual).
 */
class IrohNode(
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Stopped : Status
        data object Starting : Status
        data class Online(val homeRelay: String?) : Status
        data class OfflineOnlyDirect(val note: String) : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Latest event (incoming connection, rejection, etc.). Shown in the UI. */
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    /**
     * "Send" receiver (per peer). Since Transfers -> Incoming are built in that order,
     * AppGraph injects it after startup (OFFER / PUT are rejected while null).
     */
    var pushHandlerProvider: (peerId: String) -> PushHandler? = { null }

    /** Pairing request receiver (per peer, 0.2.14). Injected by AppGraph (PAIR is rejected while null). */
    var pairHandlerProvider: (peerId: String) -> PairHandler? = { null }

    private val server = FileServer(
        log = { PsLog.d(TAG, "server: $it") },
        pushHandlerProvider = { peerId -> pushHandlerProvider(peerId) },
        isPaired = { peerId -> settings.isPaired(peerId) },
        pairHandlerProvider = { peerId -> pairHandlerProvider(peerId) },
        isUnpaired = { peerId -> settings.isUnpairedByMe(peerId) },
    )

    /** Current endpoint. Written by the start job and stop (serialized by [lifecycle]); read by sends from any thread. */
    @Volatile
    private var endpoint: PsEndpoint? = null
    private var acceptJob: Job? = null
    private val connLock = Mutex()
    private val outgoing = mutableMapOf<String, PsConnection>()
    /** One lock per peer for connecting (0.2.36). Kept for the node's lifetime (one small Mutex per peer ever contacted). */
    private val peerLocks = mutableMapOf<String, Mutex>()

    /**
     * Serializes start / stop / restart (0.2.37). Up to 0.2.36, tapping "Restart" repeatedly ran stop and start
     * concurrently: a second endpoint was bound while the first was still starting (two accept loops), and on iOS
     * the node was freed while other threads were still inside it, which crashed the app.
     */
    private val lifecycle = Mutex()

    /** Bind + wait-for-relay job of the current start (cancelled and joined by stop). */
    private var startJob: Job? = null

    private val _stopping = MutableStateFlow(false)
    /** True while the node is stopping (with [Status.Starting], the UI disables "Restart", 0.2.37). */
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    fun start() {
        scope.launch { lifecycle.withLock { startLocked() } }
    }

    /** Starts unless running or already starting. Call with [lifecycle] held. */
    private fun startLocked() {
        if (endpoint != null || startJob?.isActive == true) return
        _status.value = Status.Starting
        startJob = scope.launch {
            try {
                val relay = settings.relayUrl.value
                // Not cancellable: a stop that arrives during bind waits for it, so the bound endpoint is
                // always published and shut down by that stop (a cancelled bind would leak the native node).
                val ep = withContext(NonCancellable) { bindPsEndpoint(settings.secretKey, relay.ifBlank { null }) }
                endpoint = ep
                acceptJob = scope.launch { acceptLoop(ep) }
                // Wait for the home relay (direct connections can still be tried without it). A stop cancels this wait.
                val ok = withTimeoutOrNull(15_000) { ep.waitOnline(15_000) } == true
                _status.value = if (ok) {
                    Status.Online(ep.homeRelayUrl())
                } else {
                    Status.OfflineOnlyDirect("Not connected to relay. Trying direct connections only.")
                }
            } catch (e: CancellationException) {
                PsLog.d(TAG, "start cancelled by stop")
                throw e
            } catch (t: Throwable) {
                PsLog.e(TAG, "bind failed: ${t.message ?: t}")
                _status.value = Status.Error(t.message ?: t.toString())
            }
        }
    }

    suspend fun stop() {
        lifecycle.withLock { stopLocked() }
    }

    /** Stops the start job, the accept loop, our connections and the endpoint, in that order. Call with [lifecycle] held. */
    private suspend fun stopLocked() {
        _stopping.value = true
        try {
            startJob?.cancelAndJoin()
            startJob = null
            acceptJob?.cancel()
            acceptJob = null
            connLock.withLock {
                outgoing.values.forEach { runCatching { it.close(0, "bye") } }
                outgoing.clear()
            }
            endpoint?.let { ep ->
                endpoint = null
                runCatching { ep.shutdown() }.onFailure { PsLog.w(TAG, "shutdown: ${it.message ?: it}") }
            }
            _status.value = Status.Stopped
            PsLog.i(TAG, "node stopped")
        } finally {
            _stopping.value = false
        }
    }

    /**
     * "Restart" button (0.2.37): ignored while a start / stop / restart is already running (repeated taps).
     * @return false if ignored
     */
    suspend fun restart(): Boolean {
        if (!lifecycle.tryLock()) {
            PsLog.d(TAG, "restart ignored: start / stop already running")
            return false
        }
        try {
            PsLog.i(TAG, "restart")
            stopLocked()
            startLocked()
        } finally {
            lifecycle.unlock()
        }
        return true
    }

    /**
     * Runs [restart] in the node's scope (0.2.37). The UI calls this instead of launching from its own scope,
     * so leaving the screen doesn't cancel a restart halfway.
     */
    fun requestRestart() {
        scope.launch { restart() }
    }

    /** [restartNow] in the node's scope (relay URL changed in the UI). */
    fun requestRestartNow() {
        scope.launch { restartNow() }
    }

    /** Restart that must happen (new key, new relay URL): waits for a running start / stop instead of skipping. */
    suspend fun restartNow() {
        lifecycle.withLock {
            PsLog.i(TAG, "restart (settings changed)")
            stopLocked()
            startLocked()
        }
    }

    // ---------------- Receiving side ----------------

    private suspend fun acceptLoop(ep: PsEndpoint) {
        while (currentCoroutineContext().isActive) {
            val conn = try {
                ep.accept() ?: return
            } catch (e: CancellationException) {
                throw e // stopped (was logged as an accept warning up to 0.2.36)
            } catch (t: Throwable) {
                PsLog.w(TAG, "accept: ${t.message ?: t}")
                continue
            }
            scope.launch {
                try {
                    val remote = conn.remoteId
                    // 0.2.14: accept connections from unknown devices too (to receive pairing requests).
                    // FileServer rejects any op other than pairing requests / unpair notices with "not paired"
                    if (!settings.isPaired(remote)) {
                        val why = if (settings.isUnpairedByMe(remote)) "unpaired" else "unknown"
                        log("Connection from ${why} device (only pairing requests / unpair notices accepted): ${remote.take(10)}")
                    } else {
                        log("Accepted connection: ${settings.peerName(remote)}")
                    }
                    serveConnection(conn)
                } catch (t: Throwable) {
                    PsLog.w(TAG, "accept flow: ${t.message ?: t}")
                }
            }
        }
    }

    private suspend fun serveConnection(conn: PsConnection) {
        try {
            while (currentCoroutineContext().isActive) {
                val bi = try {
                    conn.acceptBi() ?: return // connection closed
                } catch (_: Throwable) {
                    return
                }
                scope.launch {
                    try {
                        server.handle(bi.first, bi.second, conn.remoteId)
                    } catch (t: Throwable) {
                        PsLog.w(TAG, "request failed: ${t.message ?: t}")
                    }
                }
            }
        } finally {
            // 0.2.37: release the incoming connection's handle once it has ended (up to 0.2.36 nothing closed it,
            // so on iOS its native handle leaked). Closing an already closed connection is harmless.
            withContext(NonCancellable) { runCatching { conn.close(0, "done") } }
        }
    }

    // ---------------- Sending side ----------------

    /**
     * Returns the cached connection to the peer, or connects.
     * 0.2.36: connecting holds only that peer's lock (was the global [connLock]). Device Hub now checks every
     * paired device every 30 s, and a connect to an offline device can take seconds; under the global lock it
     * held up sends and checks to every other device. [connLock] now only guards the [outgoing] map.
     */
    private suspend fun connection(peerId: String): PsConnection {
        val ep = endpoint ?: throw IllegalStateException("The node isn't running.")
        val peerLock = connLock.withLock { peerLocks.getOrPut(peerId) { Mutex() } }
        return peerLock.withLock {
            val existing = connLock.withLock { outgoing[peerId] }
            if (existing != null) {
                if (!existing.isClosed()) return@withLock existing
                runCatching { existing.close(0, "stale") } // iOS: releases the FFI handle
                connLock.withLock { if (outgoing[peerId] === existing) outgoing.remove(peerId) }
            }
            val conn = ep.connect(peerId)
            connLock.withLock { outgoing[peerId] = conn }
            conn
        }
    }

    fun client(peerId: String): RemoteClient = RemoteClient {
        val bi = try {
            connection(peerId).openBi()
        } catch (t: Throwable) {
            // The old connection may have been dead, so reconnect once
            connLock.withLock {
                outgoing.remove(peerId)?.let { runCatching { it.close(0, "stale") } }
            }
            connection(peerId).openBi()
        }
        bi
    }

    /** One-line route to the peer (direct/relay, RTT). null if not connected. Shown in Device Hub's online status (0.2.36). */
    suspend fun pathSummary(peerId: String): String? {
        val conn = connLock.withLock { outgoing[peerId] } ?: return null
        if (conn.isClosed()) return "Disconnected"
        val paths = conn.paths() ?: return "Connected"
        if (paths.isEmpty()) return "No path"
        return paths.sortedByDescending { it.selected }.joinToString(" | ") { p ->
            val kind = when (p.kind) {
                PathKind.Relay -> "Relay"
                PathKind.Direct -> "Direct"
                PathKind.Other -> "?"
            }
            val mark = if (p.selected) "● " else ""
            "$mark$kind ${p.rttMs}ms"
        }
    }

    suspend fun disconnect(peerId: String) {
        connLock.withLock {
            outgoing.remove(peerId)?.let { runCatching { it.close(0, "bye") } }
        }
    }

    private fun log(msg: String) {
        PsLog.d(TAG, msg)
        _events.value = (listOf("${nowTimeString()} $msg") + _events.value).take(20)
    }
}

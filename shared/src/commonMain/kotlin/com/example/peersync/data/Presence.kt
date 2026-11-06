package com.example.peersync.data

import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.example.peersync.net.IrohNode
import com.example.peersync.proto.RemoteException
import com.example.peersync.proto.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "${AppInfo.NAME}/Presence"

/** Whether a paired device answered the last presence check (0.2.36). */
sealed interface PeerPresence {
    /** Not checked yet. */
    data object Checking : PeerPresence

    /** Answered. [route] is the path in use (e.g. "Direct 12ms"), null if unknown. */
    data class Online(val route: String?) : PeerPresence

    /** Answered, but that device hasn't added this one (it says "not paired"). */
    data object NotPairedThere : PeerPresence

    /** No answer (offline, app closed, or unreachable). */
    data object Offline : PeerPresence
}

/**
 * Presence of paired devices for Device Hub (0.2.36). The UI calls [checkAll] every
 * [INTERVAL_MS] while Device Hub is on screen (not in the background). Each check sends PING:
 *  - answer: Online, and the device's current name replaces the saved one (renamed devices stay current)
 *  - "unpaired": that device removed this one while we were offline -> removed here too (same as a send)
 *  - "not paired": NotPairedThere (no automatic pairing request here; sending still asks as before)
 *  - other errors from the device (e.g. "unknown op" from an older app or the browser version): Online
 *  - no answer in [PING_TIMEOUT_MS]: the connection may be stale (the device restarted, iroh keeps the old
 *    connection about 30 s), so it is dropped and tried once more on a new one; still nothing = Offline
 */
class Presence(
    private val settings: Settings,
    private val node: IrohNode,
    private val pairing: Pairing,
) {
    private val _states = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    /** Peer ID -> presence. Peers not in the map are [PeerPresence.Checking]. */
    val states: StateFlow<Map<String, PeerPresence>> = _states.asStateFlow()

    /** Checks every paired device at once and returns when all have answered or timed out. */
    suspend fun checkAll() {
        val peers = settings.peers.value
        // forget devices that are no longer paired
        _states.update { m -> m.filterKeys { id -> peers.any { it.id == id } } }
        if (peers.isEmpty()) return
        coroutineScope {
            for (peer in peers) launch { check(peer) }
        }
    }

    private suspend fun check(peer: Peer) {
        val result = try {
            ping(peer)
        } catch (e: TimeoutCancellationException) {
            PsLog.d(TAG, "${peer.name}: no answer in ${PING_TIMEOUT_MS / 1000} s, retrying on a new connection")
            node.disconnect(peer.id)
            try {
                ping(peer)
            } catch (e2: TimeoutCancellationException) {
                node.disconnect(peer.id)
                PeerPresence.Offline
            }
        }
        val before = _states.value[peer.id]
        if (before != result && !(before is PeerPresence.Online && result is PeerPresence.Online)) {
            PsLog.i(TAG, "${peer.name} (${peer.shortId}): ${before ?: PeerPresence.Checking} -> $result")
        }
        if (settings.isPaired(peer.id)) {
            _states.update { it + (peer.id to result) }
        } else {
            _states.update { it - peer.id }
        }
    }

    /** One PING. Throws TimeoutCancellationException when there is no answer in time. */
    private suspend fun ping(peer: Peer): PeerPresence = try {
        val name = withTimeout(PING_TIMEOUT_MS) { node.client(peer.id).ping(settings.deviceName) }
        settings.renamePeer(peer.id, name)
        PeerPresence.Online(route(peer))
    } catch (e: RemoteException) {
        when (e.message) {
            Wire.ERR_UNPAIRED -> {
                PsLog.i(TAG, "${peer.name}: has unpaired this device")
                pairing.onUnpairedError(peer)
                PeerPresence.Offline
            }
            Wire.ERR_NOT_PAIRED -> PeerPresence.NotPairedThere
            else -> {
                PsLog.d(TAG, "${peer.name}: answered with an error (${e.message}); counted as online")
                PeerPresence.Online(route(peer))
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // connect failed (node stopped, no route, connection lost)
        PsLog.d(TAG, "${peer.name}: unreachable (${t.message ?: t})")
        node.disconnect(peer.id)
        PeerPresence.Offline
    }

    /** The path in use, e.g. "Direct 12ms" (first part of IrohNode.pathSummary, which lists the selected path first). */
    private suspend fun route(peer: Peer): String? =
        node.pathSummary(peer.id)?.substringBefore(" | ")?.removePrefix("● ")

    companion object {
        /** Time between rounds while Device Hub is shown. */
        const val INTERVAL_MS = 30_000L

        /** Wait for one PING answer. */
        const val PING_TIMEOUT_MS = 10_000L
    }
}

package com.example.peersync.data

import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.example.peersync.net.IrohNode
import com.example.peersync.platform.PsLock
import com.example.peersync.proto.OfferResponse
import com.example.peersync.proto.PairHandler
import com.example.peersync.proto.ProtocolException
import com.example.peersync.proto.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.example.peersync.proto.RemoteException

private const val TAG = "${AppInfo.NAME}/Pairing"

/** Pairing request from another device (awaiting consent). Shown in a UI dialog and platform notification. */
data class PairingRequest(
    val token: String,
    val peerId: String,
    /** Device name given by the requester (start of the ID if empty). Also used as the saved name when allowed. */
    val peerName: String,
) {
    val shortId: String get() = peerId.take(10)
}

/**
 * Notice that the other device unpaired (0.2.21). Since 0.2.22 it's removed here too without asking,
 * and we only show "Device unpaired" (OK only).
 */
data class UnpairNotice(
    val peerId: String,
    val peerName: String,
) {
    val shortId: String get() = peerId.take(10)
}

/**
 * Tells the platform about pending requests (for background notifications; same role as IncomingNotifier).
 * Android: notification with "Allow" / "Cancel" buttons / iOS: local notification (with buttons) /
 * desktop: bring the window to front and show a tray notification.
 */
interface PairingNotifier {
    fun onRequest(request: PairingRequest) {}

    /** Called on allow, cancel or timeout (to remove the notification). */
    fun onRequestResolved(token: String) {}

    /**
     * The other device unpaired and it was auto-removed here (0.2.22; notice only. Android: notification / iOS: local notification /
     * desktop: bring to front + tray).
     */
    fun onUnpaired(notice: UnpairNotice) {}

    /** OK pressed in the notice dialog (to remove the notification). */
    fun onUnpairResolved(peerId: String) {}
}

/** Result returned to the requester (App shows it in a snackbar). */
sealed interface PairingEvent {
    val peer: Peer

    /** The other device allowed it (including when it already had this device). */
    data class Accepted(override val peer: Peer) : PairingEvent

    /** The other device tapped "Cancel". */
    data class Rejected(override val peer: Peer) : PairingEvent

    /** Didn't reach the other device, no response, or an old version. */
    data class Failed(override val peer: Peer, val reason: String) : PairingEvent
}

/**
 * Pairing requests (0.2.14). When one device adds another, the other device shows a "Pair with this device?"
 * dialog, and "Allow" adds it to that side's allow list too (no need to add manually on both).
 *
 * Receiving side (PairHandler):
 *  - PAIR: awaits consent (ACCEPTED at once if already registered). The requester polls PAIR_STATUS.
 *    No decision within 120 s counts as cancelled. A pending request from the same peer is replaced.
 *  - "Allow" = Settings.addPeer (with the name the requester gave).
 * Requesting side (requestTo):
 *  - Sent right after "Add" and whenever the peer says "not paired" (0.2.36: when a send is refused that way).
 *  - 0.2.22: "Add" only sends a request; the peer is saved once approved (nothing kept on decline/failure).
 *  - Results go to events and App shows a snackbar ("Request again" button on decline/failure).
 */
class Pairing(
    private val settings: Settings,
    private val node: IrohNode,
    private val scope: CoroutineScope,
) {
    /** This device's name sent with PAIR / UNPAIR (0.2.36: the user can change it, so read it each time). */
    private val deviceName: String get() = settings.deviceName

    var notifier: PairingNotifier = object : PairingNotifier {}

    /** Pairing history (0.2.21, persisted). */
    val history = PairingHistory(settings.store)

    private val _unpairNotices = MutableStateFlow<List<UnpairNotice>>(emptyList())
    /** Notices of auto-removal after the peer unpaired (awaiting OK, oldest first). The UI shows the first in a dialog. */
    val unpairNotices: StateFlow<List<UnpairNotice>> = _unpairNotices.asStateFlow()

    private val _outgoing = MutableStateFlow<List<Peer>>(emptyList())
    /**
     * Peers we've sent requests to (0.2.22). They aren't paired until approved,
     * so Device Hub shows them separately as "Pending" rows.
     */
    val outgoing: StateFlow<List<Peer>> = _outgoing.asStateFlow()

    private class Entry(val request: PairingRequest) {
        var status: Int = Wire.PAIR_PENDING
    }

    private val entries = HashMap<String, Entry>()
    private val lock = PsLock()

    private val _pending = MutableStateFlow<List<PairingRequest>>(emptyList())
    /** Pending requests (oldest first). The UI shows the first in a dialog. */
    val pending: StateFlow<List<PairingRequest>> = _pending.asStateFlow()

    private val _events = MutableSharedFlow<PairingEvent>(extraBufferCapacity = 16)
    /** Results on the requesting side (App shows them in a snackbar). */
    val events: SharedFlow<PairingEvent> = _events.asSharedFlow()

    /** Peers with a request in progress (prevents duplicate requests). */
    private val inFlight = HashSet<String>()

    // ---------------- Receiving side ----------------

    /** Decision from the UI or a notification. */
    fun decide(token: String, accept: Boolean) = decide(token, accept, timedOut = false)

    private fun decide(token: String, accept: Boolean, timedOut: Boolean) {
        val entry = lock.withLock { entries[token] } ?: run {
            PsLog.d(TAG, "decide: unknown token ${token.take(8)}")
            notifier.onRequestResolved(token)
            return
        }
        val changed = lock.withLock {
            if (entry.status != Wire.PAIR_PENDING) {
                false
            } else {
                entry.status = if (accept) Wire.PAIR_ACCEPTED else Wire.PAIR_REJECTED
                true
            }
        }
        if (!changed) return
        _pending.update { list -> list.filterNot { it.token == token } }
        notifier.onRequestResolved(token)
        val req = entry.request
        if (accept) {
            PsLog.i(TAG, "pairing accepted: ${req.peerName} (${req.shortId})")
            settings.addPeer(Peer(req.peerId, req.peerName))
            history.add(req.peerId, req.peerName, PairingLogKind.ReceivedAccepted)
        } else {
            PsLog.i(TAG, "pairing rejected: ${req.peerName} (${req.shortId}) timedOut=$timedOut")
            history.add(
                req.peerId, req.peerName,
                if (timedOut) PairingLogKind.ReceivedTimedOut else PairingLogKind.ReceivedRejected,
            )
        }
        // Keep for a while so the requester's polling can read the result, then remove
        scope.launch {
            delay(Consent.DECISION_KEEP_MS)
            lock.withLock { entries.remove(token) }
        }
    }

    /** Per-peer receiver (from FileServer). */
    fun handlerFor(peerId: String): PairHandler = PeerHandler(peerId)

    private inner class PeerHandler(private val peerId: String) : PairHandler {
        override suspend fun request(name: String): OfferResponse {
            if (settings.isPaired(peerId)) {
                // Already registered (normal mutual add, the peer added later). No dialog
                PsLog.d(TAG, "pair request from already paired ${settings.peerName(peerId)}")
                return OfferResponse("", Wire.PAIR_ACCEPTED)
            }
            val peerName = name.trim().take(Wire.MAX_NAME_CHARS).ifBlank { peerId.take(10) }
            // Replace any pending request from the same peer (re-request)
            lock.withLock {
                entries.values.filter { it.request.peerId == peerId && it.status == Wire.PAIR_PENDING }
                    .forEach { old ->
                        old.status = Wire.PAIR_REJECTED
                        _pending.update { list -> list.filterNot { it.token == old.request.token } }
                        notifier.onRequestResolved(old.request.token)
                    }
            }
            val token = Consent.newToken()
            val req = PairingRequest(token, peerId, peerName)
            val entry = Entry(req)
            lock.withLock { entries[token] = entry }
            PsLog.i(TAG, "pair request from $peerName (${req.shortId}) token=${token.take(8)}")
            _pending.update { it + req }
            history.add(peerId, peerName, PairingLogKind.RequestReceived)
            notifier.onRequest(req)
            scope.launch {
                delay(Transfers.CONSENT_TIMEOUT_MS)
                val still = lock.withLock { entry.status == Wire.PAIR_PENDING }
                if (still) {
                    PsLog.w(TAG, "pair request from $peerName timed out (no decision)")
                    decide(token, accept = false, timedOut = true)
                }
            }
            return OfferResponse(token, lock.withLock { entry.status })
        }

        override suspend fun status(token: String): OfferResponse {
            val entry = lock.withLock { entries[token] } ?: throw ProtocolException("unknown token")
            if (entry.request.peerId != peerId) throw ProtocolException("unknown token")
            return OfferResponse(token, lock.withLock { entry.status })
        }

        override suspend fun unpair(name: String) {
            if (!settings.isPaired(peerId)) {
                // Not registered here either (already removed). Nothing to report
                PsLog.d(TAG, "unpair from not-paired ${peerId.take(10)}: ignored")
                return
            }
            PsLog.i(TAG, "unpair notice from ${settings.peerName(peerId)} (${peerId.take(10)}) name=\"$name\"")
            removedByPeer(peerId, "unpair notice received")
        }

        override suspend fun ping(name: String): String {
            // FileServer lets only paired devices through. Keep their current name (0.2.36)
            settings.renamePeer(peerId, name)
            return deviceName
        }
    }

    // ---------------- Unpair (0.2.21; both sides auto-remove since 0.2.22) ----------------

    /**
     * Unpaired by the peer: remove here too without asking and show a notice (OK only).
     * No unpairedByMe record. From then on neither side is registered (not paired).
     */
    private fun removedByPeer(peerId: String, how: String) {
        if (!settings.isPaired(peerId)) return
        val notice = UnpairNotice(peerId, settings.peerName(peerId))
        PsLog.i(TAG, "unpaired by ${notice.peerName} (${notice.shortId}): removing automatically ($how)")
        settings.removePeer(peerId, byMe = false)
        history.add(peerId, notice.peerName, PairingLogKind.UnpairedByPeer, "Also removed from this device ($how).")
        scope.launch { runCatching { node.disconnect(peerId) } }
        val added = lock.withLock {
            if (_unpairNotices.value.any { it.peerId == peerId }) {
                false
            } else {
                _unpairNotices.value = _unpairNotices.value + notice
                true
            }
        }
        if (added) notifier.onUnpaired(notice)
    }

    /**
     * The peer returned ERR_UNPAIRED on a send or an online check (fallback when UNPAIR didn't arrive because we were offline).
     * As with a received notice, remove here automatically and show a notice.
     */
    fun onUnpairedError(peer: Peer) {
        PsLog.i(TAG, "unpaired by ${peer.name} (found on request)")
        removedByPeer(peer.id, "found when connecting")
    }

    /** OK in the notice dialog (from the UI). */
    fun dismissUnpairNotice(peerId: String) {
        lock.withLock { _unpairNotices.value = _unpairNotices.value.filterNot { it.peerId == peerId } }
        notifier.onUnpairResolved(peerId)
        PsLog.d(TAG, "unpair notice dismissed: ${peerId.take(10)}")
    }

    /**
     * Removes the peer on this device (unpair; the UI confirms first). Removes it from the list and sends
     * UNPAIR (the peer auto-removes too). If undelivered, an "unpaired" record is kept and the peer is told
     * via ERR_UNPAIRED on its next connection. Once delivered, the record is cleared (not paired from then on).
     */
    fun unpair(peer: Peer) {
        settings.removePeer(peer.id, byMe = true)
        val logId = history.add(peer.id, peer.name, PairingLogKind.UnpairedByMe, "Notifying the other device…")
        if (_unpairNotices.value.any { it.peerId == peer.id }) dismissUnpairNotice(peer.id)
        scope.launch {
            val detail = try {
                PsLog.i(TAG, "unpair -> ${peer.name} (${peer.shortId})")
                withTimeout(UNPAIR_TIMEOUT_MS) { node.client(peer.id).unpair(deviceName) }
                PsLog.i(TAG, "unpair -> ${peer.name}: delivered")
                settings.clearUnpairMarks(peer.id)
                "Also removed on the other device."
            } catch (e: RemoteException) {
                PsLog.w(TAG, "unpair -> ${peer.name}: remote error ${e.message}")
                "Couldn't notify the other device: ${e.message}"
            } catch (t: Throwable) {
                PsLog.w(TAG, "unpair -> ${peer.name}: not delivered (${t.message ?: t})")
                "Didn't reach the other device. It will be removed there the next time it connects."
            } finally {
                runCatching { node.disconnect(peer.id) }
            }
            history.updateDetail(logId, detail)
        }
    }

    // ---------------- Device ID reset (0.2.36) ----------------

    private val _resetting = MutableStateFlow(false)
    /** True while "Reset device ID" runs (the Settings row shows a spinner; survives tab switches). */
    val resetting: StateFlow<Boolean> = _resetting.asStateFlow()

    /**
     * Starts "Reset device ID" in the app scope (not the screen's, so leaving Settings doesn't cancel it halfway
     * with some devices told and the key unchanged). [onDone] gets how many devices couldn't be told.
     * Ignored while a reset is already running.
     */
    fun startResetIdentity(onDone: (notReached: Int) -> Unit) {
        val first = lock.withLock { if (_resetting.value) false else { _resetting.value = true; true } }
        if (!first) {
            PsLog.d(TAG, "reset device ID already running")
            return
        }
        scope.launch {
            try {
                onDone(resetIdentity())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // not rethrown: an uncaught exception in the app scope would crash the app
                PsLog.e(TAG, "reset device ID failed: ${t.message ?: t}")
            } finally {
                _resetting.value = false
            }
        }
    }

    /**
     * "Reset device ID": with the old key still in use, sends UNPAIR to every paired device at once
     * (each gives up after [UNPAIR_TIMEOUT_MS]), then switches to a new key (clearing the paired list)
     * and restarts the node. Devices that weren't reached keep the old ID in their list
     * (the old ID no longer exists, so the ERR_UNPAIRED fallback can't reach them).
     * @return how many paired devices couldn't be notified
     */
    private suspend fun resetIdentity(): Int {
        val peers = settings.peers.value
        PsLog.i(TAG, "reset device ID: notifying ${peers.size} paired devices")
        val results = coroutineScope {
            peers.map { peer ->
                async {
                    val detail = try {
                        withTimeout(UNPAIR_TIMEOUT_MS) { node.client(peer.id).unpair(deviceName) }
                        PsLog.i(TAG, "reset: unpair -> ${peer.name}: delivered")
                        null
                    } catch (e: RemoteException) {
                        PsLog.w(TAG, "reset: unpair -> ${peer.name}: remote error ${e.message}")
                        "Couldn't notify the other device: ${e.message}"
                    } catch (e: TimeoutCancellationException) {
                        PsLog.w(TAG, "reset: unpair -> ${peer.name}: timed out")
                        RESET_NOT_REACHED
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        PsLog.w(TAG, "reset: unpair -> ${peer.name}: not delivered (${t.message ?: t})")
                        RESET_NOT_REACHED
                    }
                    history.add(
                        peer.id, peer.name, PairingLogKind.UnpairedByMe,
                        "Device ID reset. " + (detail ?: "Also removed on the other device."),
                    )
                    detail == null
                }
            }.awaitAll()
        }
        settings.resetIdentity()
        _outgoing.value = emptyList()
        node.restartNow()
        val failed = results.count { !it }
        PsLog.i(TAG, "reset device ID done: ${results.size - failed} notified, $failed not reached")
        return failed
    }

    // ---------------- Requesting side ----------------

    /**
     * Requests pairing with the peer (async). Does nothing if a request to that peer is in progress. Results go to events.
     * 0.2.22: saves the peer (Settings.addPeer) only when approved; not on decline/failure.
     * While requesting an unregistered peer it's listed in outgoing (Device Hub "Pending" row).
     */
    fun requestTo(peer: Peer) {
        val first = lock.withLock { inFlight.add(peer.id) }
        if (!first) {
            PsLog.d(TAG, "pair request to ${peer.name} already in flight")
            return
        }
        val newPeer = !settings.isPaired(peer.id)
        if (newPeer) _outgoing.update { list -> list.filterNot { it.id == peer.id } + peer }
        scope.launch {
            try {
                PsLog.i(TAG, "pair request -> ${peer.name} (${peer.shortId}) as \"$deviceName\"")
                history.add(peer.id, peer.name, PairingLogKind.RequestSent)
                val client = node.client(peer.id)
                val resp = Consent.awaitDecision(client.pair(deviceName), { it.status == Wire.PAIR_PENDING }) {
                    client.pairStatus(it.token)
                }
                val event = if (resp.status == Wire.PAIR_ACCEPTED) {
                    PsLog.i(TAG, "pair request -> ${peer.name}: accepted")
                    // Save only once approved (keep the name if already registered)
                    if (!settings.isPaired(peer.id)) settings.addPeer(peer)
                    settings.clearUnpairMarks(peer.id)
                    history.add(peer.id, peer.name, PairingLogKind.SentAccepted)
                    PairingEvent.Accepted(peer)
                } else {
                    PsLog.i(TAG, "pair request -> ${peer.name}: rejected (not registered=$newPeer)")
                    history.add(peer.id, peer.name, PairingLogKind.SentRejected)
                    PairingEvent.Rejected(peer)
                }
                _events.emit(event)
            } catch (t: Throwable) {
                val reason = t.message ?: t.toString()
                PsLog.w(TAG, "pair request -> ${peer.name} failed: $reason")
                history.add(peer.id, peer.name, PairingLogKind.SentFailed, reason)
                _events.emit(PairingEvent.Failed(peer, reason))
            } finally {
                lock.withLock { inFlight.remove(peer.id) }
                _outgoing.update { list -> list.filterNot { it.id == peer.id } }
            }
        }
    }

    companion object {
        /** Timeout for sending UNPAIR (if the peer is offline, give up and rely on the unpaired record). */
        const val UNPAIR_TIMEOUT_MS = 20_000L

        private const val RESET_NOT_REACHED = "Didn't reach the other device. It keeps the old ID in its list."
    }
}

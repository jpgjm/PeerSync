package com.example.peersync.data

import com.example.peersync.text.PsText
import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.example.peersync.platform.PsLock
import com.example.peersync.platform.epochMillis
import com.example.peersync.proto.DownloadSink
import com.example.peersync.proto.DownloadTargets
import com.example.peersync.proto.OfferItem
import com.example.peersync.proto.OfferResponse
import com.example.peersync.proto.ProtocolException
import com.example.peersync.proto.PushHandler
import com.example.peersync.proto.SinkResult
import com.example.peersync.proto.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "${AppInfo.NAME}/Incoming"

/** "Send" offer from the other device (awaiting consent). Shown in a UI dialog and platform notification. */
data class IncomingOffer(
    val token: String,
    val peerId: String,
    val peerName: String,
    val items: List<OfferItem>,
    /** Texts for the clipboard (not saved to files). */
    val texts: List<String> = emptyList(),
) {
    val totalBytes: Long get() = items.sumOf { it.size }
    val count: Int get() = items.size
    val isTextOnly: Boolean get() = items.isEmpty() && texts.isNotEmpty()
}

/** Accepted text. The UI (App) copies it to the clipboard and shows a snackbar. */
data class ReceivedText(val token: String, val peerName: String, val text: String)

/**
 * Tells the platform about pending offers (for notifications in the background).
 * Android: notification with "Accept" / "Decline" buttons / iOS: local notification (with buttons) /
 * desktop: bring the window to front and show a tray notification.
 */
interface IncomingNotifier {
    fun onOffer(offer: IncomingOffer) {}

    /** Called on accept, decline or timeout (to remove the notification). */
    fun onOfferResolved(token: String) {}

    /**
     * Text accepted (manually or automatically). The foreground UI (App) does the actual clipboard copy,
     * so this is for a notification prompting "open to copy" when in the background.
     */
    fun onTextAccepted(text: ReceivedText) {}

    /**
     * Files auto-accepted with "Accept without asking" ON (0.2.24). No consent notification/dialog appears,
     * so this announces the start of receiving in the background (iOS: local notification; not needed on Android, which shows a transfer notification).
     */
    fun onFilesAutoAccepted(offer: IncomingOffer) {}

    /**
     * Text auto-accepted with "Accept without asking" ON (0.2.37; called after [onTextAccepted]). No dialog appears,
     * so the platform can announce it (Windows: tray notification).
     */
    fun onTextAutoAccepted(text: ReceivedText) {}
}

/**
 * Receiving side of "Send".
 *  - OFFER: awaits consent (accepted at once if "Accept without asking" is ON). The sender polls OFFER_STATUS.
 *    No decision within 120 s counts as declined.
 *  - PUT: saves only relPaths of an accepted token to <save to>/<peer name>/<relPath> (DownloadTargets: the "Save to" folder).
 *    Partial files go to .peersync-part and are renamed on completion ("name (1).ext" on a clash).
 *  - Progress goes to the Transfers receive batch (one send = one row).
 *  - If no PUT arrives for 90 s after accepting, it's closed as interrupted (sender cancelled or disconnected).
 */
class Incoming(
    private val settings: Settings,
    private val targets: DownloadTargets,
    private val transfers: Transfers,
    private val scope: CoroutineScope,
    private val guardFactory: TransferGuardFactory? = null,
) {
    var notifier: IncomingNotifier = object : IncomingNotifier {}

    private class Batch(
        val offer: IncomingOffer,
        val createdAt: Long,
    ) {
        var status: Int = Wire.OFFER_PENDING
        var transferId: Int = -1
        /** relPath -> bytes received (size when complete). */
        val progress = HashMap<String, Long>()
        val done = HashSet<String>()
        var lastSaved: String = ""
        var watchdog: Job? = null
        var guard: TransferGuard? = null
        val throttle = ProgressThrottle()
        var closed = false
    }

    private val batches = HashMap<String, Batch>()
    private val lock = PsLock()

    private val _pending = MutableStateFlow<List<IncomingOffer>>(emptyList())
    /** Pending offers (oldest first). The UI shows the first one in a dialog. */
    val pending: StateFlow<List<IncomingOffer>> = _pending.asStateFlow()

    private val _textsToCopy = MutableStateFlow<List<ReceivedText>>(emptyList())
    /**
     * Accepted texts not yet copied to the clipboard (oldest first).
     * App copies from the front and removes via consumeText() while in the foreground (texts accepted in the background: on return).
     */
    val textsToCopy: StateFlow<List<ReceivedText>> = _textsToCopy.asStateFlow()

    private val _receiveStarted = MutableSharedFlow<IncomingOffer>(extraBufferCapacity = 8)
    /** Files from an accepted offer started arriving (0.2.33; manual or automatic accept). The UI shows a snackbar. */
    val receiveStarted: SharedFlow<IncomingOffer> = _receiveStarted

    fun consumeText(token: String) {
        _textsToCopy.update { list -> list.filterNot { it.token == token } }
    }

    /** Decision from the UI or a notification. */
    fun decide(token: String, accept: Boolean, fromUi: Boolean = true) {
        val batch = lock.withLock { batches[token] } ?: run {
            PsLog.d(TAG, "decide: unknown token ${token.take(8)}")
            notifier.onOfferResolved(token)
            return
        }
        val changed = lock.withLock {
            if (batch.status != Wire.OFFER_PENDING) {
                false
            } else {
                batch.status = if (accept) Wire.OFFER_ACCEPTED else Wire.OFFER_REJECTED
                true
            }
        }
        if (!changed) return
        _pending.update { list -> list.filterNot { it.token == token } }
        notifier.onOfferResolved(token)
        val offer = batch.offer
        if (accept) {
            PsLog.i(TAG, "accepted ${offer.count} items, ${offer.texts.size} texts from ${offer.peerName} (${if (fromUi) "manual" else "auto"})")
            // Texts go to the clipboard, not files (the UI does the copy)
            for ((i, t) in offer.texts.withIndex()) {
                val rt = ReceivedText(if (i == 0) offer.token else "${offer.token}#$i", offer.peerName, t)
                _textsToCopy.update { it + rt }
                notifier.onTextAccepted(rt)
                if (!fromUi) notifier.onTextAutoAccepted(rt)
            }
            if (offer.items.isEmpty()) {
                // Text only: nothing to receive, so done at once (the sender stops on ACCEPTED)
                finish(batch, success = true, report = false)
                return
            }
            batch.transferId = transfers.beginReceive(
                offer.peerName, offer.items.map { it.relPath.substringAfterLast('/') }, offer.totalBytes,
            ) { cancelBatch(token) }
            // iOS background protection (including the BGContinuedProcessingTask system progress UI).
            // Up to 0.2.23 it was requested only on manual accept (fromUi), so auto-accept with "Accept without asking" ON
            // never showed "Receiving files". While the app is in the foreground the request succeeds
            // from any thread (Bridge dispatches synchronously to main), so auto-accept requests it too.
            // If the request fails in the background, only the first stage (about 30 s) applies.
            batch.guard = guardFactory?.begin(
                PsText.receivingFiles, "${Transfers.batchLabel(offer.items.map { it.relPath.substringAfterLast('/') })} (${offer.peerName})",
            ) { cancelBatch(token) }
            PsLog.d(TAG, "receive guard ${if (batch.guard != null) "started" else "unavailable"} (${if (fromUi) "manual" else "auto"})")
            if (!fromUi) {
                // Auto-accept: no consent notification, so in the background the platform announces the start of receiving
                notifier.onFilesAutoAccepted(offer)
            }
            _receiveStarted.tryEmit(offer)
            touch(batch)
        } else {
            PsLog.i(TAG, "rejected ${offer.count} items from ${offer.peerName}")
            // Keep for a while so the sender's polling can read the decline, then remove
            scope.launch {
                delay(Consent.DECISION_KEEP_MS)
                lock.withLock { batches.remove(token) }
            }
        }
    }

    /** Per-peer receiver (from FileServer). */
    fun handlerFor(peerId: String): PushHandler = PeerHandler(peerId)

    private inner class PeerHandler(private val peerId: String) : PushHandler {
        private val peerName: String get() = settings.peerName(peerId)

        override suspend fun offer(items: List<OfferItem>, texts: List<String>): OfferResponse {
            if (items.isEmpty() && texts.isEmpty()) throw ProtocolException("empty offer")
            for (it in items) {
                validateRelPath(it.relPath)
                if (it.size < 0) throw ProtocolException("bad size")
            }
            // Replace any pending offer from the same peer (resend)
            lock.withLock {
                batches.values.filter { it.offer.peerId == peerId && it.status == Wire.OFFER_PENDING }
                    .forEach { old ->
                        old.status = Wire.OFFER_REJECTED
                        _pending.update { list -> list.filterNot { it.token == old.offer.token } }
                        notifier.onOfferResolved(old.offer.token)
                    }
            }
            val token = Consent.newToken()
            val offer = IncomingOffer(token, peerId, peerName, items, texts)
            val batch = Batch(offer, epochMillis())
            lock.withLock { batches[token] = batch }
            PsLog.i(TAG, "offer from $peerName: ${items.size} items, ${offer.totalBytes} bytes, ${texts.size} texts (token=${token.take(8)})")

            if (settings.autoAcceptIncoming.value) {
                decide(token, accept = true, fromUi = false)
            } else {
                _pending.update { it + offer }
                notifier.onOffer(offer)
                scope.launch {
                    delay(Transfers.CONSENT_TIMEOUT_MS)
                    val still = lock.withLock { batch.status == Wire.OFFER_PENDING }
                    if (still) {
                        PsLog.w(TAG, "offer from $peerName timed out (no decision)")
                        decide(token, accept = false, fromUi = false)
                    }
                }
            }
            return OfferResponse(token, lock.withLock { batch.status })
        }

        override suspend fun offerStatus(token: String): OfferResponse {
            val batch = lock.withLock { batches[token] } ?: throw ProtocolException("unknown token")
            if (batch.offer.peerId != peerId) throw ProtocolException("unknown token")
            return OfferResponse(token, lock.withLock { batch.status })
        }

        override suspend fun sinkFor(token: String, relPath: String, size: Long): DownloadSink {
            val batch = lock.withLock { batches[token] } ?: throw ProtocolException("unknown token")
            if (batch.offer.peerId != peerId) throw ProtocolException("unknown token")
            if (batch.status != Wire.OFFER_ACCEPTED || batch.closed) throw ProtocolException("not accepted")
            val item = batch.offer.items.firstOrNull { it.relPath == relPath }
                ?: throw ProtocolException("not in offer: $relPath")
            if (item.size != size) throw ProtocolException("size mismatch: $relPath")
            validateRelPath(relPath)
            return when (val r = targets.create(peerName.safePeerDirName(), relPath)) {
                is SinkResult.Ready -> r.sink
                is SinkResult.Unavailable -> throw ProtocolException("Save location unavailable: ${r.reason}")
            }
        }

        override fun onPutProgress(token: String, relPath: String, done: Long, size: Long): Boolean {
            val batch = lock.withLock { batches[token] } ?: return false
            if (batch.closed) return false // cancelled: FileServer aborts receiving
            batch.progress[relPath] = done
            // 0.2.25: called every 256KB, so throttle list/notification updates and watchdog resets
            // (the watchdog is 90 s, so 200ms throttling is harmless; end of file always passes)
            if (batch.throttle.pass(done, size)) {
                touch(batch)
                transfers.receiveProgress(batch.transferId, batch.progress.values.sum(), batch.guard)
            }
            return true
        }

        override fun onPutDone(token: String, relPath: String, savedPath: String) {
            val batch = lock.withLock { batches[token] } ?: return
            batch.progress[relPath] = batch.offer.items.first { it.relPath == relPath }.size
            batch.done += relPath
            batch.lastSaved = savedPath
            transfers.notifySaved(savedPath)
            if (batch.done.size >= batch.offer.count) {
                finish(batch, success = true)
            } else {
                touch(batch)
                transfers.receiveProgress(batch.transferId, batch.progress.values.sum(), batch.guard)
            }
        }

        override fun onPutFailed(token: String, relPath: String, reason: String) {
            val batch = lock.withLock { batches[token] } ?: return
            if (batch.closed) return
            PsLog.w(TAG, "put failed ${relPath}: $reason (resumes next time)")
            // Don't end on one file's failure (a resend resumes). Leave it to the watchdog
            touch(batch)
        }
    }

    // ---- Internal ----

    /** Activity: reset the watchdog. */
    private fun touch(batch: Batch) {
        batch.watchdog?.cancel()
        batch.watchdog = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!batch.closed && batch.done.size < batch.offer.count) {
                PsLog.w(TAG, "receive from ${batch.offer.peerName} idle for ${IDLE_TIMEOUT_MS / 1000}s; closing")
                transfers.receiveFailed(batch.transferId, "Interrupted. Send again to resume where it stopped.")
                finish(batch, success = false, report = false)
            }
        }
    }

    private fun finish(batch: Batch, success: Boolean, report: Boolean = true) {
        val first = lock.withLock {
            if (batch.closed) {
                false
            } else {
                batch.closed = true
                if (batch.offer.isTextOnly) {
                    // Keep for a while so the sender can read ACCEPTED via OFFER_STATUS, then remove
                    scope.launch {
                        delay(Consent.DECISION_KEEP_MS)
                        lock.withLock { batches.remove(batch.offer.token) }
                    }
                } else {
                    batches.remove(batch.offer.token)
                }
                true
            }
        }
        if (!first) return
        batch.watchdog?.cancel()
        if (report) {
            if (success) transfers.receiveFinished(batch.transferId, batch.lastSaved)
            else transfers.receiveFailed(batch.transferId, "Failed")
        }
        batch.guard?.end(success)
        PsLog.i(TAG, "receive from ${batch.offer.peerName} ${if (success) "completed" else "closed"}")
    }

    private fun cancelBatch(token: String) {
        val batch = lock.withLock { batches[token] } ?: return
        PsLog.i(TAG, "receive from ${batch.offer.peerName} cancelled by user")
        transfers.receiveCancelled(batch.transferId)
        finish(batch, success = false, report = false)
        // Later PUTs are rejected as unknown token -> the sender stops with an error
    }

    private fun validateRelPath(relPath: String) {
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw ProtocolException("invalid path")
        if (parts.any { it == ".." || it == "." || it.contains('\\') || it.contains('\u0000') }) {
            throw ProtocolException("invalid path")
        }
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 90_000L
    }
}

package com.example.peersync.data

import com.example.peersync.text.PsText
import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.example.peersync.net.IrohNode
import com.example.peersync.platform.epochMillis
import com.example.peersync.proto.OfferItem
import com.example.peersync.proto.RemoteException
import com.example.peersync.proto.SendItem
import com.example.peersync.proto.Wire
import com.example.peersync.ui.formatMb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.example.peersync.platform.PsLock

private const val TAG = "${AppInfo.NAME}/Transfers"

@Serializable
enum class TransferState { Running, Completed, Failed, Cancelled }

/**
 * Transfer direction. Send = from us / Receive = sent to us by the peer.
 * Download (from the peer's shared folder) was removed in 0.2.36; it stays so saved history rows still load.
 */
@Serializable
enum class TransferKind { Download, Send, Receive }

@Serializable
data class TransferItem(
    val id: Int,
    val fileName: String,
    val peerName: String,
    val kind: TransferKind = TransferKind.Download, // default kept for rows saved before kind existed
    val done: Long = 0,
    val total: Long = 0,
    val indeterminate: Boolean = true,
    val state: TransferState = TransferState.Running,
    val error: String? = null,
    /** Item count of a send/receive batch (1 for an old single-file download row). */
    val count: Int = 1,
    /** Start time (epoch millis, 0.2.21; for History; 0 for rows from 0.2.20 and earlier). */
    val startedAt: Long = 0,
    /** End time (done, failed, cancelled; 0 while in progress). */
    val finishedAt: Long = 0,
)

/**
 * Forwards transfer progress to the platform UI.
 * Android: system notification (one per transfer, progress bar, cancel).
 * Desktop: tray notification on done/failed (progress in the in-app list).
 */
interface TransferNotifier {
    fun onStart(id: Int, fileName: String, kind: TransferKind) {}
    fun onProgress(id: Int, fileName: String, done: Long, total: Long, kind: TransferKind) {}
    fun onCompleted(id: Int, fileName: String, savedPath: String, kind: TransferKind) {}
    fun onFailed(id: Int, fileName: String, reason: String, kind: TransferKind) {}
    fun onCancelled(id: Int, fileName: String, kind: TransferKind) {}
}

/** Pairing-state errors from the other device while sending (0.2.36). Implemented by AppGraph with Pairing. */
interface PairingErrorHandler {
    /** The other device hasn't added this one ("not paired"). */
    fun onNotPaired(peer: Peer) {}

    /** The other device has unpaired this one ("unpaired"). */
    fun onUnpaired(peer: Peer) {}
}

/** iOS: background protection during transfers (BGContinuedProcessingTask). null elsewhere. */
interface TransferGuard {
    fun update(subtitle: String, percent: Int)
    fun end(success: Boolean)
}

interface TransferGuardFactory {
    /**
     * Called when a transfer starts (send right after a UI action; receive on accept, i.e. an iroh thread for auto-accept).
     * The iOS implementation dispatches synchronously to main. null if no slot is free.
     */
    fun begin(title: String, subtitle: String, onExpire: () -> Unit): TransferGuard?
}

/**
 * Manages transfers (send, receive). Progress display is per platform
 * (Android: system notification / desktop, iOS: in-app Transfers list).
 *  - Cancelling a send keeps the partial file (.peersync-part) on the other side, so the next attempt resumes.
 *  - For receiving, Incoming (FileServer PUT) feeds progress; cancel is handled by Incoming closing the connection.
 * Runs in the app scope so it continues across screens.
 */
class Transfers(
    private val node: IrohNode,
    private val scope: CoroutineScope,
    private val notifier: TransferNotifier = object : TransferNotifier {},
    private val guardFactory: TransferGuardFactory? = null,
    private val onSaved: (String) -> Unit = {},
    /**
     * History store (0.2.21). null = not persisted (tests). Kept until the user taps "Clear all"
     * (no size limit). Saved only on row add, state change or clear (not on each progress update).
     */
    private val historyStore: KeyValueStore? = null,
) {
    /**
     * What to do when a send is refused because of pairing (0.2.36; set by AppGraph):
     * "not paired" sends a pairing request, "unpaired" removes the peer here too.
     */
    var pairingErrors: PairingErrorHandler = object : PairingErrorHandler {}

    private val json = Json { ignoreUnknownKeys = true }
    private val saveLock = PsLock()
    private val jobs = mutableMapOf<Int, Job>()

    /** Cancel handlers for receiving (Incoming). id -> cancel action. */
    private val externalCancel = mutableMapOf<Int, () -> Unit>()

    private val _items = MutableStateFlow(loadHistory())
    val items: StateFlow<List<TransferItem>> = _items.asStateFlow()

    // Continue numbering so IDs don't collide with earlier rows (also used as Android notification IDs)
    private var nextId = maxOf(1000, _items.value.maxOfOrNull { it.id } ?: 0)

    // ---------------- Send (from us to the peer) ----------------

    /**
     * Sends items to peer (one send = one batch = one row in Transfers).
     * 1. Send OFFER and poll up to 120 s for consent
     * 2. Once accepted, PUT files in order (resuming any partial file on the other side)
     * @param onFinished called when the batch ends (success, failure, cancel). iOS cleans up temp files and security scopes.
     */
    fun send(peer: Peer, items: List<SendItem>, onFinished: () -> Unit = {}) =
        send(peer, items, emptyList(), onFinished)

    /**
     * Sends items (files) and texts (for the clipboard) to peer. Text only
     * completes on OFFER2 acceptance (no PUT).
     */
    fun send(peer: Peer, items: List<SendItem>, texts: List<String>, onFinished: () -> Unit = {}) {
        if (items.isEmpty() && texts.isEmpty()) {
            PsLog.d(TAG, "send: nothing to send")
            onFinished()
            return
        }
        val total = items.sumOf { it.size }
        val label = if (items.isEmpty()) textLabel(texts) else batchLabel(items.map { it.fileName })
        val id = ++nextId
        PsLog.i(TAG, "send #$id -> ${peer.name}: ${items.size} items, $total bytes, ${texts.size} texts")

        _items.update {
            listOf(
                TransferItem(
                    id = id, fileName = label, peerName = peer.name, kind = TransferKind.Send,
                    total = total, count = items.size, startedAt = epochMillis(),
                ),
            ) + it
        }
        persist()
        notifier.onStart(id, label, TransferKind.Send)
        val guard = guardFactory?.begin("Sending files", "$label (${peer.name})") { cancel(id) }

        jobs[id] = scope.launch {
            try {
                val client = node.client(peer.id)

                // 1. Offer -> await consent
                updateItem(id) { it.copy(indeterminate = true) }
                var resp = try {
                    client.offer(items.map { OfferItem(it.relPath, it.size, it.mtime) }, texts)
                } catch (e: RemoteException) {
                    // 0.2.36: the browse screen used to handle these; now sending is where they show up
                    when (e.message) {
                        Wire.ERR_NOT_PAIRED -> pairingErrors.onNotPaired(peer)
                        Wire.ERR_UNPAIRED -> pairingErrors.onUnpaired(peer)
                    }
                    throw translateRemote(e)
                }
                resp = Consent.awaitDecision(resp, { it.status == Wire.OFFER_PENDING }) { client.offerStatus(it.token) }
                if (resp.status == Wire.OFFER_REJECTED) {
                    throw IllegalStateException("The other device declined.")
                }
                PsLog.d(TAG, "send #$id accepted (token=${resp.token.take(8)})")

                // 2. PUT one file at a time
                var doneBefore = 0L
                val throttle = ProgressThrottle()
                for (item in items) {
                    client.put(resp.token, item) { done, _ ->
                        // Don't throttle the end of a file (done == item.size)
                        if (throttle.pass(done, item.size)) {
                            publishProgress(id, label, doneBefore + done, total, TransferKind.Send, guard)
                        }
                    }
                    doneBefore += item.size
                }
                PsLog.i(TAG, "send #$id done")
                updateItem(id) { it.copy(state = TransferState.Completed, done = it.total, indeterminate = false) }
                notifier.onCompleted(id, label, "", TransferKind.Send)
                guard?.end(true)
            } catch (e: CancellationException) {
                PsLog.d(TAG, "send #$id cancelled")
                updateItem(id) { it.copy(state = TransferState.Cancelled) }
                notifier.onCancelled(id, label, TransferKind.Send)
                guard?.end(false)
                throw e
            } catch (t: Throwable) {
                PsLog.w(TAG, "send #$id failed: ${t.message ?: t}")
                updateItem(id) { it.copy(state = TransferState.Failed, error = t.message ?: t.toString()) }
                notifier.onFailed(id, label, t.message ?: t.toString(), TransferKind.Send)
                guard?.end(false)
            } finally {
                jobs.remove(id)
                runCatching { onFinished() }
            }
        }
    }

    /** Rewords pairing-state errors into plain language. */
    private fun translateRemote(e: RemoteException): Throwable {
        val m = e.message ?: ""
        return when {
            m == Wire.ERR_UNPAIRED ->
                IllegalStateException("The other device has unpaired this device.")
            m == Wire.ERR_NOT_PAIRED ->
                IllegalStateException("The other device hasn't added this device. Pairing request sent; send again once it's allowed.")
            else -> e
        }
    }

    // ---------------- Receive (used by Incoming) ----------------

    /** Creates a receive batch row. @return transfer ID */
    fun beginReceive(peerName: String, fileNames: List<String>, total: Long, onCancel: () -> Unit): Int {
        val id = ++nextId
        val label = batchLabel(fileNames)
        _items.update {
            listOf(
                TransferItem(
                    id = id, fileName = label, peerName = peerName, kind = TransferKind.Receive,
                    total = total, count = fileNames.size, indeterminate = false,
                    startedAt = epochMillis(),
                ),
            ) + it
        }
        persist()
        externalCancel[id] = onCancel
        notifier.onStart(id, label, TransferKind.Receive)
        PsLog.d(TAG, "receive #$id from $peerName: ${fileNames.size} items, $total bytes")
        return id
    }

    /**
     * Receive progress. The caller (Incoming's ProgressThrottle) throttles.
     * 0.2.25: up to 0.2.24 it was called every 256KB and updated the list/Android notification each time.
     * [guard] shows % in the iOS system UI (BGContinuedProcessingTask).
     */
    fun receiveProgress(id: Int, done: Long, guard: TransferGuard? = null) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.state != TransferState.Running) return
        publishProgress(id, item.fileName, done, item.total, TransferKind.Receive, guard)
    }

    /** Sends progress to the list, platform notification and iOS system UI (shared by send/receive; pass throttled values). */
    private fun publishProgress(id: Int, label: String, done: Long, total: Long, kind: TransferKind, guard: TransferGuard?) {
        updateItem(id) { it.copy(done = done, total = total, indeterminate = false) }
        notifier.onProgress(id, label, done, total, kind)
        val percent = if (total > 0) (done * 100 / total).toInt() else -1
        guard?.update("$label · ${formatMb(done)} / ${formatMb(total)}", percent)
    }

    fun receiveFinished(id: Int, savedPath: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        externalCancel.remove(id)
        updateItem(id) { it.copy(state = TransferState.Completed, done = it.total, indeterminate = false) }
        if (savedPath.isNotEmpty()) onSaved(savedPath)
        notifier.onCompleted(id, item.fileName, savedPath, TransferKind.Receive)
        PsLog.d(TAG, "receive #$id done")
    }

    fun receiveFailed(id: Int, reason: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        externalCancel.remove(id)
        if (item.state != TransferState.Running) return
        updateItem(id) { it.copy(state = TransferState.Failed, error = reason) }
        notifier.onFailed(id, item.fileName, reason, TransferKind.Receive)
        PsLog.w(TAG, "receive #$id failed: $reason")
    }

    fun receiveCancelled(id: Int) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        externalCancel.remove(id)
        if (item.state != TransferState.Running) return
        updateItem(id) { it.copy(state = TransferState.Cancelled) }
        notifier.onCancelled(id, item.fileName, TransferKind.Receive)
        PsLog.d(TAG, "receive #$id cancelled")
    }

    /** Notifies MediaScanner etc. of a received file (Android). */
    fun notifySaved(path: String) = onSaved(path)

    // ---------------- Common ----------------

    fun cancel(id: Int) {
        val job = jobs[id]
        if (job != null) {
            PsLog.d(TAG, "cancel #$id requested")
            job.cancel()
            return
        }
        val ext = externalCancel[id]
        if (ext != null) {
            PsLog.d(TAG, "cancel #$id requested (receive)")
            ext()
            return
        }
        PsLog.d(TAG, "cancel #$id: no running job")
    }

    /** "Clear all" (History screen). Keeps rows in progress. */
    fun clearFinished() {
        val before = _items.value.size
        _items.update { list -> list.filter { it.state == TransferState.Running } }
        persist()
        PsLog.i(TAG, "history cleared: ${before - _items.value.size} rows (running kept: ${_items.value.size})")
    }

    fun hasRunning(): Boolean = _items.value.any { it.state == TransferState.Running }

    private fun updateItem(id: Int, f: (TransferItem) -> TransferItem) {
        var stateChanged = false
        _items.update { list ->
            list.map {
                if (it.id != id) {
                    it
                } else {
                    val n = f(it)
                    if (n.state != it.state) {
                        stateChanged = true
                        if (n.state != TransferState.Running) n.copy(finishedAt = epochMillis()) else n
                    } else {
                        n
                    }
                }
            }
        }
        if (stateChanged) persist()
    }

    // ---------------- History persistence (0.2.21) ----------------

    private fun persist() {
        val store = historyStore ?: return
        saveLock.withLock {
            try {
                store.putString(KEY_HISTORY, json.encodeToString(ListSerializer(TransferItem.serializer()), _items.value))
            } catch (t: Throwable) {
                PsLog.w(TAG, "history save failed: ${t.message ?: t}")
            }
        }
    }

    /**
     * Loads saved history. Rows in progress at the last exit are shown as interrupted (failed)
     * (not auto-resumed; receiving keeps partial files, so sending again resumes).
     */
    private fun loadHistory(): List<TransferItem> {
        val raw = historyStore?.getString(KEY_HISTORY) ?: return emptyList()
        return try {
            val list = json.decodeFromString(ListSerializer(TransferItem.serializer()), raw)
            var interrupted = 0
            val fixed = list.map {
                if (it.state == TransferState.Running) {
                    interrupted++
                    it.copy(state = TransferState.Failed, error = INTERRUPTED_REASON, indeterminate = false)
                } else {
                    it
                }
            }
            PsLog.d(TAG, "history loaded: ${fixed.size} rows (interrupted: $interrupted)")
            if (interrupted > 0) {
                historyStore.putString(KEY_HISTORY, json.encodeToString(ListSerializer(TransferItem.serializer()), fixed))
            }
            fixed
        } catch (t: Throwable) {
            PsLog.w(TAG, "history load failed (reset): ${t.message ?: t}")
            emptyList()
        }
    }

    companion object {
        const val CONSENT_TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 1_500L
        private const val KEY_HISTORY = "transferHistory"
        const val INTERRUPTED_REASON = "Interrupted because the app was closed."

        /** Display name of a text send: start of the first line + character count. */
        fun textLabel(texts: List<String>): String {
            val first = texts.firstOrNull().orEmpty().lineSequence().firstOrNull().orEmpty()
            val head = if (first.length > 20) first.take(20) + "…" else first
            val chars = texts.sumOf { it.length }
            return "Text \"$head\" ($chars chars${if (texts.size > 1) ", ${texts.size} items" else ""})"
        }

        /** Display name of a batch: the file name for 1 item, otherwise "first and N more". */
        fun batchLabel(fileNames: List<String>): String = when (fileNames.size) {
            0 -> "(none)"
            1 -> fileNames[0]
            else -> "${fileNames[0]} and ${fileNames.size - 1} more"
        }
    }
}

/**
 * Throttles progress updates (at most every [intervalMs]; the one where done reaches total always passes).
 * 0.2.25: shared by send and receive (download used it too until 0.2.36).
 */
internal class ProgressThrottle(private val intervalMs: Long = 200) {
    private var last = 0L

    fun pass(done: Long, total: Long): Boolean {
        val now = epochMillis()
        if (now - last > intervalMs || done >= total) {
            last = now
            return true
        }
        return false
    }
}

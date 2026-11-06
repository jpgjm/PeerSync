package com.example.peersync.data

import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.example.peersync.platform.PsLock
import com.example.peersync.platform.epochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "${AppInfo.NAME}/PairHistory"

/** Pairing history event (0.2.21). */
@Serializable
enum class PairingLogKind {
    /** We sent a request. */
    RequestSent,
    /** The other device allowed our request. */
    SentAccepted,
    /** The other device cancelled our request. */
    SentRejected,
    /** Our request didn't arrive or got no response. */
    SentFailed,
    /** A request arrived from the other device. */
    RequestReceived,
    /** We allowed the other device's request. */
    ReceivedAccepted,
    /** We cancelled the other device's request. */
    ReceivedRejected,
    /** We didn't answer the other device's request within 120 s. */
    ReceivedTimedOut,
    /** Device added (0.2.21 only; since 0.2.22 approval registers it = SentAccepted). */
    Added,
    /** Unpaired (removed) on this device. */
    UnpairedByMe,
    /** The other device unpaired (notice received, or found while browsing). */
    UnpairedByPeer,
    /** Removed here too after the other device unpaired (0.2.21 confirm dialog row; included in UnpairedByPeer since 0.2.22). */
    RemovedAfterUnpair,
    /** Kept here after the other device unpaired (0.2.21 only; "Keep" removed in 0.2.22; for showing old rows). */
    KeptAfterUnpair,
}

@Serializable
data class PairingLogEntry(
    val id: Long,
    /** Epoch millis. */
    val at: Long,
    val peerId: String,
    val peerName: String,
    val kind: PairingLogKind,
    /** Detail (failure reason, whether the notice was delivered, etc.). */
    val detail: String? = null,
) {
    val shortId: String get() = peerId.take(10)
}

/**
 * Pairing history (0.2.21). Stored as JSON in KeyValueStore and kept until the user taps "Clear all"
 * (no size limit). Newest first.
 */
class PairingHistory(private val store: KeyValueStore?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = PsLock()

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<PairingLogEntry>> = _entries.asStateFlow()

    private var nextId: Long = (_entries.value.maxOfOrNull { it.id } ?: 0L) + 1

    /** @return ID of the added row (for updating detail later) */
    fun add(peerId: String, peerName: String, kind: PairingLogKind, detail: String? = null): Long {
        val id = lock.withLock {
            val id = nextId++
            val e = PairingLogEntry(id, epochMillis(), peerId, peerName, kind, detail)
            _entries.value = listOf(e) + _entries.value
            save(_entries.value)
            id
        }
        PsLog.d(TAG, "add #$id $kind ${peerName} (${peerId.take(10)})${detail?.let { ": $it" } ?: ""}")
        return id
    }

    fun updateDetail(id: Long, detail: String) {
        lock.withLock {
            _entries.value = _entries.value.map { if (it.id == id) it.copy(detail = detail) else it }
            save(_entries.value)
        }
        PsLog.d(TAG, "update #$id: $detail")
    }

    fun clear() {
        val n = lock.withLock {
            val n = _entries.value.size
            _entries.value = emptyList()
            save(emptyList())
            n
        }
        PsLog.i(TAG, "cleared $n entries")
    }

    private fun save(list: List<PairingLogEntry>) {
        val s = store ?: return
        try {
            s.putString(KEY, json.encodeToString(ListSerializer(PairingLogEntry.serializer()), list))
        } catch (t: Throwable) {
            PsLog.w(TAG, "save failed: ${t.message ?: t}")
        }
    }

    private fun load(): List<PairingLogEntry> {
        val raw = store?.getString(KEY) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(PairingLogEntry.serializer()), raw).also {
                PsLog.d(TAG, "loaded ${it.size} entries")
            }
        } catch (t: Throwable) {
            PsLog.w(TAG, "load failed (history reset): ${t.message ?: t}")
            emptyList()
        }
    }

    companion object {
        private const val KEY = "pairingHistory"
    }
}

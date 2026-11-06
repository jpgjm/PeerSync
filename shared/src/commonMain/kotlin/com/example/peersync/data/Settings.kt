package com.example.peersync.data

import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.example.peersync.net.endpointIdFromSecret
import com.example.peersync.net.generateSecretKey
import com.example.peersync.platform.PsLock
import com.example.peersync.proto.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "${AppInfo.NAME}/Settings"

/**
 * Device settings, stored in KeyValueStore (platform implementation).
 * - secret key (= source of the device ID; "Reset device ID" replaces it, 0.2.36)
 * - device name (0.2.36; default = the platform's model name)
 * - paired devices (only connections from these are accepted)
 * - custom relay URL (empty = iroh public relay)
 * - Always listen (Android: foreground service / desktop: autostart + tray)
 * - iOS: Keep screen on / Keep alive with silent audio
 * - Accept without asking (save files from "Send" without consent)
 * No data migration from old storage locations (spec 2).
 */
@OptIn(ExperimentalEncodingApi::class)
class Settings(
    /** History (Transfers / Pairing) uses the same store. */
    val store: KeyValueStore,
    /** Device name used until the user sets one (Android: Build.MODEL, desktop: COMPUTERNAME, iOS: machine id). */
    val defaultDeviceName: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Secret key (replaced by [resetIdentity], 0.2.36). The node reads it on each start. */
    var secretKey: ByteArray = loadOrCreateKey()
        private set

    private val _endpointId = MutableStateFlow(endpointIdFromSecret(secretKey))
    /** This device's ID (public key). Changes on [resetIdentity]. */
    val endpointIdFlow: StateFlow<String> = _endpointId.asStateFlow()
    val endpointId: String get() = _endpointId.value

    /**
     * This device's name as set by the user (0.2.36). Empty = [defaultDeviceName].
     * Sent with PAIR / UNPAIR / PING and embedded in the QR / pairing code.
     */
    private val _customDeviceName = MutableStateFlow(store.getString(KEY_DEVICE_NAME) ?: "")
    val customDeviceName: StateFlow<String> = _customDeviceName.asStateFlow()

    /** The name in use (custom, or the default when not set). */
    val deviceName: String get() = _customDeviceName.value.ifBlank { defaultDeviceName }

    /** Sets the device name. Blank goes back to [defaultDeviceName]. Longer names are cut to the wire limit. */
    fun setDeviceName(name: String) {
        val n = name.trim().take(Wire.MAX_NAME_CHARS)
        store.putString(KEY_DEVICE_NAME, n)
        _customDeviceName.value = n
        PsLog.i(TAG, "device name set: ${n.ifBlank { "(default) $defaultDeviceName" }}")
    }

    private val peersLock = PsLock()
    private val _peers = MutableStateFlow(loadPeers())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /**
     * IDs of peers this device unpaired (0.2.21). They get ERR_UNPAIRED
     * (fallback when UNPAIR didn't arrive). Cleared once UNPAIR arrives, on re-add, or on pairing.
     * The 0.2.21 "Keep" record (keptAfterUnpair) was dropped in 0.2.22 (not read, not migrated).
     */
    private var unpairedByMe: Set<String> = loadIdSet(KEY_UNPAIRED_BY_ME)

    private val _relayUrl = MutableStateFlow(store.getString(KEY_RELAY) ?: "")
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    /**
     * Persisted on/off setting (0.2.25; unifies 4 identical items; keys, defaults and logs unchanged).
     */
    private inner class BoolPref(private val key: String, default: Boolean, private val logName: String) {
        private val flow = MutableStateFlow(store.getBoolean(key, default))
        val state: StateFlow<Boolean> = flow.asStateFlow()

        fun set(on: Boolean) {
            store.putBoolean(key, on)
            flow.value = on
            PsLog.d(TAG, "$logName=$on")
        }
    }

    /** Always listen. Default off on every platform (Android since 0.2.32, desktop since 0.2.33): the user turns it on. */
    private val alwaysListenPref = BoolPref(KEY_ALWAYS_LISTEN, false, "alwaysListen")
    val alwaysListen: StateFlow<Boolean> = alwaysListenPref.state

    /** Keep the screen on while the app is in front (all platforms since 0.2.32). Default off since 0.2.32. */
    private val keepScreenOnPref = BoolPref(KEY_KEEP_SCREEN_ON, false, "keepScreenOn")
    val keepScreenOn: StateFlow<Boolean> = keepScreenOnPref.state

    /** iOS: Keep alive with silent audio. Default OFF. */
    private val keepAliveAudioPref = BoolPref(KEY_KEEP_ALIVE_AUDIO, false, "keepAliveAudio")
    val keepAliveAudio: StateFlow<Boolean> = keepAliveAudioPref.state

    /** "Accept without asking". Default OFF (asks Accept/Decline each time, even for paired devices). */
    private val autoAcceptPref = BoolPref(KEY_AUTO_ACCEPT, false, "autoAcceptIncoming")
    val autoAcceptIncoming: StateFlow<Boolean> = autoAcceptPref.state

    fun setAutoAcceptIncoming(on: Boolean) = autoAcceptPref.set(on)

    fun setAlwaysListen(on: Boolean) = alwaysListenPref.set(on)

    fun setKeepScreenOn(on: Boolean) = keepScreenOnPref.set(on)

    fun setKeepAliveAudio(on: Boolean) = keepAliveAudioPref.set(on)

    /**
     * Where received files go on Android and the desktop (0.2.32): [SAVE_DEST_APP] (the default folder)
     * or [SAVE_DEST_CUSTOM] (the folder in [saveDestToken]). iOS keeps its own store in Swift.
     */
    private val _saveDestKind = MutableStateFlow(store.getString(KEY_SAVE_DEST_KIND) ?: SAVE_DEST_APP)
    val saveDestKind: StateFlow<String> = _saveDestKind.asStateFlow()

    /** The custom save folder (Android: SAF tree URI, desktop: folder path). Null until one is picked. */
    private val _saveDestToken = MutableStateFlow(store.getString(KEY_SAVE_DEST_TOKEN))
    val saveDestToken: StateFlow<String?> = _saveDestToken.asStateFlow()

    fun setSaveDestKind(kind: String) {
        store.putString(KEY_SAVE_DEST_KIND, kind)
        _saveDestKind.value = kind
        PsLog.d(TAG, "saveDestKind=$kind")
    }

    fun setSaveDestToken(token: String) {
        store.putString(KEY_SAVE_DEST_TOKEN, token)
        _saveDestToken.value = token
        PsLog.d(TAG, "saveDestToken set")
    }

    fun setRelayUrl(url: String) {
        val u = url.trim()
        store.putString(KEY_RELAY, u)
        _relayUrl.value = u
        PsLog.d(TAG, "relay url set: ${u.ifBlank { "(public relay)" }}")
    }

    fun addPeer(peer: Peer) {
        updatePeers { list -> list.filterNot { it.id == peer.id } + peer }
        clearUnpairMarks(peer.id)
        PsLog.i(TAG, "peer added: ${peer.name} (${peer.shortId})")
    }

    /**
     * @param byMe true = the user unpaired on this device (recorded so the peer gets ERR_UNPAIRED).
     *             false = removed after the peer unpaired (not recorded; ERR_NOT_PAIRED from then on).
     */
    fun removePeer(id: String, byMe: Boolean = true) {
        updatePeers { list -> list.filterNot { it.id == id } }
        if (byMe) unpairedByMe = (unpairedByMe + id).also { saveIdSet(KEY_UNPAIRED_BY_ME, it) }
        PsLog.i(TAG, "peer removed: ${id.take(10)} byMe=$byMe")
    }

    fun isUnpairedByMe(id: String): Boolean = id in unpairedByMe

    /**
     * Updates the saved name of a paired device when it reports a different one (PING, 0.2.36).
     * Does nothing if the device isn't paired, the name is blank, or unchanged.
     * @return true if the name changed
     */
    fun renamePeer(id: String, name: String): Boolean {
        val n = name.trim().take(Wire.MAX_NAME_CHARS)
        if (n.isBlank()) return false
        var old: String? = null
        updatePeers { list ->
            list.map {
                if (it.id == id && it.name != n) {
                    old = it.name
                    it.copy(name = n)
                } else {
                    it
                }
            }
        }
        val before = old ?: return false
        PsLog.i(TAG, "peer renamed: $before -> $n (${id.take(10)})")
        return true
    }

    /**
     * New device ID (0.2.36, "Reset device ID"): a new secret key, and the paired devices and
     * unpaired records are cleared (they refer to the old ID). Name, relay and other settings stay.
     * The caller notifies the paired devices first (while the old key is still in use) and restarts the node after.
     */
    fun resetIdentity() {
        val oldId = endpointId.take(10)
        val key = generateSecretKey()
        store.putString(KEY_SECRET, Base64.encode(key))
        secretKey = key
        _endpointId.value = endpointIdFromSecret(key)
        updatePeers { emptyList() }
        unpairedByMe = emptySet()
        saveIdSet(KEY_UNPAIRED_BY_ME, unpairedByMe)
        PsLog.i(TAG, "device ID reset: $oldId… -> ${endpointId.take(10)}…")
    }

    /** Clears the "unpaired" record on re-add, pairing, or when UNPAIR reaches the peer. */
    fun clearUnpairMarks(id: String) {
        if (id in unpairedByMe) {
            unpairedByMe = (unpairedByMe - id).also { saveIdSet(KEY_UNPAIRED_BY_ME, it) }
            PsLog.d(TAG, "unpaired-by-me mark cleared: ${id.take(10)}")
        }
    }

    private fun loadIdSet(key: String): Set<String> {
        val raw = store.getString(key) ?: return emptySet()
        return try {
            json.decodeFromString(ListSerializer(String.serializer()), raw).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun saveIdSet(key: String, ids: Set<String>) {
        store.putString(key, json.encodeToString(ListSerializer(String.serializer()), ids.toList()))
    }

    fun isPaired(id: String): Boolean = _peers.value.any { it.id == id }

    fun peerName(id: String): String = _peers.value.firstOrNull { it.id == id }?.name ?: id.take(10)

    /**
     * Changes the peer list and saves it. Under a lock (0.2.36): PING renames peers from iroh threads
     * while the UI or a pairing request may add or remove one at the same time.
     */
    private fun updatePeers(f: (List<Peer>) -> List<Peer>) {
        peersLock.withLock {
            val list = f(_peers.value)
            store.putString(KEY_PEERS, json.encodeToString(ListSerializer(Peer.serializer()), list))
            _peers.value = list
        }
    }

    private fun loadPeers(): List<Peer> {
        val raw = store.getString(KEY_PEERS) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(Peer.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadOrCreateKey(): ByteArray {
        store.getString(KEY_SECRET)?.let { saved ->
            try {
                val b = Base64.decode(saved)
                if (b.size == 32) return b
            } catch (_: Throwable) {
                // Recreate if corrupted
            }
        }
        val key = generateSecretKey()
        store.putString(KEY_SECRET, Base64.encode(key))
        PsLog.i(TAG, "secret key generated")
        return key
    }

    companion object {
        private const val KEY_SECRET = "secretKey"
        private const val KEY_PEERS = "peers"
        // "sharedFolderToken" (the shared folder, removed in 0.2.36) is no longer read; not migrated (spec 2)
        private const val KEY_DEVICE_NAME = "deviceName"
        private const val KEY_RELAY = "relayUrl"
        private const val KEY_ALWAYS_LISTEN = "alwaysListen"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_KEEP_ALIVE_AUDIO = "keepAliveAudio"
        private const val KEY_AUTO_ACCEPT = "autoAcceptIncoming"
        private const val KEY_UNPAIRED_BY_ME = "unpairedByMe"
        private const val KEY_SAVE_DEST_KIND = "saveDestKind"
        private const val KEY_SAVE_DEST_TOKEN = "saveDestToken"

        const val SAVE_DEST_APP = "app"
        const val SAVE_DEST_CUSTOM = "custom"
    }
}

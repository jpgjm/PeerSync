package com.example.peersync.bridge

import com.example.peersync.AppInfo
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.peersync.ui.AppTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.window.ComposeUIViewController
import com.example.peersync.AppGraph
import com.example.peersync.data.Incoming
import com.example.peersync.data.Pairing
import com.example.peersync.data.IosDownloadTargets
import com.example.peersync.data.IosFileSendItem
import com.example.peersync.data.IosKeyValueStore
import com.example.peersync.data.Settings
import com.example.peersync.data.TransferGuard
import com.example.peersync.data.TransferGuardFactory
import com.example.peersync.data.Transfers
import com.example.peersync.log.LogLevel
import com.example.peersync.log.PsLog
import com.example.peersync.net.IrohNode
import com.example.peersync.ui.App
import com.example.peersync.ui.SaveDestHooks
import com.example.peersync.ui.SendOption
import com.example.peersync.ui.SendPick
import com.example.peersync.ui.ShareAnchor
import com.example.peersync.ui.UiHooks
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/**
 * iOS entry point. Swift (PeerSyncApp.swift) calls start(bridge) at launch
 * and gets the Compose UI from MainViewController().
 * The Swift service layer logs by calling PsLog.log(...) directly via the framework.
 */
object IosApp {
    private const val TAG = "${AppInfo.NAME}/App"

    lateinit var graph: AppGraph
        private set
    private lateinit var bridge: IosBridge
    private lateinit var hooks: IosUiHooks
    private var started = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(bridge: IosBridge) {
        if (started) return
        started = true
        this.bridge = bridge

        // Console + Documents/logs/peersync.log (retrievable from the Files app).
        // Note: don't use NSLog. Passing a Kotlin String to variadic NSLog("%@", ...) from Kotlin/Native
        // isn't converted to CVarArg and crashes at launch (confirmed in a 0.2.1 .ips).
        PsLog.addSink { e -> println(e.line) }
        PsLog.addSink(FileLogSinkIos())

        // Device name: the model identifier until the user sets one in Settings (0.2.36)
        val settings = Settings(IosKeyValueStore(), defaultDeviceName = bridge.deviceName())
        val node = IrohNode(settings, appScope)
        val targets = IosDownloadTargets(
            rootPathProvider = { bridge.saveRootPath() },
            unavailableReason = { bridge.saveRootUnavailableReason() },
        )
        val guardFactory = object : TransferGuardFactory {
            override fun begin(title: String, subtitle: String, onExpire: () -> Unit): TransferGuard? {
                val h = bridge.beginTransferGuard(title, subtitle, onExpire) ?: return null
                return object : TransferGuard {
                    override fun update(subtitle: String, percent: Int) = h.update(subtitle, percent)
                    override fun end(success: Boolean) = h.end(success)
                }
            }
        }
        val transfers = Transfers(
            node = node,
            scope = appScope,
            guardFactory = guardFactory,
            historyStore = settings.store,
        )
        // Receiving side of "Send" (consent, saving). Saves to the "Save to" setting (Default / Custom)
        val incoming = Incoming(
            settings = settings,
            targets = targets,
            transfers = transfers,
            scope = appScope,
            guardFactory = guardFactory,
        )
        // IosNotifications builds notification content (title, body, ID) from PsText (0.2.25)
        incoming.notifier = IosNotifications.incomingNotifier(bridge)
        // Pairing requests (0.2.14). In the background, a local notification with "Allow / Cancel" buttons
        val pairing = Pairing(settings, node, appScope)
        pairing.notifier = IosNotifications.pairingNotifier(bridge)
        graph = AppGraph(settings, node, transfers, incoming = incoming, pairing = pairing)
        hooks = IosUiHooks(bridge, graph)

        // 0.2.30: version is read from Info.plist CFBundleShortVersionString (project.yml) (hard-coded v0.2.25 up to 0.2.29)
        PsLog.i(TAG, "${AppInfo.NAME} launch v${appVersion()} (${bridge.runtimeSummary()}) id=${settings.endpointId.take(10)}")

        node.start()
        applyKeepSettings()
    }

    /** Notification "Accept / Decline" (called from Swift's IncomingNotification). */
    fun decideOffer(token: String, accept: Boolean) {
        if (!started) return
        PsLog.d(TAG, "decideOffer from notification: accept=$accept")
        graph.incoming.decide(token, accept)
    }

    /** Notification "Allow / Cancel" (pairing request; called from Swift's IncomingNotification). */
    fun decidePairing(token: String, accept: Boolean) {
        if (!started) return
        PsLog.d(TAG, "decidePairing from notification: accept=$accept")
        graph.pairing.decide(token, accept)
    }

    /** When scenePhase becomes active (called from Swift). */
    fun onActive() {
        if (!started) return
        PsLog.d(TAG, "scene active")
        graph.node.start()
        applyKeepSettings()
    }

    /** When scenePhase becomes background (called from Swift). The node keeps running (reused on return). */
    fun onBackground() {
        if (!started) return
        PsLog.d(TAG, "scene background")
    }

    private fun applyKeepSettings() {
        bridge.applyKeepScreenOn(graph.settings.keepScreenOn.value)
        bridge.applyKeepAliveAudio(graph.settings.keepAliveAudio.value)
    }

    fun makeViewController(): UIViewController = ComposeUIViewController(
        configure = {
            // Info.plist has CADisableMinimumFrameDurationOnPhone, but
            // to launch reliably where Bundle.main is replaced (e.g. LiveContainer)
            // the check itself is disabled too (if it fails, Compose throws and crashes the app).
            enforceStrictPlistSanityCheck = false
        },
    ) {
        val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
        AppTheme(colorScheme = colors) {
            App(graph, hooks)
        }
    }
}

/**
 * App version (Info.plist CFBundleShortVersionString, set in project.yml). 0.2.30.
 * In LiveContainer, Bundle.main is the guest (PeerSync) bundle, so PeerSync's version is returned.
 */
private fun appVersion(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"

/** Log function easy to call from Swift (replaces qlog). level: 0=DEBUG 1=INFO 2=WARN 3=ERROR */
fun psLog(level: Int, tag: String, message: String) {
    PsLog.log(LogLevel.entries.getOrElse(level) { LogLevel.INFO }, tag, message)
}

/**
 * Appends to Documents/logs/peersync.log (matches the old iOS AppLog file output, plain text).
 * Writes via POSIX (fopen in append mode). No NSFileHandle (same reason as IosStorage.kt).
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private class FileLogSinkIos : com.example.peersync.log.LogSink {
    private val path: String by lazy {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: ""
        val dir = "$docs/${AppInfo.LOG_DIR}"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        "$dir/${AppInfo.LOG_FILE_BASE}.log"
    }

    override fun write(entry: com.example.peersync.log.LogEntry) {
        runCatching {
            val f = platform.posix.fopen(path, "ab") ?: return
            val bytes = (entry.line + "\n").encodeToByteArray()
            bytes.usePinned { pinned ->
                platform.posix.fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), f)
            }
            platform.posix.fclose(f)
        }
    }
}

private class IosUiHooks(private val bridge: IosBridge, private val graph: AppGraph) : UiHooks {
    override val canScanQr = true

    override fun scanQr(onResult: (String?) -> Unit, onMessage: (String) -> Unit) = bridge.scanQr(onResult)

    // "Share" = UIActivityViewController (the same one SwiftUI's ShareLink shows).
    // The sheet also has "Copy", so copying the Device ID goes through here too.
    override val canShareText = true

    override fun shareText(text: String, anchor: ShareAnchor?) {
        PsLog.d("${AppInfo.NAME}/App", "share sheet: anchor=$anchor")
        bridge.shareText(
            text,
            (anchor?.x ?: 0f).toDouble(),
            (anchor?.y ?: 0f).toDouble(),
            (anchor?.width ?: 0f).toDouble(),
            (anchor?.height ?: 0f).toDouble(),
        )
    }

    // "Send": like FlyingCarpet's Files App / Camera Roll / Send Folder (photos and videos split to match Android)
    override val sendOptions = SendOption.all

    override fun pickSendItems(option: SendOption, onResult: (SendPick?) -> Unit) {
        bridge.pickSendItems(option.id) { batch ->
            if (batch == null || batch.files.isEmpty()) {
                batch?.let { bridge.endSendBatch(it.batchId) }
                onResult(null)
            } else {
                PsLog.d("${AppInfo.NAME}/App", "send picker ${option.id}: ${batch.files.size} files (batch=${batch.batchId})")
                val items = batch.files.map { IosFileSendItem(it.relPath, it.path, it.size, it.mtime) }
                onResult(SendPick(items) { bridge.endSendBatch(batch.batchId) })
            }
        }
    }

    override fun readClipboardImage(): SendPick? {
        val batch = bridge.clipboardImage() ?: return null
        if (batch.files.isEmpty()) {
            bridge.endSendBatch(batch.batchId)
            return null
        }
        val items = batch.files.map { IosFileSendItem(it.relPath, it.path, it.size, it.mtime) }
        return SendPick(items) { bridge.endSendBatch(batch.batchId) }
    }

    override val alwaysListenDescription: String? = null // iOS has no way to stay resident

    override val showKeepAliveAudio = true

    override fun applyKeepScreenOn(on: Boolean) = bridge.applyKeepScreenOn(on)
    override fun applyKeepAliveAudio(on: Boolean) = bridge.applyKeepAliveAudio(on)

    override val saveDest: SaveDestHooks = object : SaveDestHooks {
        private val _state = MutableStateFlow(bridge.saveDestState().toUi())
        override val state: StateFlow<SaveDestHooks.State> = _state

        private fun refresh() {
            _state.value = bridge.saveDestState().toUi()
        }

        override fun selectApp() {
            bridge.setSaveDestKind("app")
            refresh()
        }

        override fun selectCustom() {
            bridge.setSaveDestKind("custom")
            refresh()
        }

        override fun pickCustomFolder(onDone: (Boolean) -> Unit) {
            bridge.pickSaveDestFolder { ok ->
                refresh()
                onDone(ok)
            }
        }

        override val openLabel = "Open in Files"

        override fun openInFilesApp() = bridge.openSaveRootInFilesApp()
    }

    private fun IosSaveDestState.toUi() = SaveDestHooks.State(
        kind = kind,
        appPath = appPath,
        // 0.2.28: the label says how the app runs (LiveContainer guest or a normal install)
        defaultLabel = if (inLiveContainer) "Default (LiveContainer)" else "Default (Standalone)",
        customName = customName,
        customPath = customPath,
    )
}

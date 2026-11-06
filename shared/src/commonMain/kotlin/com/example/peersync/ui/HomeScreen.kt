package com.example.peersync.ui

import com.example.peersync.AppInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChangeCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PublishedWithChanges
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.peersync.AppGraph
import com.example.peersync.data.PairingCode
import com.example.peersync.data.Peer
import com.example.peersync.data.PeerPresence
import com.example.peersync.data.Presence
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.example.peersync.log.PsLog
import com.example.peersync.net.IrohNode
import com.example.peersync.text.PsText
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

private const val TAG = "${AppInfo.NAME}/Home"

/** Navigation bar tabs (0.2.17). Device Hub is the default. */
private const val TAB_SETTINGS = "settings"
private const val TAB_DEVICE_HUB = "deviceHub"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    graph: AppGraph,
    hooks: UiHooks,
    onOpenLogs: () -> Unit,
    /** Opens the history screen (renamed from "Transfers" in 0.2.21; Transfers | Pairing) (0.2.19). */
    onOpenTransfers: () -> Unit,
) {
    val settings = graph.settings
    val node = graph.node

    val status by node.status.collectAsState()
    val peers by settings.peers.collectAsState()
    val relay by settings.relayUrl.collectAsState()
    // 0.2.36: the ID changes on "Reset device ID" and the name can be changed in Settings
    val endpointId by settings.endpointIdFlow.collectAsState()
    val customName by settings.customDeviceName.collectAsState()
    val deviceName = customName.ifBlank { settings.defaultDeviceName }
    val alwaysListen by settings.alwaysListen.collectAsState()
    val autoAccept by settings.autoAcceptIncoming.collectAsState()
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current
    // copy the device ID (tapping the ID box)
    val copyEndpointId = {
        PsLog.d(TAG, "copy endpoint id")
        clipboard.setText(AnnotatedString(endpointId))
        snackbar.show(PsText.copied)
    }

    var tab by rememberSaveable { mutableStateOf(TAB_DEVICE_HUB) }
    var showQr by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var editRelay by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }

    // 0.2.17: instead of one screen with everything, a navigation bar switches Settings / Device Hub.
    // Device Hub is the default. History and log screens (detail screens) are outside this Scaffold, so no bar.
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(hooks.appTitle) },
                actions = {
                    // 0.2.19: entry to the History (transfers / pairing) screen
                    if (tab == TAB_DEVICE_HUB) {
                        TooltipIconButton(PsText.history, Icons.Filled.History, onClick = {
                            PsLog.d(TAG, "open transfers from Device Hub")
                            onOpenTransfers()
                        })
                    }
                },
            )
        },
        bottomBar = {
            // 0.2.18: swapped to Device Hub left / Settings right. The bar is a snackbar anchor
            NavigationBar(Modifier.snackbarAnchor()) {
                NavigationBarItem(
                    selected = tab == TAB_DEVICE_HUB,
                    onClick = {
                        PsLog.d(TAG, "tab -> Device Hub")
                        tab = TAB_DEVICE_HUB
                    },
                    icon = { Icon(Icons.Filled.DeviceHub, contentDescription = null) },
                    label = { Text("Device Hub") },
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS,
                    onClick = {
                        PsLog.d(TAG, "tab -> Settings")
                        tab = TAB_SETTINGS
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
        floatingActionButton = {
            // "Add by QR code" and "Enter manually" moved from the header icons to the FAB menu (0.2.17)
            if (tab == TAB_DEVICE_HUB) {
                AddPeerFabMenu(
                    canScanQr = hooks.canScanQr,
                    onScanQr = {
                        hooks.scanQr(
                            onResult = { text ->
                                if (text != null) {
                                    showAdd = PairingCode.decode(text) ?: (text to null)
                                }
                            },
                            onMessage = { snackbar.show(it) },
                        )
                    },
                    onManual = { showAdd = "" to null },
                )
            }
        },
    ) { pad ->
        when (tab) {
            TAB_DEVICE_HUB -> DeviceHubTab(
                graph = graph,
                hooks = hooks,
                modifier = Modifier.padding(pad),
                status = status,
                peers = peers,
                endpointId = endpointId,
                deviceName = deviceName,
                onRestart = { node.requestRestart() },
                onCopyId = copyEndpointId,
                onShowQr = { showQr = true },
            )
            else -> SettingsTab(
                graph = graph,
                hooks = hooks,
                modifier = Modifier.padding(pad),
                status = status,
                relay = relay,
                deviceName = deviceName,
                onEditName = { editName = true },
                alwaysListen = alwaysListen,
                autoAccept = autoAccept,
                onEditRelay = { editRelay = true },
                onOpenLogs = onOpenLogs,
            )
        }
    }

    if (showQr) {
        val code = PairingCode.encode(endpointId, deviceName)
        AlertDialog(
            onDismissRequest = { showQr = false },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(PsText.close) } },
            title = { Text("This device's QR code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = rememberQrCodePainter(code),
                        contentDescription = "QR",
                        modifier = Modifier.size(280.dp).background(Color.White).padding(12.dp),
                    )
                }
            },
        )
    }

    showAdd?.let { (initialId, initialName) ->
        AddPeerDialog(
            initialId = initialId,
            initialName = initialName ?: "",
            selfId = endpointId,
            onDismiss = { showAdd = null },
            onAdd = { id, name ->
                val peer = Peer(id, name)
                showAdd = null
                // 0.2.22: adding only sends a request. The peer becomes paired once the other device allows it
                // (nothing is kept if declined or undelivered). While waiting it shows under "Pending"
                if (settings.isPaired(peer.id)) {
                    settings.addPeer(peer) // already paired: just rename
                } else {
                    PsLog.i(TAG, "add peer -> pair request only: ${peer.name} (${peer.shortId})")
                    snackbar.show("Pairing request sent. Waiting for the other device to approve.")
                }
                graph.pairing.requestTo(peer)
            },
        )
    }

    if (editRelay) {
        TextInputDialog(
            "Your own relay URL (leave empty for the public relay)", relay,
            hint = "e.g. https://relay.example.com",
            onDismiss = { editRelay = false },
        ) {
            settings.setRelayUrl(it)
            editRelay = false
            node.requestRestartNow()
        }
    }

    if (editName) {
        TextInputDialog(
            "${PsText.deviceNameTitle} (leave empty for \"${settings.defaultDeviceName}\")", customName,
            hint = settings.defaultDeviceName,
            onDismiss = { editName = false },
        ) {
            settings.setDeviceName(it)
            editName = false
        }
    }
}

/**
 * Device Hub tab: This device (ID / QR / Share), paired devices (with online status), pending requests.
 * The bottom-right FAB menu (Add by QR code / Enter manually) lives in HomeScreen's Scaffold.
 */
@Composable
private fun DeviceHubTab(
    graph: AppGraph,
    hooks: UiHooks,
    modifier: Modifier,
    status: IrohNode.Status,
    peers: List<Peer>,
    endpointId: String,
    deviceName: String,
    onRestart: () -> Unit,
    onCopyId: () -> Unit,
    onShowQr: () -> Unit,
) {
    val outgoing by graph.pairing.outgoing.collectAsState()
    /** Target of the unpair confirmation dialog (0.2.21). */
    var confirmUnpair by remember { mutableStateOf<Peer?>(null) }
    /** Target of the "Send" menu (0.2.36: on the peer row; was the browse screen's top bar). */
    var sendTo by remember { mutableStateOf<Peer?>(null) }
    val presence by graph.presence.states.collectAsState()
    /** Bumped by the refresh button (0.2.38): restarts the loop below = check now and count 30 s again. */
    var refreshTick by remember { mutableStateOf(0) }
    /** A check round is running (the refresh button shows a spinner meanwhile). */
    var checking by remember { mutableStateOf(false) }

    // 0.2.36: check which paired devices are online every 30 s while Device Hub is visible
    // (lifecycle STARTED: app in front on Android / iOS, window shown on desktop). Restarts when the
    // paired list changes so a new device is checked right away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val peerIds = peers.map { it.id }
    // also restarts when the node comes online, so devices are checked right away instead of 30 s later
    val nodeOnline = status is IrohNode.Status.Online
    LaunchedEffect(lifecycle, peerIds, nodeOnline, refreshTick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            PsLog.d(TAG, "presence checks on (${peerIds.size} devices)")
            try {
                while (true) {
                    checking = true
                    try {
                        graph.presence.checkAll()
                    } finally {
                        checking = false
                    }
                    delay(Presence.INTERVAL_MS)
                }
            } finally {
                PsLog.d(TAG, "presence checks off")
            }
        }
    }

    sendTo?.let { target ->
        SendMenu(graph, hooks, target, onDismiss = { sendTo = null })
    }

    confirmUnpair?.let { target ->
        ConfirmDialog(
            title = "Unpair this device?",
            text = "${PsText.peerWithId(target.name, target.shortId)} will be unpaired. This device is also removed from the other device automatically (if it's offline, the next time it connects to this device).",
            confirmLabel = "Unpair",
            onConfirm = {
                PsLog.i(TAG, "unpair confirmed by user: ${target.name} (${target.shortId})")
                graph.pairing.unpair(target)
                confirmUnpair = null
            },
            onDismiss = { confirmUnpair = null },
        )
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("This device") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 0.2.18: the status line (online, relay URL) is plain text instead of marquee (may wrap)
                    Text(statusText(status), Modifier.weight(1f))
                    // 0.2.32: on every platform (was iOS only). Stops the iroh node and starts a new one.
                    // 0.2.37: disabled while the node is starting or stopping (repeated taps crashed iOS)
                    val stopping by graph.node.stopping.collectAsState()
                    TooltipIconButton(
                        "Restart", Icons.Filled.RestartAlt, onClick = onRestart,
                        enabled = !stopping && status !is IrohNode.Status.Starting,
                    )
                }
                // The device ID is built like Canta's (package name in AppInfoDialog) (0.2.13):
                // a rounded (shapes.small) secondaryContainer Row with a small content_copy icon + text;
                // tapping the whole Row copies. Being the theme's secondary color, it always differs from
                // the surrounding card (surfaceContainerLow). Full card width, monospace text.
                // 0.2.18: back to plain text instead of marquee (all digits visible, wraps on narrow screens).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onCopyId)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = PsText.copyLabel,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        endpointId,
                        Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TooltipIconButton("Show QR code", Icons.Outlined.QrCode, onClick = onShowQr)
                    // 0.2.38: Windows uses "Share" too (its Copy button is gone); tapping the ID box copies
                    if (hooks.canShareText) {
                        // remember the button rect and pass it as the share sheet (iPad popover)
                        // origin. boundsInWindow is in px, so convert to dp/pt.
                        var anchor by remember { mutableStateOf<ShareAnchor?>(null) }
                        val density = LocalDensity.current.density
                        TooltipIconButton(
                            "Share", Icons.Filled.Share,
                            onClick = {
                                val code = PairingCode.encode(endpointId, deviceName)
                                PsLog.d(TAG, "share sheet: anchor=$anchor")
                                hooks.shareText(code, anchor)
                            },
                            modifier = Modifier.onGloballyPositioned { coords ->
                                val b = coords.boundsInWindow()
                                anchor = ShareAnchor(
                                    x = b.left / density,
                                    y = b.top / density,
                                    width = b.width / density,
                                    height = b.height / density,
                                )
                            },
                        )
                    }
                }
            }
        }

        item {
            // "Add by QR code" and "Enter manually" moved to the FAB menu, so just the header (0.2.17).
            // 0.2.38: refresh button at the right end = check which devices are online right now
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Paired devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (checking && peers.isNotEmpty()) {
                    // same 48 dp slot as the IconButton so the header doesn't jump
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                } else {
                    TooltipIconButton(
                        "Check online status", Icons.Filled.Refresh,
                        enabled = peers.isNotEmpty(),
                        onClick = {
                            PsLog.d(TAG, "refresh presence (${peers.size} devices)")
                            refreshTick++
                        },
                    )
                }
            }
        }

        if (peers.isEmpty()) {
            item {
                Text(
                    "No devices yet. When you add a device, it gets a pairing request and shows up here once it's allowed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(peers, key = { it.id }) { peer ->
            // 0.2.36: "name, ID, online status | vertical divider | Send, Unpair". The left side does nothing
            // when tapped (the browse screen was removed with the shared folder), so there's no chevron.
            Card(Modifier.fillMaxWidth()) {
                ActionDividerRow(
                    actions = {
                        TooltipIconButton(
                            PsText.send, Icons.AutoMirrored.Filled.Send,
                            modifier = Modifier.padding(start = 8.dp),
                            onClick = {
                                PsLog.d(TAG, "send menu for ${peer.name} (${peer.shortId})")
                                sendTo = peer
                            },
                        )
                        // 0.2.36: same icon as "You unpaired" in History (was a trash can).
                        // 0.2.21: the other device is notified too, so confirm in case of a mis-tap
                        TooltipIconButton(
                            PsText.unpair, Icons.Outlined.LinkOff,
                            modifier = Modifier.padding(end = 8.dp),
                            onClick = { confirmUnpair = peer },
                        )
                    },
                    dividerPadding = 12.dp,
                ) {
                    Column(Modifier.weight(1f).padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp)) {
                        MarqueeText(peer.name, style = MaterialTheme.typography.titleSmall)
                        MarqueeText(
                            peer.shortId + "…",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        PresenceLine(presence[peer.id] ?: PeerPresence.Checking)
                    }
                }
            }
        }

        // 0.2.22: devices we requested that haven't approved yet (listed separately from paired ones)
        val pendingOut = outgoing.filter { o -> peers.none { it.id == o.id } }
        if (pendingOut.isNotEmpty()) {
            item { Text("Pending", style = MaterialTheme.typography.titleMedium) }
            items(pendingOut, key = { "out-" + it.id }) { peer ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            MarqueeText(peer.name, style = MaterialTheme.typography.titleSmall)
                            MarqueeText(
                                peer.shortId + "…",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(
                                "Waiting for approval…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                }
            }
        }

        // 0.2.19: the transfer list moved out of Device Hub to the "History" screen opened from the top bar (the 0.2.19 "Transfers" screen)

        // bottom space so the FAB menu doesn't cover content
        item { Spacer(Modifier.size(88.dp)) }
    }
}

/**
 * Settings tab: device name, listening, Save to, receiving, relay (and device ID reset), logs.
 * 0.2.36: the shared folder card was removed with the feature.
 */
@Composable
private fun SettingsTab(
    graph: AppGraph,
    hooks: UiHooks,
    modifier: Modifier,
    status: IrohNode.Status,
    relay: String,
    deviceName: String,
    onEditName: () -> Unit,
    alwaysListen: Boolean,
    autoAccept: Boolean,
    onEditRelay: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val settings = graph.settings

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // 0.2.36: this device's name (default = the model name). Paired devices pick up a new name
            // with the next online check (PING carries it).
            SectionCard(
                PsText.deviceNameTitle,
                description = "Other devices see this name. Default: ${settings.defaultDeviceName}",
            ) {
                ValueActionRow(
                    value = deviceName,
                    actionLabel = PsText.change,
                    actionIcon = Icons.Filled.Edit,
                    onAction = {
                        PsLog.d(TAG, "edit device name")
                        onEditName()
                    },
                )
            }
        }

        // 0.2.18: listening has its own card. 0.2.32: "Keep screen on" (all platforms) and iOS's
        // silent-audio switch moved here from the relay card, since they also keep the device listening.
        item {
            SectionCard(PsText.listening) {
                hooks.alwaysListenDescription?.let { desc ->
                    SwitchRow("Always listen", desc, checked = alwaysListen) { on ->
                        PsLog.d(TAG, "alwaysListen switch -> $on")
                        if (on) {
                            // Android: notifications must be on first (opens the settings and waits)
                            hooks.beforeEnableAlwaysListen {
                                PsLog.d(TAG, "alwaysListen enabled")
                                settings.setAlwaysListen(true)
                                hooks.onAlwaysListenChanged(true)
                            }
                        } else {
                            settings.setAlwaysListen(false)
                            hooks.onAlwaysListenChanged(false)
                        }
                    }
                }
                val keepScreenOn by settings.keepScreenOn.collectAsState()
                SwitchRow(
                    "Keep screen on",
                    "Keeps the screen from turning off while ${AppInfo.NAME} is open.",
                    checked = keepScreenOn,
                ) {
                    settings.setKeepScreenOn(it)
                    hooks.applyKeepScreenOn(it)
                }
                if (hooks.showKeepAliveAudio) {
                    val keepAliveAudio by settings.keepAliveAudio.collectAsState()
                    SwitchRow(
                        "Keep alive with silent audio",
                        "Keeps listening for a while after the screen turns off (uses more battery).",
                        checked = keepAliveAudio,
                    ) {
                        settings.setKeepAliveAudio(it)
                        hooks.applyKeepAliveAudio(it)
                    }
                }
            }
        }

        // Save to (default / custom). iOS, and Android and desktop since 0.2.32
        hooks.saveDest?.let { dest ->
            item { SaveDestCard(dest) }
        }

        item {
            SectionCard("Receiving") {
                SwitchRow(
                    "Accept without asking",
                    if (autoAccept) "Files sent from paired devices are saved without asking."
                    else "Asks you to accept or decline each time (declined automatically after ${PsText.consentTimeoutSeconds} seconds).",
                    checked = autoAccept,
                ) { on ->
                    PsLog.d(TAG, "autoAccept switch -> $on")
                    settings.setAutoAcceptIncoming(on)
                }
            }
        }

        item {
            SectionCard("Relay") {
                // 0.2.17: "Change" became an icon button. Like peer rows, split into
                // "URL | vertical divider | change icon". Three icons:
                //   not set (empty = public relay): change_circle
                //   set and connected (Online and homeRelay is our URL): published_with_changes
                //   set but not connected (starting, error, no relay, mismatch): sync_problem
                val relayUsable = remember(relay, status) {
                    val s = status
                    val ok = relay.isNotBlank() && s is IrohNode.Status.Online &&
                        normalizeRelayUrl(s.homeRelay ?: "") == normalizeRelayUrl(relay)
                    PsLog.d(TAG, "relay usable: $ok (relay=$relay status=$s)")
                    ok
                }
                val relayIcon = when {
                    relay.isBlank() -> Icons.Filled.ChangeCircle
                    relayUsable -> Icons.Filled.PublishedWithChanges
                    else -> Icons.Filled.SyncProblem
                }
                ValueActionRow(
                    value = if (relay.isBlank()) "iroh public relay (default)" else relay,
                    actionLabel = PsText.change,
                    actionIcon = relayIcon,
                    onAction = onEditRelay,
                )
                // 0.2.36: "Reset device ID" (new key; paired devices are unpaired and told first)
                ResetIdRow(graph)
            }
        }

        item {
            // 0.2.17: tapping the whole card opens the log screen (chevron like peer rows, no text button).
            // Android uses this card -> log screen too (the inline log at the bottom of home was removed).
            Card(onClick = {
                PsLog.d(TAG, "open logs")
                onOpenLogs()
            }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(PsText.logTitle, style = MaterialTheme.typography.titleMedium)
                        Text("Shows the in-app log.", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(Modifier.size(24.dp)) }
    }
}

/**
 * "Add device" FAB menu (0.2.17). Tapping expands "Add by QR code" and "Enter manually".
 * M3 Expressive FloatingActionButtonMenu + ToggleFloatingActionButton
 * (in Compose Multiplatform 1.9's material3; experimental API).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddPeerFabMenu(
    canScanQr: Boolean,
    onScanQr: () -> Unit,
    onManual: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            // 0.2.18: make the FAB a snackbar anchor (like setAnchorView(fab): the FAB stays put and
            // the snackbar shows above it)
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = {
                    PsLog.d(TAG, "fab menu expanded -> $it")
                    expanded = it
                },
                modifier = Modifier.snackbarAnchor(),
            ) {
                val icon by remember {
                    derivedStateOf { if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add }
                }
                // like the official sample, ToggleFloatingActionButtonDefaults.animateIcon changes the
                // icon color and size with the expansion (checkedProgress)
                Icon(
                    painter = rememberVectorPainter(icon),
                    contentDescription = if (expanded) PsText.close else PsText.addPeer,
                    modifier = with(ToggleFloatingActionButtonDefaults) {
                        Modifier.animateIcon({ checkedProgress })
                    },
                )
            }
        },
    ) {
        if (canScanQr) {
            FloatingActionButtonMenuItem(
                onClick = {
                    expanded = false
                    PsLog.d(TAG, "fab menu: scan qr")
                    onScanQr()
                },
                icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                text = { Text(PsText.addByQr) },
            )
        }
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                PsLog.d(TAG, "fab menu: manual input")
                onManual()
            },
            icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
            text = { Text("Enter manually") },
        )
    }
}

/** Normalizes relay URLs for comparison (trailing / and ., case; iroh may add the FQDN dot). */
private fun normalizeRelayUrl(s: String): String = s.trim().trimEnd('/', '.').lowercase()

private fun statusText(s: IrohNode.Status): String = when (s) {
    IrohNode.Status.Stopped -> "Stopped"
    IrohNode.Status.Starting -> "Starting…"
    is IrohNode.Status.Online -> "Online (relay: ${s.homeRelay ?: "-"})"
    is IrohNode.Status.OfflineOnlyDirect -> s.note
    is IrohNode.Status.Error -> "Error: ${s.message}"
}

@Composable
private fun SaveDestCard(dest: SaveDestHooks) {
    val state by dest.state.collectAsState()
    SectionCard(
        "Save to",
        spacing = 8.dp,
        titleAction = {
            TooltipIconButton(dest.openLabel, Icons.Filled.FolderOpen, onClick = {
                dest.openInFilesApp()
            })
        },
    ) {
        // 0.2.16: standard radio buttons (single choice) instead of a "✓" Text.
        // The whole row is selectable (Role.RadioButton); RadioButton itself has onClick = null (accessibility guidance)
        val appSelected = state.kind == "app"
        val customSelected = state.kind == "custom"
        val customUsable = state.customPath != null
        Column(Modifier.selectableGroup()) {
            // ○ Default
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = appSelected, onClick = { dest.selectApp() }, role = Role.RadioButton)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = appSelected, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    // 0.2.28: label depends on the environment (LiveContainer guest or standalone install)
                    Text(state.defaultLabel)
                    // 0.2.27: real path only, like Custom (dropped the Files app display path PeerSync/PeerSync)
                    MarqueeText(state.appPath, style = MaterialTheme.typography.bodySmall)
                }
            }
            // ○ Custom | Change (a vertical divider splits "select" and "change folder", like peer rows)
            // The icon differs for selected and accessible (customPath set) / otherwise (not selected or
            // inaccessible) (two variants since 0.2.11)
            ActionDividerRow(
                actionLabel = PsText.change,
                actionIcon = folderPickIcon(usable = customUsable),
                onAction = { dest.pickCustomFolder { } },
                dividerPadding = 8.dp,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = customSelected,
                            onClick = {
                                // if selected and accessible, just switch; otherwise open the folder picker
                                if (customUsable) dest.selectCustom() else dest.pickCustomFolder { }
                            },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = customSelected, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)) {
                        Text("Custom")
                        MarqueeText(
                            state.customPath
                                ?: state.customName?.let { "$it (can't access)" }
                                ?: "No folder selected",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Text(
            "Received files are saved to <Save to>/<device name>/<path on that device>.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Online status line of a paired device (0.2.36): a dot plus text (the text alone carries the state). */
@Composable
private fun PresenceLine(p: PeerPresence) {
    val (text, color) = when (p) {
        PeerPresence.Checking -> PsText.presenceChecking to MaterialTheme.colorScheme.outlineVariant
        is PeerPresence.Online -> PsText.presenceOnline(p.route) to MaterialTheme.colorScheme.primary
        PeerPresence.NotPairedThere -> PsText.presenceNotPairedThere to MaterialTheme.colorScheme.tertiary
        PeerPresence.Offline -> PsText.presenceOffline to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        MarqueeText(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Reset device ID" row in the Relay card (0.2.36). Confirms first, then Pairing.startResetIdentity()
 * (tells every paired device, new key, node restart). A spinner replaces the button while it runs (up to 20 s).
 */
@Composable
private fun ResetIdRow(graph: AppGraph) {
    val snackbar = LocalSnackbar.current
    val peers by graph.settings.peers.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val resetting by graph.pairing.resetting.collectAsState()

    ActionDividerRow(
        actions = {
            if (resetting) {
                Box(Modifier.size(48.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                }
            } else {
                TooltipIconButton(
                    PsText.resetIdTitle, Icons.Filled.LockReset,
                    modifier = Modifier.padding(start = 4.dp),
                    onClick = { confirm = true },
                )
            }
        },
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(PsText.resetIdTitle, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Gives this device a new ID. Paired devices are unpaired.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "${PsText.resetIdTitle}?",
            text = PsText.resetIdBody(peers.size),
            confirmLabel = "Reset",
            onConfirm = {
                confirm = false
                PsLog.i(TAG, "reset device ID confirmed by user (${peers.size} paired)")
                // runs in the app scope; the snackbar controller is app-wide, so the result shows on any screen
                graph.pairing.startResetIdentity { notReached -> snackbar.show(PsText.resetIdDone(notReached)) }
            },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * Icon of the folder "Change" button (custom Save to; two variants since 0.2.11; the shared folder used it until 0.2.36).
 * Selected and accessible: published_with_changes / not selected or inaccessible: sync_problem
 */
private fun folderPickIcon(usable: Boolean): ImageVector =
    if (usable) Icons.Filled.PublishedWithChanges else Icons.Filled.SyncProblem

@Composable
private fun AddPeerDialog(
    initialId: String,
    initialName: String,
    selfId: String,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var raw by remember { mutableStateOf(initialId) }
    val parsed = PairingCode.decode(raw)
    val error = when {
        raw.isBlank() -> null
        parsed == null -> "Invalid device ID format"
        parsed.first == selfId -> "This is this device's own ID"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PsText.addPeer) },
        text = {
            // 0.2.18: fields span the dialog width (follows the screen width)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 0.2.37: Paste / Clear button at the end of every field
                PasteClearTextField(
                    raw, { raw = it }, label = "Device ID of the other device", error = error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && error == null, onClick = {
                val (id, codeName) = parsed!!
                // 0.2.38: no Name field (the other device's Device name in Settings is the name). Until the
                // first online check (PING, right after it's paired) replaces it: the name in the pairing code
                // (QR / shared text), the QR scan's name, or the first 10 characters of the ID
                onAdd(id, codeName ?: initialName.ifBlank { id.take(10) })
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(PsText.cancel) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String? = null,
    /** Multi-line input ("Send" -> "Text"). OK is disabled while empty. */
    multiline: Boolean = false,
    okLabel: String = "OK",
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // 0.2.37: Paste / Clear button at the end (text to send, device name, relay URL)
            if (multiline) {
                PasteClearTextField(
                    value, { value = it }, placeholder = hint,
                    minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 0.2.18: single-line fields (relay URL etc.) also span the dialog width
                PasteClearTextField(
                    value, { value = it }, placeholder = hint,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOk(value) }, enabled = !multiline || value.isNotBlank()) { Text(okLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(PsText.cancel) } },
    )
}

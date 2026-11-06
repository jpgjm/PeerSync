package com.example.peersync.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.peersync.AppGraph
import androidx.compose.ui.unit.dp
import com.example.peersync.data.IncomingOffer
import com.example.peersync.data.PairingEvent
import com.example.peersync.data.PairingRequest
import com.example.peersync.data.UnpairNotice
import com.example.peersync.text.PsText
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.layout
import com.example.peersync.log.PsLog
import com.example.peersync.AppInfo

private const val TAG = "${AppInfo.NAME}/App"

/**
 * App navigation (home / history / logs). The browse screen was removed with the shared folder in 0.2.36.
 * The theme (MaterialTheme) is applied by each platform's entry point
 * (dynamic color on Android, default M3 colors on desktop and iOS).
 */
@Composable
fun App(graph: AppGraph, hooks: UiHooks) {
    // app-wide snackbar (with ×, Short). Screens call LocalSnackbar.current.show(...)
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarController(snackbarHost, scope) }
    // 0.2.18: show the snackbar above the anchor's top (like setAnchorView) so it doesn't cover the
    // navigation bar, FAB or system navigation bar. Screens without an anchor use the system bar.
    val anchors = remember { SnackbarAnchors() }
    var rootBottomPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalSnackbarAnchors provides anchors) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { rootBottomPx = it.boundsInWindow().bottom }) {
            AppContent(graph, hooks)
            val anchorTop = anchors.topPx
            val hostModifier = if (anchorTop != null && rootBottomPx > anchorTop) {
                Modifier.padding(bottom = with(density) { (rootBottomPx - anchorTop).toDp() } + 8.dp)
            } else {
                Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            }
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).then(hostModifier))
        }
    }
}

@Composable
private fun AppContent(graph: AppGraph, hooks: UiHooks) {
    var showLogs by rememberSaveable { mutableStateOf(false) }
    // History (transfers / pairing) screen (0.2.19)
    var showTransfers by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    val clipboard = LocalClipboardManager.current

    // copy accepted text to the clipboard (manual or auto; the foreground UI copies it)
    val textsToCopy by graph.incoming.textsToCopy.collectAsState()
    LaunchedEffect(textsToCopy) {
        val t = textsToCopy.firstOrNull() ?: return@LaunchedEffect
        clipboard.setText(AnnotatedString(t.text))
        graph.incoming.consumeText(t.token)
        snackbar.show(PsText.copiedToClipboard)
    }

    // incoming "Send" awaiting consent (the first one is shown in a dialog)
    val offers by graph.incoming.pending.collectAsState()

    // pairing requests from other devices (the first one is shown in a dialog, 0.2.14)
    val pairRequests by graph.pairing.pending.collectAsState()

    // notices that another device unpaired (the first one is shown in a dialog, 0.2.21)
    val unpairNotices by graph.pairing.unpairNotices.collectAsState()

    // 0.2.33: files from another device's "Send" started arriving (snackbar with "Show")
    LaunchedEffect(graph) {
        graph.incoming.receiveStarted.collect { offer ->
            snackbar.reportTransferStarted(PsText.receiveStarted(offer.peerName, offer.count)) { showTransfers = true }
        }
    }

    // results of our pairing requests (snackbar; declined/failed get "Request again", Long)
    LaunchedEffect(graph) {
        graph.pairing.events.collect { ev ->
            when (ev) {
                is PairingEvent.Accepted -> snackbar.show("Paired with ${ev.peer.name}.")
                // keep "declined" until × or "Request again" is pressed so it isn't missed (0.2.15). Failures stay Long
                is PairingEvent.Rejected -> snackbar.showWithAction(
                    "${ev.peer.name} declined pairing.", PsText.reRequest, indefinite = true,
                ) { graph.pairing.requestTo(ev.peer) }
                is PairingEvent.Failed -> snackbar.showWithAction(
                    "Couldn't send a pairing request to ${ev.peer.name}: ${ev.reason}", PsText.reRequest,
                ) { graph.pairing.requestTo(ev.peer) }
            }
        }
    }

    when {
        showTransfers -> HistoryScreen(graph, onBack = { showTransfers = false })
        showLogs -> LogScreen(onBack = { showLogs = false })
        else -> HomeScreen(
            graph = graph,
            hooks = hooks,
            onOpenLogs = { showLogs = true },
            onOpenTransfers = { showTransfers = true },
        )
    }

    offers.firstOrNull()?.let { offer ->
        IncomingOfferDialog(
            offer = offer,
            onAccept = { graph.incoming.decide(offer.token, accept = true) },
            onReject = { graph.incoming.decide(offer.token, accept = false) },
        )
    }

    pairRequests.firstOrNull()?.let { req ->
        PairingRequestDialog(
            request = req,
            onAccept = { graph.pairing.decide(req.token, accept = true) },
            onReject = { graph.pairing.decide(req.token, accept = false) },
        )
    }

    // show the "unpaired" notice only when no request dialog is up (never two at once)
    if (pairRequests.isEmpty()) {
        unpairNotices.firstOrNull()?.let { notice ->
            UnpairedDialog(notice = notice, onOk = { graph.pairing.dismissUnpairNotice(notice.peerId) })
        }
    }
}

/**
 * Pairing request dialog (0.2.14). "Allow" adds the other device to this device's allow list.
 * Outside taps and back don't close it (choose "Allow" or "Cancel"; auto-cancelled after 120 s).
 */
@Composable
private fun PairingRequestDialog(request: PairingRequest, onAccept: () -> Unit, onReject: () -> Unit) {
    ConfirmDialog(
        title = PsText.pairRequestTitle,
        text = PsText.pairRequestBody(request.peerName, request.shortId),
        confirmLabel = PsText.allow,
        onConfirm = onAccept,
        onDismiss = onReject,
        dismissOnOutside = false,
    )
}

/**
 * Notice that the other device unpaired (0.2.22; already removed from this device). OK button only.
 * Outside taps and back don't close it (OK does).
 */
@Composable
private fun UnpairedDialog(notice: UnpairNotice, onOk: () -> Unit) {
    ConfirmDialog(
        title = PsText.unpairedTitle,
        text = PsText.unpairedBody(notice.peerName, notice.shortId),
        confirmLabel = "OK",
        onConfirm = onOk,
        onDismiss = onOk,
        dismissLabel = null,
        dismissOnOutside = false,
    )
}

/**
 * "Accept / Decline" consent dialog (matches QSProbe's consent sheet).
 * Text-only offers show the text with "Copy / Close" (like LocalSend; Copy = accept).
 */
@Composable
private fun IncomingOfferDialog(offer: IncomingOffer, onAccept: () -> Unit, onReject: () -> Unit) {
    val textOnly = offer.isTextOnly
    AlertDialog(
        onDismissRequest = { /* outside taps don't close it (a decision is needed) */ },
        title = { Text(PsText.offerTitle(offer.peerName, textOnly)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (offer.items.isNotEmpty()) {
                    Text(PsText.offerFilesQuestion(offer.count, offer.totalBytes))
                    // Every item, scrollable (like Hail's list dialog). The question above, the timeout
                    // note and the buttons stay fixed; weight(fill = false) gives the list only the
                    // height left over, so short lists don't stretch the dialog.
                    OfferItemList(offer, Modifier.weight(1f, fill = false))
                }
                if (offer.texts.isNotEmpty()) {
                    val chars = offer.texts.sumOf { it.length }
                    Text(
                        if (textOnly) PsText.offerTextQuestion(chars)
                        else "Also includes text ($chars ${if (chars == 1) "character" else "characters"}, copied to the clipboard).",
                    )
                    Card(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        SelectionContainer {
                            Text(
                                offer.texts.joinToString("\n\n"),
                                Modifier.verticalScroll(rememberScrollState()).padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text("Declined automatically if you don't respond within ${PsText.consentTimeoutSeconds} seconds.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(PsText.offerAcceptLabel(textOnly)) } },
        dismissButton = { TextButton(onClick = onReject) { Text(PsText.offerRejectLabel(textOnly)) } },
    )
}

/** AlertDialog's horizontal content padding (M3 DialogPadding), used to run the dividers edge to edge. */
private val DialogSidePadding = 24.dp

/**
 * File list of an incoming offer. Shows every item and scrolls; a full-width divider appears above /
 * below the list only while items are hidden on that side (same look as Hail's working-mode dialog).
 */
@Composable
private fun OfferItemList(offer: IncomingOffer, modifier: Modifier = Modifier) {
    // Keyed by token: a new offer starts at the top instead of keeping the previous scroll position.
    val listState = remember(offer.token) { LazyListState() }
    LaunchedEffect(offer.token) {
        PsLog.d(TAG, "offer dialog: listing ${offer.items.size} items from ${offer.peerName}")
    }
    Column(modifier) {
        if (listState.canScrollBackward) HorizontalDivider(Modifier.dialogFullWidth())
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(offer.items) {
                Text("• ${it.relPath} · ${sizeText(it.size)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (listState.canScrollForward) HorizontalDivider(Modifier.dialogFullWidth())
    }
}

/** Widens a child past the dialog's side padding so it spans the whole dialog. */
private fun Modifier.dialogFullWidth(): Modifier = layout { measurable, constraints ->
    val extra = DialogSidePadding.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

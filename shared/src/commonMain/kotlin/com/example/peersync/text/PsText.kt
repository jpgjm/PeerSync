package com.example.peersync.text

import com.example.peersync.AppInfo
import com.example.peersync.data.TransferKind
import com.example.peersync.data.Transfers
import com.example.peersync.ui.sizeText

/**
 * Text shared by all three platforms, kept in one place (0.2.23).
 * Compose dialogs, Android notifications, Windows tray notifications and iOS (Swift) local notifications
 * used to carry separate copies of the same sentences, so wording changes were easy to miss.
 * Swift can call `PsText.shared.pairRequestBody(peerName:shortId:)` (since 0.2.25 iOS notification text is
 * also built in Kotlin by IosNotifications, so Swift no longer references it directly)
 * (Kotlin Int is Int32 in Swift, Long is Int64).
 *
 * Only text that is identical on every platform belongs here.
 * Platform-specific guidance (e.g. Windows' "choose ... in the window") stays on each platform's side.
 */
object PsText {
    // ---- Pairing ----

    val pairRequestTitle: String get() = "Pair with this device?"
    val unpairedTitle: String get() = "Device unpaired"

    /** "<name> (ID: <first 10 chars>…)". How dialog and notification bodies refer to the other device. */
    fun peerWithId(peerName: String, shortId: String): String = "$peerName (ID: $shortId…)"

    fun pairRequestBody(peerName: String, shortId: String): String =
        "${peerWithId(peerName, shortId)} wants to pair with this device. " +
            "If you allow it, it's added to \"Paired devices\" and you can send files and text to each other."

    fun unpairedBody(peerName: String, shortId: String): String =
        "${peerWithId(peerName, shortId)} unpaired from this device. It has been removed here too."

    // ---- Incoming "Send" from the other device (awaiting consent) ----

    fun offerTitle(peerName: String, isText: Boolean): String =
        if (isText) "Text from $peerName" else "Files from $peerName"

    fun offerFilesQuestion(count: Int, totalBytes: Long): String =
        "Accept $count ${if (count == 1) "item" else "items"} (${sizeText(totalBytes)} total)?"

    fun offerTextQuestion(chars: Int): String =
        "Copy text ($chars ${if (chars == 1) "character" else "characters"}) to the clipboard?"

    /** Notification body when auto-accepted via "Accept without asking" (0.2.24). */
    fun autoAcceptedFilesBody(count: Int, totalBytes: Long): String =
        "Started receiving $count ${if (count == 1) "item" else "items"} (${sizeText(totalBytes)} total)."

    /** Snackbar after received text was copied, and the Windows notification for auto-accepted text (0.2.37). */
    val copiedToClipboard: String get() = "Copied to the clipboard."

    // ---- Paste / Clear button of text fields (0.2.37) ----
    val paste: String get() = "Paste"
    val clear: String get() = "Clear"
    val clipboardEmpty: String get() = "The clipboard is empty."

    /** Snackbar when files from another device's "Send" start arriving (0.2.33). */
    fun receiveStarted(peerName: String, count: Int): String =
        "Receiving $count ${if (count == 1) "item" else "items"} from $peerName."

    /** Notification body when text was accepted in the background (copied once the app is in front). Android and iOS (0.2.25). */
    val textTapToCopy: String get() = "Tap to open and copy it to the clipboard."

    // ---- Transfers (shared by the history screen and Android transfer notifications, 0.2.25) ----

    /** "Resumes where it stopped" hint after a failure or interruption (shown in parentheses). */
    fun resumeHint(kind: TransferKind): String = when (kind) {
        TransferKind.Download -> "download again to resume where it stopped"
        TransferKind.Send -> "send again to resume where it stopped"
        TransferKind.Receive -> "resumes where it stopped if the other device sends again"
    }

    /** Shown before any progress (Send: waiting for approval, otherwise connecting). */
    fun waitingText(kind: TransferKind): String =
        if (kind == TransferKind.Send) "Waiting for approval…" else connecting

    /** "…and N more" when a list is cut short. */
    fun moreItems(rest: Int): String = "…and $rest more"

    /** File list for notifications (first max relative paths, one per line; the rest as "…and N more"). */
    fun itemListSummary(relPaths: List<String>, max: Int): String =
        relPaths.take(max).joinToString("\n") +
            if (relPaths.size > max) "\n" + moreItems(relPaths.size - max) else ""

    // ---- 0.2.30: short labels repeated across screens and platforms (buttons, titles, tooltips) ----
    // Swift references them as PsText.shared.accept etc.

    val accept: String get() = "Accept"
    val reject: String get() = "Decline"
    val allow: String get() = "Allow"
    val cancel: String get() = "Cancel"
    /** "Copy". Not named copy because it would clash with NSObject's copy in Swift (Objective-C). */
    val copyLabel: String get() = "Copy"
    val close: String get() = "Close"
    val copied: String get() = "Copied."
    val change: String get() = "Change"
    val send: String get() = "Send"
    val show: String get() = "Show"
    val history: String get() = "History"
    val logTitle: String get() = "Logs"
    val addPeer: String get() = "Add device"
    val addByQr: String get() = "Add by QR code"
    val reRequest: String get() = "Request again"
    val listening: String get() = "Listening"
    val connecting: String get() = "Connecting…"
    val receivingFiles: String get() = "Receiving files"
    val unpair: String get() = "Unpair"

    // ---- Device Hub: online status of paired devices (0.2.36) ----

    val presenceChecking: String get() = "Checking…"
    val presenceOffline: String get() = "Offline"
    val presenceNotPairedThere: String get() = "Online · hasn't added this device"
    fun presenceOnline(route: String?): String = if (route.isNullOrBlank()) "Online" else "Online · $route"

    // ---- Settings: device name and device ID reset (0.2.36) ----

    val deviceNameTitle: String get() = "Device name"
    val resetIdTitle: String get() = "Reset device ID"
    fun resetIdBody(pairedCount: Int): String =
        "This device gets a new ID. " +
            (if (pairedCount > 0) "All $pairedCount paired ${if (pairedCount == 1) "device is" else "devices are"} unpaired and this device is removed from them too. " else "") +
            "Devices that are offline now keep the old ID in their list, and transfers in progress stop. " +
            "To use them again, add this device again."
    fun resetIdDone(notReached: Int): String =
        if (notReached == 0) "Device ID reset."
        else "Device ID reset. $notReached ${if (notReached == 1) "device" else "devices"} couldn't be told (they keep the old ID in their list)."

    /** Positive button of the consent dialog/notification ("Copy" for text only). */
    fun offerAcceptLabel(isText: Boolean): String = if (isText) copyLabel else accept

    /** Negative button of the consent dialog/notification ("Close" for text only). */
    fun offerRejectLabel(isText: Boolean): String = if (isText) close else reject

    /** Title of the Android ongoing notification ("PeerSync is listening"). */
    val listeningTitle: String get() = "${AppInfo.NAME} is listening"

    /** Prompt on the QR scanner (Windows scan window; Google's Android scanner has its own text since 0.2.38). */
    val scanQrPrompt: String get() = "Scan the QR code shown in ${AppInfo.NAME} on the other device"

    /** Title of the shared device ID (the Windows share window requires one) (0.2.38). */
    val shareIdTitle: String get() = "${AppInfo.NAME} device ID"

    /** Android: snackbar while Google Code Scanner's module downloads on first use (0.2.38). */
    val qrScannerDownloading: String get() = "Downloading the QR scanner from Google Play services…"

    /** Android: Google Code Scanner couldn't open (no Play services, download failed…) (0.2.38). */
    fun qrScannerUnavailable(reason: String): String = "Couldn't open the QR scanner: $reason"

    /** Consent timeout in seconds (from Transfers.CONSENT_TIMEOUT_MS; hard-coded "120" on screen until 0.2.29). */
    val consentTimeoutSeconds: Long get() = Transfers.CONSENT_TIMEOUT_MS / 1000

    /** Name of the transfer kind (Android transfer notifications, Windows tray notifications). */
    fun transferVerb(kind: TransferKind): String = when (kind) {
        TransferKind.Download -> "Download"
        TransferKind.Send -> "Send"
        TransferKind.Receive -> "Receive"
    }

    /** Short name of the transfer kind (history screen; Download only appears in rows saved before 0.2.36). */
    fun transferKindLabel(kind: TransferKind): String = transferVerb(kind)

    // ---- Save to (Android and desktop since 0.2.32; iOS builds its own reasons in Swift) ----

    val saveDestDefault: String get() = "Default"
    val saveDestNotPicked: String get() = "No custom folder is selected."
    fun saveDestUnusable(folderName: String): String = "Can't access \"$folderName\". Pick the folder again."

    /** Title of the completion notification ("Download complete" etc., Android and Windows). */
    fun transferDoneTitle(kind: TransferKind): String = when (kind) {
        TransferKind.Download -> "Download complete"
        TransferKind.Send -> "Sent"
        TransferKind.Receive -> "Received"
    }
}

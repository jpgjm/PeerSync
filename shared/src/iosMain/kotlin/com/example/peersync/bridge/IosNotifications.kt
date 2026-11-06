package com.example.peersync.bridge

import com.example.peersync.AppInfo
import com.example.peersync.data.IncomingNotifier
import com.example.peersync.data.IncomingOffer
import com.example.peersync.data.PairingNotifier
import com.example.peersync.data.PairingRequest
import com.example.peersync.data.ReceivedText
import com.example.peersync.data.UnpairNotice
import com.example.peersync.text.PsText

/**
 * Builds iOS local notification content (ID, category, title, body) (0.2.25).
 *
 * Up to 0.2.24 Swift (IncomingNotification.swift) had 5 functions, one per kind, and built the text
 * itself (text offers used wording different from PsText). Now it's built here from PsText,
 * and Swift only gets the 2 functions IosBridge.postNotification / clearNotification.
 * Notification and category IDs are the same strings as 0.2.24 (removal and button handling depend on them).
 * Swift references categories via `IosNotifications.shared.categoryIncoming` etc.
 */
object IosNotifications {
    /** "Accept / Decline" buttons. */
    val categoryIncoming: String = AppInfo.identifier("incoming")

    /** Text-only offer ("Copy / Close" buttons). */
    val categoryIncomingText: String = AppInfo.identifier("incoming.text")

    /** Pairing request ("Allow / Cancel" buttons). */
    val categoryPair: String = AppInfo.identifier("pair")

    // Notification buttons (registered and matched by Swift's IncomingNotification; moved from Swift literals in 0.2.30, same values)
    val actionAccept: String = AppInfo.identifier("incoming.accept")
    val actionReject: String = AppInfo.identifier("incoming.reject")
    val actionPairAccept: String = AppInfo.identifier("pair.accept")
    val actionPairReject: String = AppInfo.identifier("pair.reject")

    fun offerId(token: String) = AppInfo.identifier("incoming.$token")
    fun textAcceptedId(token: String) = AppInfo.identifier("text.$token")
    fun autoAcceptedId(token: String) = AppInfo.identifier("received.$token")
    fun pairId(token: String) = AppInfo.identifier("pair.$token")
    fun unpairId(peerId: String) = AppInfo.identifier("unpair.$peerId")

    /** Notifications for the receiving side of "Send". */
    fun incomingNotifier(bridge: IosBridge) = object : IncomingNotifier {
        override fun onOffer(offer: IncomingOffer) {
            val body = if (offer.isTextOnly) {
                PsText.offerTextQuestion(offer.texts.sumOf { it.length }) + "\n" + offer.texts.joinToString(" ").take(120)
            } else {
                PsText.offerFilesQuestion(offer.count, offer.totalBytes) + "\n" +
                    PsText.itemListSummary(offer.items.map { it.relPath }, max = 5)
            }
            bridge.postNotification(
                notificationId = offerId(offer.token),
                title = PsText.offerTitle(offer.peerName, isText = offer.isTextOnly),
                body = body,
                category = if (offer.isTextOnly) categoryIncomingText else categoryIncoming,
                token = offer.token,
                logLabel = "Awaiting consent (${offer.peerName} / ${offer.count} items)",
            )
        }

        override fun onOfferResolved(token: String) = bridge.clearNotification(offerId(token))

        // The foreground UI does the copy, so in the background prompt "open to copy"
        override fun onTextAccepted(text: ReceivedText) = bridge.postNotification(
            notificationId = textAcceptedId(text.token),
            title = PsText.offerTitle(text.peerName, isText = true),
            body = PsText.textTapToCopy + "\n" + text.text.take(200),
            category = null,
            token = null,
            logLabel = "Text received (${text.peerName})",
        )

        // 0.2.24: auto-accept (fallback when a continued-processing task can't be requested in the background)
        override fun onFilesAutoAccepted(offer: IncomingOffer) = bridge.postNotification(
            notificationId = autoAcceptedId(offer.token),
            title = PsText.offerTitle(offer.peerName, isText = false),
            body = PsText.autoAcceptedFilesBody(offer.count, offer.totalBytes) + "\n" +
                PsText.itemListSummary(offer.items.map { it.relPath }, max = 5),
            category = null,
            token = null,
            logLabel = "Auto-received (${offer.peerName} / ${offer.count} items)",
        )
    }

    /** Pairing request and unpair notifications. */
    fun pairingNotifier(bridge: IosBridge) = object : PairingNotifier {
        override fun onRequest(request: PairingRequest) = bridge.postNotification(
            notificationId = pairId(request.token),
            title = PsText.pairRequestTitle,
            body = PsText.pairRequestBody(request.peerName, request.shortId),
            category = categoryPair,
            token = request.token,
            logLabel = "Pairing request (${request.peerName})",
        )

        override fun onRequestResolved(token: String) = bridge.clearNotification(pairId(token))

        override fun onUnpaired(notice: UnpairNotice) = bridge.postNotification(
            notificationId = unpairId(notice.peerId),
            title = PsText.unpairedTitle,
            body = PsText.unpairedBody(notice.peerName, notice.shortId),
            category = null,
            token = null,
            logLabel = "Unpaired (${notice.peerName})",
        )

        override fun onUnpairResolved(peerId: String) = bridge.clearNotification(unpairId(peerId))
    }
}
